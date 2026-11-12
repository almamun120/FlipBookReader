// StatsActivity.kt
package com.bookflip.reader

import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bookflip.reader.database.BookDatabase
import com.bookflip.reader.databinding.ActivityStatsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class StatsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStatsBinding
    private val database by lazy { BookDatabase.getDatabase(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStatsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Reading Statistics"

        loadStats()
    }

    private fun loadStats() {
        lifecycleScope.launch {
            val bookDao    = database.bookDao()
            val sessionDao = database.readingSessionDao()

            val allBooks = withContext(Dispatchers.IO) {
                bookDao.getAllBooksSync()
            }

            val totalBooks     = allBooks.size
            val finishedBooks  = allBooks.count { it.readingProgress >= 0.95f }
            val totalPages     = allBooks.sumOf { it.totalPages }
            val pagesRead      = allBooks.sumOf { it.lastReadPage }
            val favoriteCount  = allBooks.count { it.isFavorite }

            // Reading time from sessions (sum of all books)
            var totalReadingMs = 0L
            withContext(Dispatchers.IO) {
                allBooks.forEach { book ->
                    totalReadingMs += sessionDao.getTotalReadingTime(book.id) ?: 0L
                }
            }

            val hours   = TimeUnit.MILLISECONDS.toHours(totalReadingMs)
            val minutes = TimeUnit.MILLISECONDS.toMinutes(totalReadingMs) % 60

            // Most read category
            val categoryMap = allBooks
                .filter { it.category.isNotBlank() }
                .groupBy { it.category }
                .mapValues { it.value.size }
            val topCategory = categoryMap.maxByOrNull { it.value }?.key ?: "—"

            // Average progress
            val avgProgress = if (allBooks.isEmpty()) 0f
            else allBooks.map { it.readingProgress }.average().toFloat()

            withContext(Dispatchers.Main) {
                binding.apply {
                    tvTotalBooks.text      = totalBooks.toString()
                    tvFinishedBooks.text   = finishedBooks.toString()
                    tvTotalPages.text      = totalPages.toString()
                    tvPagesRead.text       = pagesRead.toString()
                    tvFavorites.text       = favoriteCount.toString()
                    tvReadingTime.text     = "${hours}h ${minutes}m"
                    tvTopCategory.text     = topCategory
                    tvAvgProgress.text     = "${(avgProgress * 100).toInt()}%"

                    progressAvg.progress   = (avgProgress * 100).toInt()

                    // Animate numbers
                    animateValue(tvTotalBooks,   0, totalBooks)
                    animateValue(tvFinishedBooks,0, finishedBooks)
                    animateValue(tvPagesRead,    0, pagesRead)
                }
            }
        }
    }

    private fun animateValue(
        view: android.widget.TextView,
        from: Int,
        to: Int,
        duration: Long = 800
    ) {
        val animator = android.animation.ValueAnimator.ofInt(from, to)
        animator.duration = duration
        animator.interpolator = android.view.animation.DecelerateInterpolator()
        animator.addUpdateListener { view.text = it.animatedValue.toString() }
        animator.start()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) finish()
        return super.onOptionsItemSelected(item)
    }
}