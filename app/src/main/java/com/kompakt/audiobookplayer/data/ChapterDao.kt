package com.kompakt.audiobookplayer.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ChapterDao {

    @Query("SELECT * FROM chapters WHERE audiobookId = :audiobookId ORDER BY `index` ASC")
    suspend fun getChaptersForAudiobook(audiobookId: Long): List<Chapter>

    @Query("SELECT * FROM chapters WHERE audiobookId = :audiobookId ORDER BY `index` ASC")
    fun observeChaptersForAudiobook(audiobookId: Long): Flow<List<Chapter>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChapters(chapters: List<Chapter>)

    @Query("DELETE FROM chapters WHERE audiobookId = :audiobookId")
    suspend fun deleteChaptersForAudiobook(audiobookId: Long)
}
