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
     * Import an audiobook from a folder URI selected via SAF (Storage Access Framework).
     * Scans the folder for audio files and creates an Audiobook + Chapter entries.
     */
    fun importAudiobookFromFolder(folderUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            val folder = DocumentFile.fromTreeUri(context, folderUri) ?: return@launch

            // Supported audio extensions
            val audioExtensions = setOf("mp3", "m4a", "m4b", "ogg", "opus", "flac", "wav", "aac")

            val audioFiles = folder.listFiles()
                .filter { file ->
                    file.isFile && file.name?.substringAfterLast('.')
                        ?.lowercase() in audioExtensions
                }
                .sortedBy { it.name?.lowercase() }

            if (audioFiles.isEmpty()) return@launch

            // Derive title from folder name
            val title = folder.name ?: "Unknown Audiobook"

            // Try to extract artist from first file's metadata
            var author = "Unknown Author"
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(context, audioFiles.first().uri)
                author = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                    ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?: "Unknown Author"
                retriever.release()
            } catch (_: Exception) { }

            // Also try to get a better title from metadata
            var metadataTitle: String? = null
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(context, audioFiles.first().uri)
                metadataTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                retriever.release()
            } catch (_: Exception) { }

            // Insert audiobook
            val audiobookId = audiobookDao.insertAudiobook(
                Audiobook(
                    title = metadataTitle?.takeIf { it.isNotBlank() } ?: title,
                    author = author,
                    folderUri = folderUri.toString()
                )
            )

            // Check if we have M4B files with embedded chapters
            val m4bFiles = audioFiles.filter { file ->
                file.name?.substringAfterLast('.')?.lowercase() in setOf("m4b", "m4a")
            }

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

                // Try to extract embedded chapters from M4B/M4A files
                val embeddedChapters = if (ext == "m4b" || ext == "m4a") {
                    M4bChapterParser.extractChapters(context, file.uri)
                } else {
                    emptyList()
                }

                if (embeddedChapters.size > 1) {
                    // M4B with embedded chapters — create one Chapter per embedded chapter
                    // Fix the last chapter's duration using the actual file duration
                    val lastChapter = embeddedChapters.last()
                    val fixedChapters = embeddedChapters.toMutableList()
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
                    // Regular audio file or M4B without chapters — one chapter per file
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
    }

    fun deleteAudiobook(audiobook: Audiobook) {
        viewModelScope.launch(Dispatchers.IO) {
            audiobookDao.deleteAudiobook(audiobook)
        }
    }
}
