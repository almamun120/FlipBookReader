// database/BookDao.kt
package com.bookflip.reader.database

import androidx.lifecycle.LiveData
import androidx.room.*
import com.bookflip.reader.models.Book

@Dao
interface BookDao {

    // ── Queries ─────────────────────────────────────────────────────────────

    @Query("SELECT * FROM books ORDER BY lastReadDate DESC")
    fun getAllBooks(): LiveData<List<Book>>

    /** Synchronous (for coroutines / stats) */
    @Query("SELECT * FROM books ORDER BY lastReadDate DESC")
    suspend fun getAllBooksSync(): List<Book>

    @Query("SELECT * FROM books WHERE isFavorite = 1 ORDER BY title ASC")
    fun getFavoriteBooks(): LiveData<List<Book>>

    @Query("SELECT * FROM books WHERE category = :category ORDER BY title ASC")
    fun getBooksByCategory(category: String): LiveData<List<Book>>

    @Query("SELECT * FROM books ORDER BY addedDate DESC LIMIT :limit")
    fun getRecentBooks(limit: Int = 10): LiveData<List<Book>>

    @Query("SELECT * FROM books WHERE id = :bookId")
    suspend fun getBookById(bookId: Long): Book?

    @Query("SELECT * FROM books WHERE filePath = :filePath LIMIT 1")
    suspend fun getBookByPath(filePath: String): Book?

    @Query(
        "SELECT * FROM books WHERE " +
                "LOWER(title) LIKE '%' || LOWER(:q) || '%' OR " +
                "LOWER(author) LIKE '%' || LOWER(:q) || '%' OR " +
                "LOWER(tags) LIKE '%' || LOWER(:q) || '%'"
    )
    fun searchBooks(q: String): LiveData<List<Book>>

    @Query("SELECT DISTINCT category FROM books WHERE category != '' ORDER BY category ASC")
    suspend fun getAllCategories(): List<String>

    // ── Mutations ────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBook(book: Book): Long

    @Update
    suspend fun updateBook(book: Book)

    @Delete
    suspend fun deleteBook(book: Book)

    @Query(
        "UPDATE books SET lastReadPage = :page, lastReadDate = :date, " +
                "readingProgress = :progress WHERE id = :bookId"
    )
    suspend fun updateReadingProgress(bookId: Long, page: Int, date: Long, progress: Float)

    @Query("UPDATE books SET totalPages = :totalPages WHERE id = :bookId")
    suspend fun updateTotalPages(bookId: Long, totalPages: Int)

    @Query("UPDATE books SET isFavorite = :isFavorite WHERE id = :bookId")
    suspend fun updateFavoriteStatus(bookId: Long, isFavorite: Boolean)

    @Query("DELETE FROM books WHERE id = :bookId")
    suspend fun deleteById(bookId: Long)

    @Query("UPDATE books SET coverImagePath = :path WHERE id = :bookId")
    suspend fun updateCoverPath(bookId: Long, path: String)
}