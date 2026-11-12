// models/Highlight.kt
package com.bookflip.reader.models

import android.os.Parcelable
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.parcelize.Parcelize

@Parcelize
@Entity(tableName = "highlights")
data class Highlight(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val bookId: Long,
    val pageNumber: Int,
    val selectedText: String,
    val note: String = "",
    val color: Int = 0xFFFFEB3B.toInt(),
    val startOffset: Int = 0,
    val endOffset: Int = 0,
    val createdDate: Long = System.currentTimeMillis()
) : Parcelable