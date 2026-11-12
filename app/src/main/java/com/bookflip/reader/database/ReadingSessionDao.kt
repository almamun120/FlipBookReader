// database/ReadingSessionDao.kt
package com.bookflip.reader.database

import androidx.room.*
import com.bookflip.reader.models.ReadingSession

@Dao
interface ReadingSessionDao {
    @Insert
    suspend fun insertSession(session: ReadingSession): Long

    @Update
    suspend fun updateSession(session: ReadingSession)

    @Query("SELECT SUM(endTime - startTime) FROM reading_sessions WHERE bookId = :bookId AND endTime > 0")
    suspend fun getTotalReadingTime(bookId: Long): Long?

    @Query("SELECT * FROM reading_sessions WHERE bookId = :bookId ORDER BY date DESC")
    suspend fun getSessionsForBook(bookId: Long): List<ReadingSession>

    @Query("DELETE FROM reading_sessions WHERE bookId = :bookId")
    suspend fun deleteForBook(bookId: Long)
}
