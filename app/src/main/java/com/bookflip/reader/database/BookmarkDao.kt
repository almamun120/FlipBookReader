// database/BookmarkDao.kt
package com.bookflip.reader.database

import androidx.lifecycle.LiveData
import androidx.room.*
import com.bookflip.reader.models.Bookmark

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY pageNumber ASC")
    fun getBookmarksForBook(bookId: Long): LiveData<List<Bookmark>>

    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId AND pageNumber = :page")
    suspend fun getBookmarkAtPage(bookId: Long, page: Int): Bookmark?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmark(bookmark: Bookmark): Long

    @Update
    suspend fun updateBookmark(bookmark: Bookmark)

    @Delete
    suspend fun deleteBookmark(bookmark: Bookmark)

    @Query("DELETE FROM bookmarks WHERE bookId = :bookId AND pageNumber = :page")
    suspend fun deleteBookmarkAtPage(bookId: Long, page: Int)

    @Query("SELECT COUNT(*) FROM bookmarks WHERE bookId = :bookId AND pageNumber = :page")
    suspend fun isPageBookmarked(bookId: Long, page: Int): Int

    @Query("DELETE FROM bookmarks WHERE bookId = :bookId")
    suspend fun deleteForBook(bookId: Long)
}
