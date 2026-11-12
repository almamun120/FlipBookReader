package com.bookflip.reader

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.bookflip.reader.adapters.BookAdapter
import com.bookflip.reader.databinding.ActivityHomeBinding
import com.bookflip.reader.databinding.BottomSheetBookOptionsBinding
import com.bookflip.reader.models.Book
import com.bookflip.reader.models.FileType
import com.bookflip.reader.utils.BookImporter
import com.bookflip.reader.utils.PreferenceManager
import com.bookflip.reader.viewmodels.HomeViewModel
import com.bookflip.reader.viewmodels.SortOrder
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.BaseTransientBottomBar
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import java.io.File

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private val viewModel: HomeViewModel by viewModels()
    private lateinit var adapter: BookAdapter
    private var isGrid = true
    private var lastNavId = R.id.nav_all

    private val pickBooks = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        importUris(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupList()
        setupSearch()
        setupNav()
        observe()

        binding.fabAddBook.setOnClickListener { pickBooks.launch(arrayOf("*/*")) }
    }

    // ── Setup ────────────────────────────────────────────────────────────

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.home_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_toggle_view -> {
            isGrid = !isGrid
            item.setIcon(if (isGrid) R.drawable.ic_list_view else R.drawable.ic_grid_view)
            applyLayoutManager()
            true
        }
        R.id.action_sort -> { showSortDialog(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun setupList() {
        adapter = BookAdapter(
            onClick = { openBook(it) },
            onLongClick = { showBookOptions(it) },
            onFavorite = { viewModel.toggleFavorite(it) }
        )
        binding.rvBooks.adapter = adapter
        binding.rvBooks.itemAnimator = null
        applyLayoutManager()
    }

    private fun applyLayoutManager() {
        adapter.isGrid = isGrid
        val dpWidth = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        val span = (dpWidth / 120f).toInt().coerceIn(2, 6)
        binding.rvBooks.layoutManager = if (isGrid) GridLayoutManager(this, span) else LinearLayoutManager(this)
    }

    private fun setupSearch() {
        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(q: String?) = true
            override fun onQueryTextChange(q: String?): Boolean {
                viewModel.query.value = q.orEmpty()
                return true
            }
        })
        binding.searchView.clearFocus()
    }

    private fun setupNav() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_all -> { viewModel.tab.value = 0; lastNavId = item.itemId; true }
                R.id.nav_recent -> { viewModel.tab.value = 1; lastNavId = item.itemId; true }
                R.id.nav_favorites -> { viewModel.tab.value = 2; lastNavId = item.itemId; true }
                R.id.nav_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    binding.bottomNav.post { binding.bottomNav.selectedItemId = lastNavId }
                    false
                }
                else -> false
            }
        }
    }

    private fun observe() {
        viewModel.visibleBooks.observe(this) { books ->
            adapter.submitList(books)
            val empty = books.isEmpty()
            binding.layoutEmpty.visibility = if (empty) View.VISIBLE else View.GONE
            binding.rvBooks.visibility = if (empty) View.INVISIBLE else View.VISIBLE
            val total = viewModel.allBooks.value?.size ?: 0
            if (empty) {
                val filtered = total > 0
                binding.tvEmptyTitle.text = if (filtered) "Nothing here" else "Your shelf is empty"
                binding.tvEmptySub.text = if (filtered) "No books match the current filter."
                else "Tap + to add a PDF or EPUB — or open one from any file manager with “Open with BookFlip”."
            }
        }
        viewModel.allBooks.observe(this) { books ->
            binding.tvBookCount.text = when (books.size) {
                0 -> "No books yet"
                1 -> "1 book in your library"
                else -> "${books.size} books in your library"
            }
        }
        viewModel.categories.observe(this) { cats -> buildChips(cats) }
        viewModel.continueBook.observe(this) { book -> bindContinue(book) }
    }

    private fun buildChips(categories: List<String>) {
        val group = binding.chipGroupCategories
        group.setOnCheckedStateChangeListener(null)
        group.removeAllViews()
        val selected = viewModel.category.value
        fun chip(label: String, value: String?): Chip = Chip(this).apply {
            text = label
            isCheckable = true
            isChecked = value == selected
            setChipBackgroundColorResource(R.color.chip_background)
            setTextColor(getColorStateList(R.color.text_primary))
            chipStrokeWidth = 0f
            tag = value
        }
        group.addView(chip("All", null))
        categories.forEach { group.addView(chip(it, it)) }
        group.setOnCheckedStateChangeListener { g, ids ->
            val chipView = ids.firstOrNull()?.let { g.findViewById<Chip>(it) }
            viewModel.category.value = chipView?.tag as? String
        }
        group.visibility = if (categories.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun bindContinue(book: Book?) {
        if (book == null) { binding.cardContinue.visibility = View.GONE; return }
        binding.cardContinue.visibility = View.VISIBLE
        binding.tvContinueTitle.text = book.title
        val pct = (book.readingProgress * 100).toInt().coerceIn(0, 100)
        binding.progressContinue.progress = pct
        binding.tvContinueSub.text = "$pct% · page ${book.lastReadPage + 1} of ${book.totalPages}"
        val placeholder = if (book.fileType == FileType.PDF) R.drawable.ic_pdf_cover else R.drawable.ic_epub_cover
        if (book.coverImagePath.isNotEmpty() && File(book.coverImagePath).exists()) {
            Glide.with(this).load(File(book.coverImagePath)).placeholder(placeholder).into(binding.ivContinueCover)
        } else binding.ivContinueCover.setImageResource(placeholder)
        binding.cardContinue.setOnClickListener { openBook(book) }
        binding.btnContinue.setOnClickListener { openBook(book) }
    }

    // ── Actions ──────────────────────────────────────────────────────────

    private fun openBook(book: Book) {
        startActivity(Intent(this, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_BOOK_ID, book.id))
    }

    private fun importUris(uris: List<android.net.Uri>) {
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            var added = 0
            var existing = 0
            var firstBook: Book? = null
            val errors = mutableListOf<String>()
            for (uri in uris) {
                when (val r = BookImporter.import(this@HomeActivity, uri)) {
                    is BookImporter.Result.Success -> {
                        if (r.alreadyInLibrary) existing++ else added++
                        if (firstBook == null) firstBook = r.book
                    }
                    is BookImporter.Result.Error -> errors.add(r.message)
                }
            }
            binding.progressBar.visibility = View.GONE
            val msg = buildString {
                if (added > 0) append(if (added == 1) "Added “${firstBook?.title}”" else "$added books added")
                if (existing > 0) { if (isNotEmpty()) append(" · "); append("$existing already in library") }
                if (errors.isNotEmpty()) { if (isNotEmpty()) append(" · "); append(errors.first()) }
            }.ifEmpty { "Nothing imported" }
            val sb = Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).setAnchorView(binding.bottomNav)
            val fb = firstBook
            if (fb != null && uris.size == 1) sb.setAction("Read") { openBook(fb) }
            sb.show()
        }
    }

    private fun showBookOptions(book: Book) {
        val dialog = BottomSheetDialog(this, R.style.BottomSheetTheme)
        val sheet = BottomSheetBookOptionsBinding.inflate(layoutInflater)
        dialog.setContentView(sheet.root)
        sheet.btnOpen.setOnClickListener { dialog.dismiss(); openBook(book) }
        sheet.btnEdit.setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(this, AddBookActivity::class.java).putExtra("book", book).putExtra("isEdit", true))
        }
        sheet.btnShare.setOnClickListener { dialog.dismiss(); shareBook(book) }
        sheet.btnDelete.setOnClickListener { dialog.dismiss(); confirmDelete(book) }
        dialog.show()
    }

    private fun shareBook(book: Book) {
        try {
            val file = File(book.filePath)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val mime = if (book.fileType == FileType.PDF) "application/pdf" else "application/epub+zip"
            startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "Share book"))
        } catch (e: Exception) {
            Snackbar.make(binding.root, "Cannot share this file", Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun confirmDelete(book: Book) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Remove book")
            .setMessage("Remove “${book.title}” from your library?")
            .setPositiveButton("Remove") { _, _ ->
                viewModel.deleteBookRow(book)
                var undone = false
                Snackbar.make(binding.root, "Removed “${book.title}”", Snackbar.LENGTH_LONG)
                    .setAnchorView(binding.bottomNav)
                    .setAction("Undo") { undone = true; viewModel.restoreBook(book) }
                    .addCallback(object : BaseTransientBottomBar.BaseCallback<Snackbar>() {
                        override fun onDismissed(sb: Snackbar?, event: Int) {
                            if (!undone) viewModel.purgeBook(book)   // Undo-র সুযোগ শেষ → ফাইল মুছি
                        }
                    })
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSortDialog() {
        val options = arrayOf("Recently added", "Last read", "Title (A–Z)", "Author", "Progress")
        val values = SortOrder.values()
        val current = values.indexOf(viewModel.sortOrder.value ?: SortOrder.RECENTLY_ADDED)
        MaterialAlertDialogBuilder(this)
            .setTitle("Sort by")
            .setSingleChoiceItems(options, current) { d, which ->
                viewModel.sortOrder.value = values[which]
                d.dismiss()
            }
            .show()
    }
}
