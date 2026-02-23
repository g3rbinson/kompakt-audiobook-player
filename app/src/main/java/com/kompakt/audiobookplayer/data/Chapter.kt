package com.kompakt.audiobookplayer.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Represents a single chapter/track within an audiobook.
 */
@Entity(
    tableName = "chapters",
    foreignKeys = [
        ForeignKey(
            entity = Audiobook::class,
            parentColumns = ["id"],
            childColumns = ["audiobookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("audiobookId")]
)
data class Chapter(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val audiobookId: Long,
    val title: String,
    val fileUri: String,        // URI to the audio file
    val index: Int,             // Order within the audiobook
    val durationMs: Long = 0,
    val fileName: String
)
