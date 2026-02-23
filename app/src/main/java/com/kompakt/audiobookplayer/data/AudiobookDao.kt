package com.kompakt.audiobookplayer.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface AudiobookDao {

    @Query("SELECT * FROM audiobooks ORDER BY lastPlayedAt DESC")
    fun getAllAudiobooks(): Flow<List<Audiobook>>

    @Query("SELECT * FROM audiobooks WHERE id = :id")
    suspend fun getAudiobookById(id: Long): Audiobook?

    @Transaction
    @Query("SELECT * FROM audiobooks WHERE id = :id")
    suspend fun getAudiobookWithChapters(id: Long): AudiobookWithChapters?

    @Query("SELECT * FROM audiobooks WHERE lastPlayedAt > 0 ORDER BY lastPlayedAt DESC LIMIT 1")
    fun getLastPlayed(): Flow<Audiobook?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAudiobook(audiobook: Audiobook): Long

    @Update
    suspend fun updateAudiobook(audiobook: Audiobook)

    @Query("""
        UPDATE audiobooks 
        SET currentChapterIndex = :chapterIndex, 
            currentPositionMs = :positionMs, 
            lastPlayedAt = :timestamp
        WHERE id = :audiobookId
    """)
    suspend fun updateProgress(
        audiobookId: Long,
        chapterIndex: Int,
        positionMs: Long,
        timestamp: Long = System.currentTimeMillis()
    )

    @Query("UPDATE audiobooks SET isCompleted = :completed WHERE id = :audiobookId")
    suspend fun markCompleted(audiobookId: Long, completed: Boolean)

    @Query("SELECT * FROM audiobooks WHERE folderUri = :folderUri LIMIT 1")
    suspend fun getAudiobookByFolderUri(folderUri: String): Audiobook?

    @Query("SELECT * FROM audiobooks")
    suspend fun getAllAudiobooksSync(): List<Audiobook>

    @Delete
    suspend fun deleteAudiobook(audiobook: Audiobook)
}
