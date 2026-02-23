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

            // Insert audiobook
            val audiobookId = audiobookDao.insertAudiobook(
                Audiobook(
                    title = title,
                    author = author,
                    folderUri = folderUri.toString()
                )
            )

            // Create chapters from audio files
            var totalDuration = 0L
            val chapters = audioFiles.mapIndexed { index, file ->
                var duration = 0L
                try {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(context, file.uri)
                    duration = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_DURATION
                    )?.toLongOrNull() ?: 0L
                    retriever.release()
                } catch (_: Exception) { }
                totalDuration += duration

                // Use metadata title or derive from filename
                val chapterTitle = file.name
                    ?.substringBeforeLast('.')
                    ?.replace(Regex("^\\d+[._\\-\\s]+"), "") // strip leading track numbers
                    ?: "Chapter ${index + 1}"

                Chapter(
                    audiobookId = audiobookId,
                    title = chapterTitle,
                    fileUri = file.uri.toString(),
                    index = index,
                    durationMs = duration,
                    fileName = file.name ?: "unknown"
                )
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
