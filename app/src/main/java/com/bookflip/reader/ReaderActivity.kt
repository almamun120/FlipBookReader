package com.bookflip.reader

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.bookflip.reader.adapters.BookmarkAdapter
import com.bookflip.reader.databinding.ActivityReaderBinding
import com.bookflip.reader.databinding.BottomSheetBookmarksBinding
import com.bookflip.reader.databinding.BottomSheetReaderSettingsBinding
import com.bookflip.reader.models.Book
import com.bookflip.reader.models.FileType
import com.bookflip.reader.utils.EpubBook
import com.bookflip.reader.utils.PdfPageProvider
import com.bookflip.reader.utils.PreferenceManager
import com.bookflip.reader.utils.SoundManager
import com.bookflip.reader.viewmodels.ReaderViewModel
import com.bookflip.reader.views.PageCurlView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.math.abs

class ReaderActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_BOOK_ID = "bookId"
        private const val HOST = "epub.local"
    }

    private lateinit var binding: ActivityReaderBinding
    private val viewModel: ReaderViewModel by viewModels()

    private var book: Book? = null
    private var pdf: PdfPageProvider? = null
    private var epub: EpubBook? = null
    private var sound: SoundManager? = null

    private var currentPage = 0           // PDF: পাতা; EPUB: অধ্যায়
    private var totalPages = 0
    private var uiVisible = true
    private var lastEndToast = 0L
    private var settingsApplied = false
    private val hideRunnable = Runnable { if (uiVisible) setUiVisible(false) }

    // ═════════════════════════════════════════════════════════════════════
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        applyInsets()
        applyScreenPrefs()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { exitReader() }
        })

        binding.btnBack.setOnClickListener { exitReader() }
        binding.btnMenu.setOnClickListener { showMenu(it) }
        binding.btnBookmark.setOnClickListener { toggleBookmark() }
        binding.btnBookmark.setOnLongClickListener { showAddBookmarkDialog(); true }
        binding.btnReaderSettings.setOnClickListener { showReaderSettings() }
        binding.btnToc.setOnClickListener { showToc() }
        binding.btnPrevChapter.setOnClickListener { goToChapter(currentPage - 1) }
        binding.btnNextChapter.setOnClickListener { goToChapter(currentPage + 1) }

        binding.seekbarPages.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) updatePageInfo(progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { handler().removeCallbacks(hideRunnable) }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                val target = sb?.progress ?: return
                if (book?.fileType == FileType.PDF) binding.pageCurl.goToPage(target) else goToChapter(target)
                scheduleHide()
            }
        })

        viewModel.isCurrentPageBookmarked.observe(this) { marked ->
            binding.btnBookmark.setImageResource(if (marked) R.drawable.ic_bookmark_filled else R.drawable.ic_bookmark_outline)
            binding.btnBookmark.setColorFilter(if (marked) 0xFFE0A030.toInt() else 0xFFFFFFFF.toInt())
        }

        loadBook()
    }

    private fun handler() = binding.root.handler ?: android.os.Handler(mainLooper)

    // ── Insets / immersive ───────────────────────────────────────────────

    private fun applyInsets() {
        val density = resources.displayMetrics.density
        ViewCompat.setOnApplyWindowInsetsListener(binding.readerRoot) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            binding.topBar.updateLayoutParams<android.widget.FrameLayout.LayoutParams> { topMargin = bars.top + (12 * density).toInt() }
            binding.bottomBar.updateLayoutParams<android.widget.FrameLayout.LayoutParams> { bottomMargin = bars.bottom + (12 * density).toInt() }
            insets
        }
    }

    private fun applyScreenPrefs() {
        if (PreferenceManager.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val b = PreferenceManager.brightness
        if (b in 0f..1f) window.attributes = window.attributes.also { it.screenBrightness = b.coerceAtLeast(0.02f) }
    }

    private fun setUiVisible(visible: Boolean) {
        uiVisible = visible
        val ctrl = WindowInsetsControllerCompat(window, binding.readerRoot)
        ctrl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (visible) ctrl.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        else ctrl.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        for (v in listOf(binding.topBar, binding.bottomBar)) {
            v.animate().cancel()
            if (visible) {
                v.visibility = View.VISIBLE
                v.animate().alpha(1f).setDuration(180).start()
            } else {
                v.animate().alpha(0f).setDuration(180).withEndAction { v.visibility = View.GONE }.start()
            }
        }
        if (visible) scheduleHide()
    }

    private fun scheduleHide() {
        handler().removeCallbacks(hideRunnable)
        handler().postDelayed(hideRunnable, 3500)
    }

    private fun toggleUi() = setUiVisible(!uiVisible)

    private fun exitReader() {
        if (isTaskRoot) startActivity(Intent(this, HomeActivity::class.java))
        finish()
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Loading
    // ═════════════════════════════════════════════════════════════════════

    @Suppress("DEPRECATION")
    private fun loadBook() {
        lifecycleScope.launch {
            val dao = (application as BookFlipApp).database.bookDao()
            val id = intent.getLongExtra(EXTRA_BOOK_ID, -1L)
            val b: Book? = if (id >= 0) dao.getBookById(id) else intent.getParcelableExtra<Book>("book")?.let { dao.getBookById(it.id) ?: it }
            if (b == null) { fail("Book not found"); return@launch }
            val file = File(b.filePath)
            if (!file.exists()) { fail("The book file is missing. Please add it again."); return@launch }

            book = b
            viewModel.init(b)
            binding.tvBookTitle.text = b.title
            PreferenceManager.lastOpenedBookId = b.id
            sound = try { SoundManager(this@ReaderActivity).also { it.setEnabled(PreferenceManager.isPageFlipSoundEnabled) } } catch (e: Exception) { null }

            val start = if (PreferenceManager.rememberLastPage) b.lastReadPage else 0
            if (b.fileType == FileType.PDF) openPdf(file, start) else openEpub(file, start)
        }
    }

    private fun fail(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        exitReader()
    }

    private suspend fun openPdf(file: File, start: Int) {
        val provider = withContext(Dispatchers.IO) { PdfPageProvider.open(file) }
        if (provider == null) { fail("Cannot open this PDF (password-protected or damaged)."); return }
        pdf = provider
        totalPages = provider.pageCount

        val curl = binding.pageCurl
        curl.visibility = View.VISIBLE
        curl.twoPageMode = wantTwoPage()
        curl.nightMode = PreferenceManager.readerNightMode
        curl.deskColor = if (PreferenceManager.readerNightMode) 0xFF0B0907.toInt() else 0xFF2B2119.toInt()
        curl.listener = object : PageCurlView.Listener {
            override fun onPageChanged(page: Int, pageCount: Int) {
                currentPage = page
                binding.seekbarPages.max = (pageCount - 1).coerceAtLeast(0)
                binding.seekbarPages.progress = page
                updatePageInfo(page)
                viewModel.saveProgress(page, pageCount)
                viewModel.refreshBookmarkState(page)
            }
            override fun onSingleTap() = toggleUi()
            override fun onTurnCommitted(forward: Boolean) {
                viewModel.noteTurn()
                sound?.playPageFlip(forward)
            }
            override fun onReachedEnd() {
                val now = System.currentTimeMillis()
                if (now - lastEndToast > 1500) {
                    lastEndToast = now
                    Toast.makeText(this@ReaderActivity, "You've reached the last page", Toast.LENGTH_SHORT).show()
                }
            }
        }
        curl.setPdf(provider, start)
        binding.layoutLoading.visibility = View.GONE
        binding.btnToc.visibility = View.GONE
        binding.btnPrevChapter.visibility = View.GONE
        binding.btnNextChapter.visibility = View.GONE
        setUiVisible(true)
    }

    private fun wantTwoPage(): Boolean =
        PreferenceManager.isTwoPageMode && resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (book?.fileType == FileType.PDF) binding.pageCurl.twoPageMode = wantTwoPage()
    }

    // ── EPUB ─────────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private suspend fun openEpub(file: File, start: Int) {
        val e = withContext(Dispatchers.IO) { EpubBook.open(file) }
        if (e == null) { fail("Cannot open this EPUB."); return }
        epub = e
        totalPages = e.chapterCount
        currentPage = start.coerceIn(0, (totalPages - 1).coerceAtLeast(0))

        val web = binding.epubWeb
        web.visibility = View.VISIBLE
        web.setBackgroundColor(if (PreferenceManager.readerNightMode) 0xFF1A1612.toInt() else 0xFFFBF3E0.toInt())
        web.settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            domStorageEnabled = false
            builtInZoomControls = false
            setSupportZoom(false)
            cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url
                if (url.host != HOST) {
                    // নেটওয়ার্ক বন্ধ — EPUB-এর ভিতরের স্ক্রিপ্ট বাইরে যোগাযোগ করতে পারবে না
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                val path = Uri.decode(url.path.orEmpty()).removePrefix("/")
                val book = epub ?: return null
                val stream = book.open(path)
                    ?: return WebResourceResponse("text/plain", "utf-8", 404, "Not found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                return WebResourceResponse(book.mimeOf(path), "utf-8", stream)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (url.host == HOST) {
                    val idx = epub?.spineIndexOf(Uri.decode(url.path.orEmpty()).removePrefix("/")) ?: -1
                    if (idx >= 0) { currentPage = idx; onChapterShown() }
                    return false
                }
                return true  // বাইরের লিংক ব্লক
            }

            override fun onPageFinished(view: WebView, url: String?) {
                injectStyle()
            }
        }

        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(ev: MotionEvent): Boolean { toggleUi(); return false }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (abs(vx) > 1400 && abs(dx) > abs(dy) * 2.2f && abs(dx) > 160) {
                    if (dx < 0) goToChapter(currentPage + 1) else goToChapter(currentPage - 1)
                    return true
                }
                return false
            }
        })
        web.setOnTouchListener { _, ev -> gestures.onTouchEvent(ev); false }

        binding.btnToc.visibility = if (e.toc.isNotEmpty()) View.VISIBLE else View.GONE
        binding.btnPrevChapter.visibility = View.VISIBLE
        binding.btnNextChapter.visibility = View.VISIBLE
        binding.seekbarPages.max = (totalPages - 1).coerceAtLeast(0)

        loadChapter(currentPage)
        binding.layoutLoading.visibility = View.GONE
        setUiVisible(true)
    }

    private fun chapterUrl(index: Int): String? {
        val path = epub?.chapterPath(index) ?: return null
        val encoded = path.split('/').joinToString("/") { Uri.encode(it) }
        return "https://$HOST/$encoded"
    }

    private fun loadChapter(index: Int) {
        val url = chapterUrl(index) ?: return
        currentPage = index
        binding.epubWeb.loadUrl(url)
        onChapterShown()
    }

    private fun goToChapter(index: Int) {
        if (index < 0 || index >= totalPages) {
            if (index >= totalPages) Toast.makeText(this, "End of book", Toast.LENGTH_SHORT).show()
            return
        }
        loadChapter(index)
        sound?.playPageFlip(index >= currentPage)
        viewModel.noteTurn()
    }

    private fun onChapterShown() {
        binding.seekbarPages.progress = currentPage
        updatePageInfo(currentPage)
        viewModel.saveProgress(currentPage, totalPages)
        viewModel.refreshBookmarkState(currentPage)
    }

    private fun injectStyle() {
        val night = PreferenceManager.readerNightMode
        val bg = if (night) "#1A1612" else "#FBF3E0"
        val fg = if (night) "#E8DCC8" else "#2A2118"
        val link = if (night) "#E0A878" else "#8A4B2D"
        val fs = PreferenceManager.fontSize
        val lh = PreferenceManager.lineHeight
        val margin = PreferenceManager.marginSize
        val css = """
            html{background:$bg !important;}
            body{background:$bg !important;color:$fg !important;font-size:${fs}px !important;line-height:$lh !important;
                 padding:${margin}px ${margin}px 90px ${margin}px !important;margin:0 auto !important;max-width:46em;
                 font-family:Georgia,'Noto Serif',serif;-webkit-text-size-adjust:none;word-wrap:break-word;}
            ${if (night) "body *{color:$fg !important;background-color:transparent !important;}" else ""}
            a{color:$link !important;}
            img,svg,video{max-width:100% !important;height:auto !important;${if (night) "opacity:.88;" else ""}}
            p{margin:0 0 .9em 0;text-align:justify;}
            h1,h2,h3{line-height:1.3 !important;}
        """.trimIndent()
        val js = "(function(){var m=document.querySelector('meta[name=viewport]');" +
            "if(!m){m=document.createElement('meta');m.name='viewport';document.head.appendChild(m);}" +
            "m.content='width=device-width, initial-scale=1';" +
            "var s=document.getElementById('bf-style');if(!s){s=document.createElement('style');s.id='bf-style';document.head.appendChild(s);}" +
            "s.textContent=" + JSONObject.quote(css) + ";})();"
        binding.epubWeb.evaluateJavascript(js, null)
    }

    private fun showToc() {
        val e = epub ?: return
        val titles = e.toc.map { it.title }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Contents")
            .setItems(titles) { _, which -> goToChapter(e.toc[which].spineIndex) }
            .show()
    }

    // ═════════════════════════════════════════════════════════════════════
    //  UI helpers / menu
    // ═════════════════════════════════════════════════════════════════════

    private fun updatePageInfo(page: Int) {
        val b = book ?: return
        binding.tvPageInfo.text = if (b.fileType == FileType.PDF) {
            if (binding.pageCurl.twoPageMode && page > 0 && page + 1 < totalPages) "Pages ${page + 1}–${page + 2} of $totalPages"
            else "Page ${page + 1} of $totalPages"
        } else {
            val title = epub?.toc?.lastOrNull { it.spineIndex <= page }?.title
            "Chapter ${page + 1} / $totalPages" + (title?.let { " · $it" } ?: "")
        }
    }

    private fun showMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.reader_menu, popup.menu)
        popup.menu.findItem(R.id.action_search)?.isVisible = book?.fileType == FileType.EPUB
        popup.menu.findItem(R.id.action_bookmark)?.isVisible = false
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_search -> { showSearch(); true }
                R.id.action_bookmarks_list -> { showBookmarksSheet(); true }
                R.id.action_goto_page -> { showGoToPage(); true }
                R.id.action_settings -> { showReaderSettings(); true }
                R.id.action_share -> { shareBook(); true }
                else -> false
            }
        }
        popup.show()
    }

    private fun toggleBookmark() {
        viewModel.toggleBookmark(currentPage, bookmarkTitle())
        scheduleHide()
    }

    private fun bookmarkTitle(): String =
        if (book?.fileType == FileType.PDF) "Page ${currentPage + 1}"
        else epub?.toc?.lastOrNull { it.spineIndex <= currentPage }?.title ?: "Chapter ${currentPage + 1}"

    private fun showAddBookmarkDialog() {
        val et = EditText(this).apply { hint = "Note (optional)"; setPadding(56, 32, 56, 16) }
        MaterialAlertDialogBuilder(this)
            .setTitle("Bookmark this page")
            .setView(et)
            .setPositiveButton("Save") { _, _ -> viewModel.addBookmark(currentPage, bookmarkTitle(), et.text.toString().trim()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showBookmarksSheet() {
        val dialog = BottomSheetDialog(this, R.style.BottomSheetTheme)
        val sheet = BottomSheetBookmarksBinding.inflate(layoutInflater)
        dialog.setContentView(sheet.root)
        val adapter = BookmarkAdapter(
            onBookmarkClick = {
                dialog.dismiss()
                if (book?.fileType == FileType.PDF) binding.pageCurl.goToPage(it.pageNumber) else goToChapter(it.pageNumber)
            },
            onDeleteClick = { viewModel.deleteBookmark(it, currentPage) }
        )
        sheet.rvBookmarks.layoutManager = LinearLayoutManager(this)
        sheet.rvBookmarks.adapter = adapter
        val live = viewModel.bookmarksLive()
        val obs = androidx.lifecycle.Observer<List<com.bookflip.reader.models.Bookmark>> { list ->
            adapter.submitList(list)
            sheet.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        }
        live.observe(this, obs)
        dialog.setOnDismissListener { live.removeObserver(obs) }
        dialog.show()
    }

    private fun showGoToPage() {
        val et = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "1 – $totalPages"
            setPadding(56, 32, 56, 16)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(if (book?.fileType == FileType.PDF) "Go to page" else "Go to chapter")
            .setView(et)
            .setPositiveButton("Go") { _, _ ->
                val n = et.text.toString().toIntOrNull()
                if (n == null || n !in 1..totalPages) Toast.makeText(this, "Enter 1 – $totalPages", Toast.LENGTH_SHORT).show()
                else if (book?.fileType == FileType.PDF) binding.pageCurl.goToPage(n - 1) else goToChapter(n - 1)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSearch() {
        val et = EditText(this).apply { hint = "Search in this chapter"; setPadding(56, 32, 56, 16); setSingleLine() }
        MaterialAlertDialogBuilder(this)
            .setTitle("Search")
            .setView(et)
            .setPositiveButton("Find") { _, _ ->
                val q = et.text.toString().trim()
                if (q.isEmpty()) return@setPositiveButton
                val web = binding.epubWeb
                web.setFindListener { _, count, done ->
                    if (done) {
                        val sb = Snackbar.make(binding.readerRoot, if (count == 0) "No matches" else "$count matches", Snackbar.LENGTH_INDEFINITE)
                        if (count > 0) sb.setAction("Next") { web.findNext(true) }
                        sb.addCallback(object : Snackbar.Callback() {
                            override fun onDismissed(t: Snackbar?, event: Int) { web.clearMatches() }
                        })
                        sb.show()
                    }
                }
                web.findAllAsync(q)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun shareBook() {
        val b = book ?: return
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", File(b.filePath))
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = if (b.fileType == FileType.PDF) "application/pdf" else "application/epub+zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Share book"))
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot share this file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showReaderSettings() {
        val dialog = BottomSheetDialog(this, R.style.BottomSheetTheme)
        val sheet = BottomSheetReaderSettingsBinding.inflate(layoutInflater)
        dialog.setContentView(sheet.root)

        val isPdf = book?.fileType == FileType.PDF
        sheet.layoutFontSize.visibility = if (isPdf) View.GONE else View.VISIBLE

        // brightness
        val b = PreferenceManager.brightness
        sheet.seekbarBrightness.progress = if (b in 0f..1f) (b * 100).toInt() else 60
        sheet.seekbarBrightness.setOnSeekBarChangeListener(simpleSeek { p ->
            val v = (p / 100f).coerceIn(0.02f, 1f)
            PreferenceManager.brightness = v
            window.attributes = window.attributes.also { it.screenBrightness = v }
        })

        // font size 12..32 → progress 0..20
        sheet.seekbarFontSize.progress = (PreferenceManager.fontSize - 12f).toInt().coerceIn(0, 20)
        sheet.seekbarFontSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {}
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {
                PreferenceManager.fontSize = 12f + (s?.progress ?: 4)
                if (!isPdf) injectStyle()
            }
        })

        sheet.switchNightMode.isChecked = PreferenceManager.readerNightMode
        sheet.switchNightMode.setOnCheckedChangeListener { _, on ->
            PreferenceManager.readerNightMode = on
            applyNight(on)
        }
        sheet.switchSound.isChecked = PreferenceManager.isPageFlipSoundEnabled
        sheet.switchSound.setOnCheckedChangeListener { _, on ->
            PreferenceManager.isPageFlipSoundEnabled = on
            sound?.setEnabled(on)
        }
        sheet.switchTwoPage.isChecked = PreferenceManager.isTwoPageMode
        sheet.switchTwoPage.isEnabled = isPdf
        sheet.switchTwoPage.setOnCheckedChangeListener { _, on ->
            PreferenceManager.isTwoPageMode = on
            if (isPdf) binding.pageCurl.twoPageMode = wantTwoPage()
            if (on && resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE)
                Toast.makeText(this, "Rotate your phone to see the open-book view", Toast.LENGTH_SHORT).show()
        }
        dialog.show()
    }

    private fun applyNight(on: Boolean) {
        if (book?.fileType == FileType.PDF) {
            binding.pageCurl.nightMode = on
            binding.pageCurl.deskColor = if (on) 0xFF0B0907.toInt() else 0xFF2B2119.toInt()
        } else {
            binding.epubWeb.setBackgroundColor(if (on) 0xFF1A1612.toInt() else 0xFFFBF3E0.toInt())
            injectStyle()
        }
    }

    private fun simpleSeek(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) onChange(p) }
        override fun onStartTrackingTouch(s: SeekBar?) {}
        override fun onStopTrackingTouch(s: SeekBar?) {}
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Lifecycle
    // ═════════════════════════════════════════════════════════════════════

    override fun onResume() {
        super.onResume()
        if (book != null) viewModel.beginSession(currentPage)
    }

    override fun onPause() {
        super.onPause()
        viewModel.endSession()
        book?.let { viewModel.saveProgress(currentPage, totalPages) }
    }

    override fun onDestroy() {
        handler().removeCallbacks(hideRunnable)
        binding.pageCurl.release()
        pdf?.close(); pdf = null
        binding.epubWeb.apply { stopLoading(); loadUrl("about:blank"); removeAllViews(); destroy() }
        epub?.close(); epub = null
        sound?.release(); sound = null
        super.onDestroy()
    }
}
