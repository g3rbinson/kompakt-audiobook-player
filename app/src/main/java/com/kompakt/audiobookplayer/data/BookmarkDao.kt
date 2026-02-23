package com.kompakt.audiobookplayer.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {

    @Query("SELECT * FROM bookmarks WHERE audiobookId = :audiobookId ORDER BY chapterIndex ASC, positionMs ASC")
    fun getBookmarksForAudiobook(audiobookId: Long): Flow<List<Bookmark>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmark(bookmark: Bookmark): Long

    @Delete
    suspend fun deleteBookmark(bookmark: Bookmark)
}
