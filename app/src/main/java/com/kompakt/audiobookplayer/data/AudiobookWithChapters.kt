package com.kompakt.audiobookplayer.data

import androidx.room.Embedded
import androidx.room.Relation

/**
 * Audiobook with all its chapters, for convenient querying.
 */
data class AudiobookWithChapters(
    @Embedded val audiobook: Audiobook,
    @Relation(
        parentColumn = "id",
        entityColumn = "audiobookId"
    )
    val chapters: List<Chapter>
)
