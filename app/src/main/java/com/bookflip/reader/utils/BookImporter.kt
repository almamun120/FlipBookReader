package com.bookflip.reader.utils

import android.content.Context
import android.net.Uri
import com.bookflip.reader.BookFlipApp
import com.bookflip.reader.models.Book
import com.bookflip.reader.models.FileType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * যেকোনো Uri (ফাইল পিকার, "Open with", Share) থেকে PDF/EPUB নিয়ে লাইব্রেরিতে যোগ করে।
 * একই ফাইল দ্বিতীয়বার আনলে নতুন এন্ট্রি হয় না — আগেরটাই ফেরত আসে।
 */
object BookImporter {

    sealed class Result {
        data class Success(val book: Book, val alreadyInLibrary: Boolean) : Result()
        data class Error(val message: String) : Result()
    }

    suspend fun import(context: Context, uri: Uri): Result = withContext(Dispatchers.IO) {
        val app = context.applicationContext as BookFlipApp
        val dao = app.database.bookDao()

        val displayName = FileUtils.getFileNameFromUri(context, uri)
        val type = FileUtils.detectType(context, uri, displayName)
        if (type == "UNKNOWN") return@withContext Result.Error("Unsupported file. Only PDF and EPUB are supported.")

        val ext = if (type == "PDF") "pdf" else "epub"
        val (path, existed) = FileUtils.copyToBooksDir(context, uri, ext)
            ?: return@withContext Result.Error("Could not read the file.")

        dao.getBookByPath(path)?.let { return@withContext Result.Success(it, true) }

        val meta = BookMetadataExtractor.extract(path, type)
        if (type == "PDF" && meta.totalPages == 0) {
            if (!existed) File(path).delete()
            return@withContext Result.Error("Cannot open this PDF (it may be password-protected or damaged).")
        }
        if (type == "EPUB" && meta.totalPages == 0) {
            if (!existed) File(path).delete()
            return@withContext Result.Error("Cannot open this EPUB (damaged or unsupported).")
        }

        val key = File(path).nameWithoutExtension
        val coverPath = meta.coverBytes?.let { BookMetadataExtractor.saveCoverToFile(context, it, key) } ?: ""
        val title = meta.title.ifBlank { displayName.substringBeforeLast('.').replace('_', ' ').trim().ifEmpty { "Untitled" } }

        val book = Book(
            title = title,
            author = meta.author,
            filePath = path,
            fileType = if (type == "PDF") FileType.PDF else FileType.EPUB,
            coverImagePath = coverPath,
            totalPages = meta.totalPages
        )
        val id = dao.insertBook(book)
        Result.Success(book.copy(id = id), false)
    }
}
