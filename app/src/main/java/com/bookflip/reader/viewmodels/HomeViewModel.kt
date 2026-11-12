package com.bookflip.reader.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.map
import androidx.lifecycle.viewModelScope
import com.bookflip.reader.BookFlipApp
import com.bookflip.reader.models.Book
import com.bookflip.reader.utils.FileUtils
import kotlinx.coroutines.launch

enum class SortOrder { RECENTLY_ADDED, LAST_READ, TITLE, AUTHOR, PROGRESS }

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val db = (application as BookFlipApp).database
    private val bookDao = db.bookDao()

    val allBooks: LiveData<List<Book>> = bookDao.getAllBooks()

    /** 0 = সব, 1 = সাম্প্রতিক, 2 = প্রিয় */
    val tab = MutableLiveData(0)
    val query = MutableLiveData("")
    val category = MutableLiveData<String?>(null)
    val sortOrder = MutableLiveData(SortOrder.RECENTLY_ADDED)

    /** ট্যাব + সার্চ + ক্যাটাগরি + সর্ট একসাথে প্রয়োগ করা তালিকা (আগে আলাদা LiveData বারবার observe হতো) */
    val visibleBooks = MediatorLiveData<List<Book>>().apply {
        fun recompute() {
            val all = allBooks.value ?: return
            val q = query.value.orEmpty().trim().lowercase()
            val cat = category.value
            var list = all.asSequence()
            when (tab.value) {
                1 -> list = list.filter { it.lastReadDate > 0 }
                2 -> list = list.filter { it.isFavorite }
            }
            if (!cat.isNullOrBlank()) list = list.filter { it.category.equals(cat, true) }
            if (q.isNotEmpty()) list = list.filter {
                it.title.lowercase().contains(q) || it.author.lowercase().contains(q) ||
                    it.tags.lowercase().contains(q) || it.category.lowercase().contains(q)
            }
            val sorted = when {
                tab.value == 1 -> list.sortedByDescending { it.lastReadDate }
                else -> when (sortOrder.value ?: SortOrder.RECENTLY_ADDED) {
                    SortOrder.RECENTLY_ADDED -> list.sortedByDescending { it.addedDate }
                    SortOrder.LAST_READ -> list.sortedByDescending { it.lastReadDate }
                    SortOrder.TITLE -> list.sortedBy { it.title.lowercase() }
                    SortOrder.AUTHOR -> list.sortedBy { it.author.lowercase() }
                    SortOrder.PROGRESS -> list.sortedByDescending { it.readingProgress }
                }
            }
            value = sorted.toList()
        }
        addSource(allBooks) { recompute() }
        addSource(tab) { recompute() }
        addSource(query) { recompute() }
        addSource(category) { recompute() }
        addSource(sortOrder) { recompute() }
    }

    val categories: LiveData<List<String>> = allBooks.map { books ->
        books.map { it.category.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
    }

    /** সবচেয়ে সাম্প্রতিক পড়া, অসম্পূর্ণ বই — "Continue reading" কার্ডের জন্য */
    val continueBook: LiveData<Book?> = allBooks.map { books ->
        books.filter { it.lastReadDate > 0 && it.readingProgress < 0.995f }.maxByOrNull { it.lastReadDate }
    }

    fun insertBook(book: Book, onDone: (Long) -> Unit = {}) {
        viewModelScope.launch { onDone(bookDao.insertBook(book)) }
    }

    fun updateBook(book: Book) {
        viewModelScope.launch { bookDao.updateBook(book) }
    }

    fun toggleFavorite(book: Book) {
        viewModelScope.launch { bookDao.updateFavoriteStatus(book.id, !book.isFavorite) }
    }

    /** শুধু DB-সারি মোছে (Undo সম্ভব রাখতে); ফাইল মোছা আলাদা — [purgeBook] */
    fun deleteBookRow(book: Book) {
        viewModelScope.launch { bookDao.deleteBook(book) }
    }

    fun restoreBook(book: Book) {
        viewModelScope.launch { bookDao.insertBook(book) }
    }

    /** Undo আর সম্ভব না — ফাইল ও সংশ্লিষ্ট ডেটা মুছি */
    fun purgeBook(book: Book) {
        viewModelScope.launch {
            db.bookmarkDao().deleteForBook(book.id)
            db.highlightDao().deleteForBook(book.id)
            db.readingSessionDao().deleteForBook(book.id)
            FileUtils.deleteBookFiles(book.filePath, book.coverImagePath)
        }
    }
}
