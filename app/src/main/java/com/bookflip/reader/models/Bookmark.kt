package com.bookflip.reader.models

import android.os.Parcelable
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.parcelize.Parcelize

@Parcelize
@Entity(tableName = "bookmarks")
data class Bookmark(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val bookId: Long,
    val pageNumber: Int,
    val title: String,
    val note: String = "",
    val highlightedText: String = "",
    val createdDate: Long = System.currentTimeMillis(),
    val color: Int = 0xFFFFB74D.toInt() // bookmark color
) : Parcelable