// database/HighlightDao.kt
package com.bookflip.reader.database

import androidx.lifecycle.LiveData
import androidx.room.*
import com.bookflip.reader.models.Highlight

@Dao
interface HighlightDao {
    @Query("SELECT * FROM highlights WHERE bookId = :bookId ORDER BY pageNumber ASC")
    fun getHighlightsForBook(bookId: Long): LiveData<List<Highlight>>

    @Query("SELECT * FROM highlights WHERE bookId = :bookId AND pageNumber = :page")
    suspend fun getHighlightsForPage(bookId: Long, page: Int): List<Highlight>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHighlight(highlight: Highlight): Long

    @Update
    suspend fun updateHighlight(highlight: Highlight)

    @Delete
    suspend fun deleteHighlight(highlight: Highlight)

    @Query("DELETE FROM highlights WHERE bookId = :bookId")
    suspend fun deleteForBook(bookId: Long)
}
