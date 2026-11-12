package com.bookflip.reader.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.bookflip.reader.BookFlipApp
import com.bookflip.reader.models.Book
import com.bookflip.reader.models.Bookmark
import com.bookflip.reader.models.ReadingSession
import kotlinx.coroutines.launch

class ReaderViewModel(application: Application) : AndroidViewModel(application) {

    private val db = (application as BookFlipApp).database
    private val bookDao = db.bookDao()
    private val bookmarkDao = db.bookmarkDao()
    private val sessionDao = db.readingSessionDao()

    var book: Book? = null
        private set

    private var bookmarks: LiveData<List<Bookmark>>? = null

    val isCurrentPageBookmarked = MutableLiveData(false)

    private var sessionId = -1L
    private var sessionStart = 0L
    private var sessionStartPage = 0
    private var sessionMaxPagesTurned = 0

    fun init(b: Book) {
        book = b
        bookmarks = bookmarkDao.getBookmarksForBook(b.id)
    }

    fun bookmarksLive(): LiveData<List<Bookmark>> = bookmarks ?: MutableLiveData(emptyList())

    // ── progress ─────────────────────────────────────────────────────────

    fun saveProgress(page: Int, total: Int) {
        val b = book ?: return
        val t = if (total > 0) total else b.totalPages
        val progress = if (t > 1) (page + 1).toFloat() / t else if (t == 1) 1f else 0f
        viewModelScope.launch {
            bookDao.updateReadingProgress(b.id, page, System.currentTimeMillis(), progress.coerceIn(0f, 1f))
            if (t > 0 && t != b.totalPages) {
                bookDao.updateTotalPages(b.id, t)
                book = b.copy(totalPages = t)
            }
        }
    }

    // ── bookmarks ────────────────────────────────────────────────────────

    fun refreshBookmarkState(page: Int) {
        val b = book ?: return
        viewModelScope.launch { isCurrentPageBookmarked.postValue(bookmarkDao.isPageBookmarked(b.id, page) > 0) }
    }

    fun toggleBookmark(page: Int, title: String) {
        val b = book ?: return
        viewModelScope.launch {
            if (bookmarkDao.isPageBookmarked(b.id, page) > 0) {
                bookmarkDao.deleteBookmarkAtPage(b.id, page)
                isCurrentPageBookmarked.postValue(false)
            } else {
                bookmarkDao.insertBookmark(Bookmark(bookId = b.id, pageNumber = page, title = title))
                isCurrentPageBookmarked.postValue(true)
            }
        }
    }

    fun addBookmark(page: Int, title: String, note: String) {
        val b = book ?: return
        viewModelScope.launch {
            val existing = bookmarkDao.getBookmarkAtPage(b.id, page)
            if (existing != null) bookmarkDao.updateBookmark(existing.copy(title = title, note = note))
            else bookmarkDao.insertBookmark(Bookmark(bookId = b.id, pageNumber = page, title = title, note = note))
            isCurrentPageBookmarked.postValue(true)
        }
    }

    fun deleteBookmark(bm: Bookmark, currentPage: Int) {
        viewModelScope.launch {
            bookmarkDao.deleteBookmark(bm)
            if (bm.pageNumber == currentPage) isCurrentPageBookmarked.postValue(false)
        }
    }

    // ── reading session (onResume → onPause) ─────────────────────────────

    fun beginSession(page: Int) {
        val b = book ?: return
        if (sessionId != -1L) return
        sessionStart = System.currentTimeMillis()
        sessionStartPage = page
        sessionMaxPagesTurned = 0
        sessionId = -2L  // insert চলছে
        viewModelScope.launch {
            sessionId = sessionDao.insertSession(ReadingSession(bookId = b.id, startTime = sessionStart))
        }
    }

    fun noteTurn() { sessionMaxPagesTurned++ }

    fun endSession() {
        val b = book ?: return
        val id = sessionId
        if (id < 0) { sessionId = -1L; return }
        sessionId = -1L
        val start = sessionStart
        val pages = sessionMaxPagesTurned
        viewModelScope.launch {
            sessionDao.updateSession(
                ReadingSession(id = id, bookId = b.id, startTime = start, endTime = System.currentTimeMillis(), pagesRead = pages, date = start)
            )
        }
    }
}
