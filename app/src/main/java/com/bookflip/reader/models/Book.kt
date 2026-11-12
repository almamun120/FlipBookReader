package com.bookflip.reader.models

import android.os.Parcelable
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.parcelize.Parcelize

@Parcelize
@Entity(tableName = "books")
data class Book(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val author: String = "",
    val description: String = "",
    val filePath: String,
    val fileType: FileType,
    val coverImagePath: String = "",
    val totalPages: Int = 0,
    val lastReadPage: Int = 0,
    val addedDate: Long = System.currentTimeMillis(),
    val lastReadDate: Long = 0,
    val isFavorite: Boolean = false,
    val category: String = "",
    val rating: Float = 0f,
    val readingProgress: Float = 0f,
    val tags: String = "" // comma-separated tags
) : Parcelable

enum class FileType {
    PDF, EPUB
}
