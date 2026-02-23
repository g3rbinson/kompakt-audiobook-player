package com.kompakt.audiobookplayer.data

import android.content.Context
import android.net.Uri
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Parsed chapter from an M4B file.
 */
data class M4bChapter(
    val title: String,
    val startTimeMs: Long,
    val durationMs: Long
)

/**
 * Extracts embedded chapter markers from M4B (and M4A) audiobook files.
 *
 * M4B files are MP4 containers that can store chapters in two ways:
 *
 * 1. **QuickTime chapter track** (most common — iTunes, Libation, ffmpeg):
 *    A text track referenced by `tref/chap` in the audio track. Chapter names
 *    are stored as text samples in the file; timing comes from the sample table.
 *
 * 2. **Nero chapters** (`moov/udta/chpl`):
 *    A simpler format with timestamps and titles stored in a single atom.
 *
 * This parser handles both formats, trying QuickTime first.
 */
object M4bChapterParser {

    fun extractChapters(context: Context, fileUri: Uri): List<M4bChapter> {
        return try {
            val pfd = context.contentResolver.openFileDescriptor(fileUri, "r")
                ?: return emptyList()
            pfd.use {
                val channel = FileInputStream(it.fileDescriptor).channel
                extractFromChannel(channel)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun extractFromChannel(channel: FileChannel): List<M4bChapter> {
        // Find the moov box
        val moovInfo = findTopLevelBox(channel, "moov") ?: return emptyList()
        val moovContent = readBytes(channel, moovInfo.contentOffset, moovInfo.contentSize)

        // Try QuickTime chapter track first (most common)
        val qtChapters = tryQuickTimeChapters(moovContent, channel)
        if (qtChapters.isNotEmpty()) return qtChapters

        // Fall back to Nero chpl chapters
        return tryNeroChapters(moovContent)
    }

    // ── Box scanning utilities ──────────────────────────────────────────

    private data class BoxInfo(
        val offset: Long,       // Absolute file offset of the box start (including header)
        val totalSize: Long,    // Total box size including header
        val type: String
    ) {
        val headerSize: Int get() = if (totalSize != (totalSize.toInt().toLong() and 0xFFFFFFFFL) || totalSize == 1L) 16 else 8
        val contentOffset: Long get() = offset + headerSize
        val contentSize: Long get() = totalSize - headerSize
    }

    /**
     * Find a top-level box in the file by scanning from the start.
     */
    private fun findTopLevelBox(channel: FileChannel, targetType: String): BoxInfo? {
        val fileSize = channel.size()
        var pos = 0L
        while (pos < fileSize - 8) {
            val info = readBoxHeader(channel, pos) ?: break
            if (info.type == targetType) return info
            if (info.totalSize < 8) break
            pos += info.totalSize
        }
        return null
    }

    /**
     * Find all direct child boxes of a given type within a parent byte array.
     */
    private fun findChildBoxes(parentData: ByteArray, targetType: String): List<ByteArray> {
        val results = mutableListOf<ByteArray>()
        var pos = 0
        while (pos < parentData.size - 8) {
            val size32 = readInt(parentData, pos)
            val type = readType(parentData, pos + 4)
            val totalSize = if (size32 == 1 && pos + 16 <= parentData.size) {
                readLong(parentData, pos + 8)
            } else if (size32 == 0) {
                (parentData.size - pos).toLong()
            } else {
                size32.toLong() and 0xFFFFFFFFL
            }
            val headerSize = if (size32 == 1) 16 else 8

            if (totalSize < 8 || pos + totalSize > parentData.size) break

            if (type == targetType) {
                val contentStart = pos + headerSize
                val contentEnd = (pos + totalSize).toInt()
                if (contentStart < contentEnd) {
                    results.add(parentData.copyOfRange(contentStart, contentEnd))
                }
            }
            pos += totalSize.toInt()
        }
        return results
    }

    private fun findChildBox(parentData: ByteArray, targetType: String): ByteArray? {
        return findChildBoxes(parentData, targetType).firstOrNull()
    }

    private fun findBoxPath(data: ByteArray, path: List<String>): ByteArray? {
        var current = data
        for (type in path) {
            current = findChildBox(current, type) ?: return null
        }
        return current
    }

    private fun readBoxHeader(channel: FileChannel, pos: Long): BoxInfo? {
        val header = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
        header.limit(8)
        channel.position(pos)
        if (channel.read(header) < 8) return null
        header.flip()

        val size32 = header.int.toLong() and 0xFFFFFFFFL
        val typeBytes = ByteArray(4)
        header.get(typeBytes)
        val type = String(typeBytes, Charsets.US_ASCII)

        val totalSize = if (size32 == 1L) {
            header.clear(); header.limit(8)
            channel.position(pos + 8)
            if (channel.read(header) < 8) return null
            header.flip(); header.long
        } else if (size32 == 0L) {
            channel.size() - pos
        } else {
            size32
        }

        return BoxInfo(pos, totalSize, type)
    }

    private fun readBytes(channel: FileChannel, offset: Long, size: Long): ByteArray {
        if (size > 50_000_000 || size < 0) return ByteArray(0)
        val buf = ByteBuffer.allocate(size.toInt())
        channel.position(offset)
        while (buf.hasRemaining()) {
            if (channel.read(buf) < 0) break
        }
        return buf.array()
    }

    private fun readInt(data: ByteArray, pos: Int): Int {
        return ((data[pos].toInt() and 0xFF) shl 24) or
                ((data[pos + 1].toInt() and 0xFF) shl 16) or
                ((data[pos + 2].toInt() and 0xFF) shl 8) or
                (data[pos + 3].toInt() and 0xFF)
    }

    private fun readLong(data: ByteArray, pos: Int): Long {
        return ((readInt(data, pos).toLong() and 0xFFFFFFFFL) shl 32) or
                (readInt(data, pos + 4).toLong() and 0xFFFFFFFFL)
    }

    private fun readType(data: ByteArray, pos: Int): String {
        return String(data, pos, 4, Charsets.US_ASCII)
    }

    // ── QuickTime chapter track parsing ─────────────────────────────────

    private data class TrackInfo(
        val trackId: Int,
        val chapRefs: List<Int>,
        val timescale: Long,
        val sampleTable: ByteArray?
    )

    private fun tryQuickTimeChapters(moovData: ByteArray, channel: FileChannel): List<M4bChapter> {
        val trakBoxes = findChildBoxes(moovData, "trak")
        if (trakBoxes.isEmpty()) return emptyList()

        val tracks = trakBoxes.map { parseTrack(it) }

        // Find chapter track IDs referenced by tref/chap
        val chapTrackIds = tracks.flatMap { it.chapRefs }.toSet()
        if (chapTrackIds.isEmpty()) return emptyList()

        val chapterTrack = tracks.find { it.trackId in chapTrackIds } ?: return emptyList()
        val stbl = chapterTrack.sampleTable ?: return emptyList()
        val timescale = chapterTrack.timescale
        if (timescale <= 0) return emptyList()

        // Parse sample table
        val sampleDurations = parseStts(findChildBox(stbl, "stts"), timescale)
        val sampleSizes = parseStsz(findChildBox(stbl, "stsz"))
        val chunkOffsets = parseStco(findChildBox(stbl, "stco"))
            .ifEmpty { parseCo64(findChildBox(stbl, "co64")) }
        val sampleToChunk = parseStsc(findChildBox(stbl, "stsc"))

        if (sampleDurations.isEmpty() || sampleSizes.isEmpty() || chunkOffsets.isEmpty()) {
            return emptyList()
        }

        val sampleOffsets = resolveSampleOffsets(sampleToChunk, chunkOffsets, sampleSizes)
        if (sampleOffsets.size != sampleSizes.size) return emptyList()

        // Read chapter titles from the file
        val chapters = mutableListOf<M4bChapter>()
        var startTimeMs = 0L

        for (i in sampleDurations.indices) {
            if (i >= sampleOffsets.size || i >= sampleSizes.size) break
            val title = readChapterTitle(channel, sampleOffsets[i], sampleSizes[i], i)
            val durationMs = sampleDurations[i]
            chapters.add(M4bChapter(title = title, startTimeMs = startTimeMs, durationMs = durationMs))
            startTimeMs += durationMs
        }

        return chapters
    }

    private fun parseTrack(trakData: ByteArray): TrackInfo {
        val tkhd = findChildBox(trakData, "tkhd")
        val trackId = parseTkhd(tkhd)

        val tref = findChildBox(trakData, "tref")
        val chapRefs = if (tref != null) parseChapRef(tref) else emptyList()

        val mdia = findChildBox(trakData, "mdia")
        val mdhd = if (mdia != null) findChildBox(mdia, "mdhd") else null
        val timescale = parseMdhd(mdhd)

        val minf = if (mdia != null) findChildBox(mdia, "minf") else null
        val stbl = if (minf != null) findChildBox(minf, "stbl") else null

        return TrackInfo(trackId, chapRefs, timescale, stbl)
    }

    private fun parseTkhd(data: ByteArray?): Int {
        if (data == null || data.size < 12) return 0
        val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        val version = buf.get().toInt() and 0xFF
        buf.position(buf.position() + 3) // flags
        return if (version == 1) {
            if (data.size < 24) 0
            else { buf.position(buf.position() + 16); buf.int }
        } else {
            if (data.size < 16) 0
            else { buf.position(buf.position() + 8); buf.int }
        }
    }

    private fun parseChapRef(trefData: ByteArray): List<Int> {
        val chapBox = findChildBox(trefData, "chap") ?: return emptyList()
        val refs = mutableListOf<Int>()
        val buf = ByteBuffer.wrap(chapBox).order(ByteOrder.BIG_ENDIAN)
        while (buf.remaining() >= 4) { refs.add(buf.int) }
        return refs
    }

    private fun parseMdhd(data: ByteArray?): Long {
        if (data == null || data.size < 12) return 0
        val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        val version = buf.get().toInt() and 0xFF
        buf.position(buf.position() + 3) // flags
        return if (version == 1) {
            if (data.size < 28) 0
            else { buf.position(buf.position() + 16); buf.int.toLong() and 0xFFFFFFFFL }
        } else {
            if (data.size < 16) 0
            else { buf.position(buf.position() + 8); buf.int.toLong() and 0xFFFFFFFFL }
        }
    }

    /** Parse stts → per-sample durations in milliseconds. */
    private fun parseStts(data: ByteArray?, timescale: Long): List<Long> {
        if (data == null || data.size < 8 || timescale <= 0) return emptyList()
        val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        buf.position(4) // version + flags
        val entryCount = buf.int
        if (entryCount <= 0 || entryCount > 10000) return emptyList()

        val durations = mutableListOf<Long>()
        for (i in 0 until entryCount) {
            if (buf.remaining() < 8) break
            val sampleCount = buf.int
            val sampleDelta = buf.int.toLong() and 0xFFFFFFFFL
            val durationMs = sampleDelta * 1000 / timescale
            repeat(sampleCount) { durations.add(durationMs) }
        }
        return durations
    }

    /** Parse stsz → per-sample sizes in bytes. */
    private fun parseStsz(data: ByteArray?): List<Int> {
        if (data == null || data.size < 12) return emptyList()
        val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        buf.position(4) // version + flags
        val defaultSize = buf.int
        val sampleCount = buf.int
        if (sampleCount <= 0 || sampleCount > 100000) return emptyList()
        if (defaultSize != 0) return List(sampleCount) { defaultSize }

        val sizes = mutableListOf<Int>()
        for (i in 0 until sampleCount) {
            if (buf.remaining() < 4) break
            sizes.add(buf.int)
        }
        return sizes
    }

    /** Parse stco (32-bit chunk offsets). */
    private fun parseStco(data: ByteArray?): List<Long> {
        if (data == null || data.size < 8) return emptyList()
        val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        buf.position(4)
        val count = buf.int
        if (count <= 0 || count > 100000) return emptyList()
        val offsets = mutableListOf<Long>()
        for (i in 0 until count) {
            if (buf.remaining() < 4) break
            offsets.add(buf.int.toLong() and 0xFFFFFFFFL)
        }
        return offsets
    }

    /** Parse co64 (64-bit chunk offsets). */
    private fun parseCo64(data: ByteArray?): List<Long> {
        if (data == null || data.size < 8) return emptyList()
        val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        buf.position(4)
        val count = buf.int
        if (count <= 0 || count > 100000) return emptyList()
        val offsets = mutableListOf<Long>()
        for (i in 0 until count) {
            if (buf.remaining() < 8) break
            offsets.add(buf.long)
        }
        return offsets
    }

    /** Parse stsc (sample-to-chunk mapping). */
    private fun parseStsc(data: ByteArray?): List<Triple<Int, Int, Int>> {
        if (data == null || data.size < 8) return emptyList()
        val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        buf.position(4)
        val count = buf.int
        if (count <= 0 || count > 100000) return emptyList()
        val entries = mutableListOf<Triple<Int, Int, Int>>()
        for (i in 0 until count) {
            if (buf.remaining() < 12) break
            entries.add(Triple(buf.int, buf.int, buf.int))
        }
        return entries
    }

    /** Resolve per-sample absolute file offsets from stsc + stco + stsz. */
    private fun resolveSampleOffsets(
        stsc: List<Triple<Int, Int, Int>>,
        chunkOffsets: List<Long>,
        sampleSizes: List<Int>
    ): List<Long> {
        if (stsc.isEmpty() || chunkOffsets.isEmpty()) return emptyList()

        val offsets = mutableListOf<Long>()
        var sampleIdx = 0

        for (chunkIdx in chunkOffsets.indices) {
            val chunkNumber = chunkIdx + 1 // 1-based

            // Find which stsc entry applies
            var samplesInChunk = stsc[0].second
            for (e in stsc) {
                if (chunkNumber >= e.first) samplesInChunk = e.second
                else break
            }

            var offsetInChunk = 0L
            for (s in 0 until samplesInChunk) {
                if (sampleIdx >= sampleSizes.size) break
                offsets.add(chunkOffsets[chunkIdx] + offsetInChunk)
                offsetInChunk += sampleSizes[sampleIdx]
                sampleIdx++
            }
        }
        return offsets
    }

    /** Read a QuickTime text sample (2-byte length prefix + text). */
    private fun readChapterTitle(channel: FileChannel, offset: Long, size: Int, index: Int): String {
        if (size < 2) return "Chapter ${index + 1}"
        return try {
            val buf = ByteBuffer.allocate(minOf(size, 512)).order(ByteOrder.BIG_ENDIAN)
            channel.position(offset)
            channel.read(buf)
            buf.flip()
            val textLen = buf.short.toInt() and 0xFFFF
            if (textLen <= 0 || textLen > buf.remaining()) return "Chapter ${index + 1}"
            val textBytes = ByteArray(textLen)
            buf.get(textBytes)
            String(textBytes, Charsets.UTF_8).ifBlank { "Chapter ${index + 1}" }
        } catch (_: Exception) {
            "Chapter ${index + 1}"
        }
    }

    // ── Nero chapter parsing ────────────────────────────────────────────

    private fun tryNeroChapters(moovData: ByteArray): List<M4bChapter> {
        val chplData = findBoxPath(moovData, listOf("udta", "chpl")) ?: return emptyList()
        return parseChplBox(chplData)
    }

    private fun parseChplBox(data: ByteArray): List<M4bChapter> {
        if (data.size < 5) return emptyList()

        val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        val version = buf.get().toInt() and 0xFF
        buf.position(buf.position() + 3) // flags
        if (version == 1 && buf.remaining() >= 4) buf.position(buf.position() + 4)
        if (buf.remaining() < 1) return emptyList()

        val chapterCount = if (version >= 1 && buf.remaining() >= 4) buf.int
        else buf.get().toInt() and 0xFF
        if (chapterCount <= 0 || chapterCount > 1000) return emptyList()

        val chapters = mutableListOf<M4bChapter>()
        for (i in 0 until chapterCount) {
            if (buf.remaining() < 9) break
            val timestampMs = buf.long / 10_000
            val titleLen = buf.get().toInt() and 0xFF
            if (buf.remaining() < titleLen) break
            val titleBytes = ByteArray(titleLen)
            buf.get(titleBytes)
            chapters.add(M4bChapter(
                title = String(titleBytes, Charsets.UTF_8).ifBlank { "Chapter ${i + 1}" },
                startTimeMs = timestampMs, durationMs = 0
            ))
        }

        for (i in chapters.indices) {
            val end = if (i < chapters.size - 1) chapters[i + 1].startTimeMs else chapters[i].startTimeMs
            chapters[i] = chapters[i].copy(durationMs = end - chapters[i].startTimeMs)
        }
        return chapters
    }
}
