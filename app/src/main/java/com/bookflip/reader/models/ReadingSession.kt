package com.bookflip.reader.models

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "reading_sessions")
data class ReadingSession(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val bookId: Long,
    val startTime: Long,
    val endTime: Long = 0,
    val pagesRead: Int = 0,
    val date: Long = System.currentTimeMillis()
)