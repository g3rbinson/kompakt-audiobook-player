package com.kompakt.audiobookplayer.ui.library

import android.app.Application
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kompakt.audiobookplayer.data.AppDatabase
import com.kompakt.audiobookplayer.data.Audiobook
import com.kompakt.audiobookplayer.data.Chapter
import com.kompakt.audiobookplayer.data.M4bChapterParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Hybrid audiobook import logic:
 *
 * When the user picks a folder, we scan it recursively:
 *   - Each M4B/M4A file → its own audiobook (with embedded chapters)
 *   - Regular audio files (MP3, FLAC…) in a folder → grouped as one audiobook
 *   - Sub-folders are scanned recursively
 *
 * This naturally supports:
 *   - A folder of M4B files (each = separate book)
 *   - A folder of MP3 chapters (all = one book)
 *   - Nested structures like Author/Series/Book/chapters.mp3
 */
class LibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val audiobookDao = db.audiobookDao()
    private val chapterDao = db.chapterDao()

    val audiobooks: Flow<List<Audiobook>> = audiobookDao.getAllAudiobooks()

    private val audioExtensions = setOf("mp3", "m4a", "m4b", "ogg", "opus", "flac", "wav", "aac")
    private val m4bExtensions = setOf("m4b", "m4a")

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Import audiobooks from a folder picked via SAF.
     * Discovers all audiobooks inside (including sub-folders) and upserts them.
     * Books that previously existed under this root but are no longer found get removed.
     */
    fun importAudiobookFromFolder(folderUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            scanRootFolder(folderUri)
        }
    }

    /**
     * Re-scan every root folder so the library stays in sync with disk.
     * Call once on app startup.
     */
    fun syncAllAudiobooks() {
        viewModelScope.launch(Dispatchers.IO) {
            val rootUris = audiobookDao.getDistinctRootUris()
            for (rootUri in rootUris) {
                try {
                    scanRootFolder(Uri.parse(rootUri))
                } catch (_: Exception) {
                    // Folder no longer accessible — leave entries as-is
                }
            }
        }
    }

    fun deleteAudiobook(audiobook: Audiobook) {
        viewModelScope.launch(Dispatchers.IO) {
            audiobookDao.deleteAudiobook(audiobook)
        }
    }

    // ── Scan logic ──────────────────────────────────────────────────────

    /**
     * Represents a discovered audiobook source before it's written to the DB.
     */
    private data class DiscoveredBook(
        val sourceUri: String,       // Unique key: file URI for M4B, folder URI for MP3s
        val files: List<DocumentFile> // The audio files that make up this book
    )

    /**
     * Scan a root folder the user picked, discover all audiobooks inside,
     * and reconcile with the database.
     */
    private suspend fun scanRootFolder(rootUri: Uri) {
        val context = getApplication<Application>()
        val rootFolder = DocumentFile.fromTreeUri(context, rootUri) ?: return
        val rootUriStr = rootUri.toString()

        // 1. Discover all audiobooks in this tree
        val discovered = mutableListOf<DiscoveredBook>()
        discoverAudiobooks(rootFolder, discovered)

        // 2. Get existing books for this root
        val existingBooks = audiobookDao.getAudiobooksByRootUri(rootUriStr)
        val existingBySource = existingBooks.associateBy { it.folderUri }.toMutableMap()

        // 3. Upsert each discovered book
        val seenSourceUris = mutableSetOf<String>()
        for (book in discovered) {
            seenSourceUris.add(book.sourceUri)
            upsertAudiobook(book, rootUriStr, existingBySource[book.sourceUri])
        }

        // 4. Remove books that no longer exist on disk
        val toRemove = existingBooks.filter { it.folderUri !in seenSourceUris }
        if (toRemove.isNotEmpty()) {
            audiobookDao.deleteAudiobooks(toRemove)
        }
    }

    /**
     * Recursively discover audiobooks inside a folder.
     *
     * Rules:
     *   - Each M4B/M4A file with embedded chapters → individual audiobook
     *   - Any non-M4B audio files in the same folder → grouped as one audiobook
     *   - Sub-folders → recurse
     */
    private fun discoverAudiobooks(
        folder: DocumentFile,
        results: MutableList<DiscoveredBook>
    ) {
        val children = folder.listFiles()
        val audioFiles = children
            .filter { it.isFile && it.name?.substringAfterLast('.')?.lowercase() in audioExtensions }
            .sortedBy { it.name?.lowercase() }
        val subFolders = children.filter { it.isDirectory }

        // Separate M4B files from regular audio
        val m4bFiles = audioFiles.filter {
            it.name?.substringAfterLast('.')?.lowercase() in m4bExtensions
        }
        val regularFiles = audioFiles - m4bFiles.toSet()

        // Each M4B → its own audiobook (keyed by file URI)
        for (m4b in m4bFiles) {
            results.add(DiscoveredBook(
                sourceUri = m4b.uri.toString(),
                files = listOf(m4b)
            ))
        }

        // Regular audio files in this folder → one audiobook (keyed by folder URI)
        if (regularFiles.isNotEmpty()) {
            results.add(DiscoveredBook(
                sourceUri = folder.uri.toString(),
                files = regularFiles
            ))
        }

        // Recurse into sub-folders
        for (sub in subFolders) {
            discoverAudiobooks(sub, results)
        }
    }

    // ── Upsert a single audiobook ───────────────────────────────────────

    private suspend fun upsertAudiobook(
        discovered: DiscoveredBook,
        rootUri: String,
        existing: Audiobook?
    ) {
        val context = getApplication<Application>()
        val files = discovered.files
        if (files.isEmpty()) return

        // -- Metadata from first file --
        var author = "Unknown Author"
        var metadataTitle: String? = null
        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, files.first().uri)
            author = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?: "Unknown Author"
            metadataTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            retriever.release()
        } catch (_: Exception) { }

        // For single M4B files, prefer the metadata title or filename
        // For folders of MP3s, prefer the album metadata or folder name
        val isSingleM4b = files.size == 1 &&
                files.first().name?.substringAfterLast('.')?.lowercase() in m4bExtensions

        val fallbackTitle = if (isSingleM4b) {
            // Use filename without extension
            files.first().name?.substringBeforeLast('.') ?: "Unknown Audiobook"
        } else {
            // Use parent folder name
            files.first().parentFile?.name ?: "Unknown Audiobook"
        }

        val title = metadataTitle?.takeIf { it.isNotBlank() } ?: fallbackTitle

        // -- Upsert audiobook row --
        val audiobookId: Long
        if (existing != null) {
            audiobookDao.updateAudiobook(
                existing.copy(title = title, author = author, rootUri = rootUri)
            )
            audiobookId = existing.id
            chapterDao.deleteChaptersForAudiobook(audiobookId)
        } else {
            audiobookId = audiobookDao.insertAudiobook(
                Audiobook(
                    title = title,
                    author = author,
                    folderUri = discovered.sourceUri,
                    rootUri = rootUri
                )
            )
        }

        // -- Build chapters --
        var totalDuration = 0L
        val chapters = mutableListOf<Chapter>()
        var chapterIndex = 0

        for (file in files) {
            val ext = file.name?.substringAfterLast('.')?.lowercase()
            var fileDuration = 0L
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(context, file.uri)
                fileDuration = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION
                )?.toLongOrNull() ?: 0L
                retriever.release()
            } catch (_: Exception) { }

            // Try embedded chapters for M4B/M4A
            val embeddedChapters = if (ext in m4bExtensions) {
                M4bChapterParser.extractChapters(context, file.uri)
            } else {
                emptyList()
            }

            if (embeddedChapters.size > 1) {
                val fixedChapters = embeddedChapters.toMutableList()
                val lastChapter = fixedChapters.last()
                if (lastChapter.durationMs <= 0 && fileDuration > 0) {
                    fixedChapters[fixedChapters.size - 1] = lastChapter.copy(
                        durationMs = fileDuration - lastChapter.startTimeMs
                    )
                }
                for (embedded in fixedChapters) {
                    chapters.add(
                        Chapter(
                            audiobookId = audiobookId,
                            title = embedded.title,
                            fileUri = file.uri.toString(),
                            index = chapterIndex++,
                            durationMs = embedded.durationMs,
                            fileName = file.name ?: "unknown",
                            startTimeMs = embedded.startTimeMs
                        )
                    )
                }
                totalDuration += fileDuration
            } else {
                val chapterTitle = file.name
                    ?.substringBeforeLast('.')
                    ?.replace(Regex("^\\d+[._\\-\\s]+"), "")
                    ?: "Chapter ${chapterIndex + 1}"

                chapters.add(
                    Chapter(
                        audiobookId = audiobookId,
                        title = chapterTitle,
                        fileUri = file.uri.toString(),
                        index = chapterIndex++,
                        durationMs = fileDuration,
                        fileName = file.name ?: "unknown",
                        startTimeMs = 0
                    )
                )
                totalDuration += fileDuration
            }
        }

        chapterDao.insertChapters(chapters)

        audiobookDao.updateAudiobook(
            audiobookDao.getAudiobookById(audiobookId)!!.copy(
                totalDurationMs = totalDuration
            )
        )
    }
}
