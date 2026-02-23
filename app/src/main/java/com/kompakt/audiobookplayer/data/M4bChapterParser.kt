package com.kompakt.audiobookplayer.data

import android.content.Context
import android.net.Uri
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
 * M4B files are MP4 containers that store chapter data in one of two ways:
 * 1. Nero chapters — a `chpl` atom inside `moov/udta`
 * 2. QuickTime chapter track — a text track referenced by `moov/trak/tref/chap`
 *
 * This parser handles the common Nero `chpl` format used by most audiobook
 * encoders (iTunes, ffmpeg, Libation, etc.).
 */
object M4bChapterParser {

    /**
     * Extract chapters from an M4B file.
     * Returns an empty list if no chapters are found or the file is not a valid MP4.
     */
    fun extractChapters(context: Context, fileUri: Uri): List<M4bChapter> {
        return try {
            context.contentResolver.openInputStream(fileUri)?.use { stream ->
                parseChapters(stream)
            } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseChapters(stream: InputStream): List<M4bChapter> {
        // Navigate the MP4 box tree: we need to find moov/udta/chpl
        val moovData = findBoxPath(stream, listOf("moov", "udta", "chpl"))
            ?: return emptyList()
        return parseChplBox(moovData)
    }

    /**
     * Walk nested MP4 boxes to reach the target path.
     * Container boxes (moov, udta, trak, mdia, minf, stbl) are descended into.
     * Returns the raw data of the final box, or null if not found.
     */
    private fun findBoxPath(stream: InputStream, path: List<String>): ByteArray? {
        if (path.isEmpty()) return null

        var currentStream = stream
        for ((depth, targetType) in path.withIndex()) {
            val boxData = findBox(currentStream, targetType) ?: return null

            if (depth < path.size - 1) {
                // This is a container box — its content starts after the 8-byte header
                // (we already stripped the header in findBox, so boxData IS the content)
                currentStream = boxData.inputStream()
            } else {
                return boxData
            }
        }
        return null
    }

    /**
     * Scan through boxes at the current level looking for one with the given type.
     * Returns the box CONTENT (excluding the 8-byte header), or null.
     */
    private fun findBox(stream: InputStream, targetType: String): ByteArray? {
        while (true) {
            // Read 8-byte box header: [4 bytes size][4 bytes type]
            val header = ByteArray(8)
            val headerRead = readFully(stream, header)
            if (headerRead < 8) return null

            val size = ByteBuffer.wrap(header, 0, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
            val type = String(header, 4, 4, Charsets.US_ASCII)

            if (size < 8) return null // invalid box

            val contentSize = size - 8

            if (type == targetType) {
                // Found it — read the content
                if (contentSize > 10_000_000) return null // safety limit: 10 MB
                val content = ByteArray(contentSize.toInt())
                val read = readFully(stream, content)
                return if (read == contentSize.toInt()) content else null
            } else {
                // Skip this box
                skipFully(stream, contentSize)
            }
        }
    }

    /**
     * Parse the content of a `chpl` (Nero chapter) box.
     *
     * Format (version 1, most common):
     *   1 byte : version (0 or 1)
     *   3 bytes: flags
     *   -- if version 1: 4 bytes reserved --
     *   1 byte : chapter count (version 0) or 4 bytes chapter count (can vary)
     *   For each chapter:
     *     8 bytes: timestamp in 100-nanosecond units (big-endian)
     *     1 byte : title length
     *     N bytes: title (UTF-8)
     */
    private fun parseChplBox(data: ByteArray): List<M4bChapter> {
        if (data.size < 5) return emptyList()

        val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        val version = buf.get().toInt() and 0xFF
        // Skip 3 bytes of flags
        buf.position(buf.position() + 3)

        // Version 1 has 4 extra reserved bytes
        if (version == 1 && buf.remaining() >= 4) {
            buf.position(buf.position() + 4)
        }

        if (buf.remaining() < 1) return emptyList()

        // Chapter count — 1 byte in older format, but some encoders use 4 bytes
        // Heuristic: if version >= 1, try reading as 4-byte count first
        val chapterCount: Int
        if (version >= 1 && buf.remaining() >= 4) {
            chapterCount = buf.int
        } else {
            chapterCount = buf.get().toInt() and 0xFF
        }

        if (chapterCount <= 0 || chapterCount > 1000) return emptyList()

        val chapters = mutableListOf<M4bChapter>()
        for (i in 0 until chapterCount) {
            if (buf.remaining() < 9) break // need at least 8 (timestamp) + 1 (title len)

            val timestamp100ns = buf.long
            val timestampMs = timestamp100ns / 10_000 // 100ns → ms

            val titleLen = buf.get().toInt() and 0xFF
            if (buf.remaining() < titleLen) break

            val titleBytes = ByteArray(titleLen)
            buf.get(titleBytes)
            val title = String(titleBytes, Charsets.UTF_8).ifBlank { "Chapter ${i + 1}" }

            chapters.add(M4bChapter(title = title, startTimeMs = timestampMs, durationMs = 0))
        }

        // Calculate durations from start times
        for (i in chapters.indices) {
            val start = chapters[i].startTimeMs
            val end = if (i < chapters.size - 1) chapters[i + 1].startTimeMs else start
            chapters[i] = chapters[i].copy(durationMs = end - start)
        }

        return chapters
    }

    private fun readFully(stream: InputStream, buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val read = stream.read(buffer, offset, buffer.size - offset)
            if (read < 0) break
            offset += read
        }
        return offset
    }

    private fun skipFully(stream: InputStream, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = stream.skip(remaining)
            if (skipped <= 0) {
                // Fallback: read and discard
                val toRead = minOf(remaining, 8192L).toInt()
                val buf = ByteArray(toRead)
                val read = stream.read(buf)
                if (read < 0) break
                remaining -= read
            } else {
                remaining -= skipped
            }
        }
    }
}
