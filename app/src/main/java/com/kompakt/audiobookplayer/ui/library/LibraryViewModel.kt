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

class LibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val audiobookDao = db.audiobookDao()
    private val chapterDao = db.chapterDao()

    val audiobooks: Flow<List<Audiobook>> = audiobookDao.getAllAudiobooks()

    /**
     * Import or update an audiobook from a folder URI selected via SAF.
     * If a book with the same folderUri already exists, its chapters are
     * re-scanned (preserving playback progress). Otherwise a new entry is created.
     */
    fun importAudiobookFromFolder(folderUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            syncFolder(folderUri)
        }
    }

    /**
     * Re-scan every previously-imported folder so the library stays in sync
     * with what's actually on disk. Call once on app startup.
     */
    fun syncAllAudiobooks() {
        viewModelScope.launch(Dispatchers.IO) {
            val allBooks = audiobookDao.getAllAudiobooksSync()
            for (book in allBooks) {
                try {
                    syncFolder(Uri.parse(book.folderUri))
                } catch (_: Exception) {
                    // If folder is no longer accessible, leave the entry as-is
                }
            }
        }
    }

    /**
     * Core sync logic: reads the audio files inside [folderUri] and upserts
     * the corresponding Audiobook + Chapter rows, preserving progress.
     */
    private suspend fun syncFolder(folderUri: Uri) {
        val context = getApplication<Application>()
        val folder = DocumentFile.fromTreeUri(context, folderUri) ?: return

        val audioExtensions = setOf("mp3", "m4a", "m4b", "ogg", "opus", "flac", "wav", "aac")

        val audioFiles = folder.listFiles()
            .filter { file ->
                file.isFile && file.name?.substringAfterLast('.')
                    ?.lowercase() in audioExtensions
            }
            .sortedBy { it.name?.lowercase() }

        // Look up existing audiobook for this folder
        val existing = audiobookDao.getAudiobookByFolderUri(folderUri.toString())

        if (audioFiles.isEmpty()) {
            // Folder is now empty — remove the audiobook if it existed
            existing?.let { audiobookDao.deleteAudiobook(it) }
            return
        }

        // -- Metadata --
        var author = "Unknown Author"
        var metadataTitle: String? = null
        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, audioFiles.first().uri)
            author = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?: "Unknown Author"
            metadataTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            retriever.release()
        } catch (_: Exception) { }

        val title = metadataTitle?.takeIf { it.isNotBlank() }
            ?: folder.name ?: "Unknown Audiobook"

        // -- Upsert audiobook row --
        val audiobookId: Long
        if (existing != null) {
            // Update metadata but keep progress fields
            audiobookDao.updateAudiobook(
                existing.copy(title = title, author = author)
            )
            audiobookId = existing.id
            // Wipe old chapters — they'll be re-created below
            chapterDao.deleteChaptersForAudiobook(audiobookId)
        } else {
            audiobookId = audiobookDao.insertAudiobook(
                Audiobook(
                    title = title,
                    author = author,
                    folderUri = folderUri.toString()
                )
            )
        }

        // -- Build chapters --
        var totalDuration = 0L
        val chapters = mutableListOf<Chapter>()
        var chapterIndex = 0

        for (file in audioFiles) {
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

            val embeddedChapters = if (ext == "m4b" || ext == "m4a") {
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

        // Update total duration
        audiobookDao.updateAudiobook(
            audiobookDao.getAudiobookById(audiobookId)!!.copy(
                totalDurationMs = totalDuration
            )
        )
    }

    fun deleteAudiobook(audiobook: Audiobook) {
        viewModelScope.launch(Dispatchers.IO) {
            audiobookDao.deleteAudiobook(audiobook)
        }
    }
}
