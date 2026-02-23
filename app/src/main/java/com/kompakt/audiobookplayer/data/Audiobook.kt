package com.kompakt.audiobookplayer.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Represents a single audiobook in the library.
 * An audiobook can consist of one or more audio files (chapters).
 */
@Entity(tableName = "audiobooks")
data class Audiobook(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val author: String = "Unknown Author",
    val folderUri: String,            // SAF URI to the audiobook folder
    val coverUri: String? = null,     // Optional cover image URI
    val totalDurationMs: Long = 0,    // Total duration across all chapters
    val currentChapterIndex: Int = 0, // Last played chapter index
    val currentPositionMs: Long = 0,  // Position within the current chapter
    val lastPlayedAt: Long = 0,       // Timestamp of last playback
    val addedAt: Long = System.currentTimeMillis(),
    val isCompleted: Boolean = false
)
