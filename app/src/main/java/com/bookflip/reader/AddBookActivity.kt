// AddBookActivity.kt
package com.bookflip.reader

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.bookflip.reader.databinding.ActivityAddBookBinding
import com.bookflip.reader.models.Book
import com.bookflip.reader.models.FileType
import com.bookflip.reader.utils.FileUtils
import com.bookflip.reader.viewmodels.HomeViewModel
import com.bumptech.glide.Glide
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AddBookActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAddBookBinding
    private val viewModel: HomeViewModel by viewModels()
    private var existingBook: Book? = null
    private var selectedFilePath: String = ""
    private var selectedFileType: String = ""
    private var selectedCoverPath: String = ""
    private var isEditMode = false

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { loadCoverImage(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddBookBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        loadIntentData()
        setupUI()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    private fun loadIntentData() {
        existingBook = intent.getParcelableExtra("book")
        isEditMode = intent.getBooleanExtra("isEdit", false)

        if (existingBook != null && isEditMode) {
            populateFields(existingBook!!)
            supportActionBar?.title = "Edit Book"
        } else {
            selectedFilePath = intent.getStringExtra("filePath") ?: ""
            selectedFileType = intent.getStringExtra("fileType") ?: ""
            val fileName = intent.getStringExtra("fileName") ?: ""
            binding.etTitle.setText(fileName.removeSuffix(".pdf").removeSuffix(".epub"))
            supportActionBar?.title = "Add Book"
        }
    }

    private fun populateFields(book: Book) {
        binding.apply {
            etTitle.setText(book.title)
            etAuthor.setText(book.author)
            etDescription.setText(book.description)
            etCategory.setText(book.category)
            etTags.setText(book.tags)
            ratingBar.rating = book.rating
            selectedFilePath = book.filePath
            selectedFileType = book.fileType.name
            selectedCoverPath = book.coverImagePath

            if (book.coverImagePath.isNotEmpty()) {
                Glide.with(this@AddBookActivity)
                    .load(book.coverImagePath)
                    .into(ivCover)
            }
        }
    }

    private fun setupUI() {
        binding.ivCover.setOnClickListener {
            imagePickerLauncher.launch("image/*")
        }

        binding.btnChangeCover.setOnClickListener {
            imagePickerLauncher.launch("image/*")
        }

        binding.btnSave.setOnClickListener {
            saveBook()
        }

        binding.btnCancel.setOnClickListener {
            finish()
        }
    }

    private fun loadCoverImage(uri: Uri) {
        CoroutineScope(Dispatchers.Main).launch {
            val path = withContext(Dispatchers.IO) {
                FileUtils.copyFileToAppStorage(
                    this@AddBookActivity, uri,
                    "cover_${System.currentTimeMillis()}.jpg"
                )
            }
            path?.let {
                selectedCoverPath = it
                Glide.with(this@AddBookActivity).load(it).into(binding.ivCover)
            }
        }
    }

    private fun saveBook() {
        val title = binding.etTitle.text.toString().trim()
        if (title.isEmpty()) {
            binding.etTitle.error = "Title is required"
            return
        }

        if (selectedFilePath.isEmpty() && !isEditMode) {
            Toast.makeText(this, "No file selected", Toast.LENGTH_SHORT).show()
            return
        }

        val fileType = when (selectedFileType.uppercase()) {
            "EPUB" -> FileType.EPUB
            else -> FileType.PDF
        }

        val book = if (isEditMode && existingBook != null) {
            existingBook!!.copy(
                title = title,
                author = binding.etAuthor.text.toString().trim(),
                description = binding.etDescription.text.toString().trim(),
                category = binding.etCategory.text.toString().trim(),
                tags = binding.etTags.text.toString().trim(),
                rating = binding.ratingBar.rating,
                coverImagePath = selectedCoverPath
            )
        } else {
            Book(
                title = title,
                author = binding.etAuthor.text.toString().trim(),
                description = binding.etDescription.text.toString().trim(),
                filePath = selectedFilePath,
                fileType = fileType,
                category = binding.etCategory.text.toString().trim(),
                tags = binding.etTags.text.toString().trim(),
                rating = binding.ratingBar.rating,
                coverImagePath = selectedCoverPath
            )
        }

        viewModel.insertBook(book) { bookId ->
            runOnUiThread {
                Toast.makeText(this, "Book saved successfully!", Toast.LENGTH_SHORT).show()
                if (!isEditMode) {
                    val savedBook = book.copy(id = bookId)
                    val intent = Intent(this, ReaderActivity::class.java).apply {
                        putExtra("book", savedBook)
                    }
                    startActivity(intent)
                }
                finish()
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) finish()
        return super.onOptionsItemSelected(item)
    }
}