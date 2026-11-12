// SettingsActivity.kt
package com.bookflip.reader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.bookflip.reader.database.BookDatabase
import com.bookflip.reader.databinding.ActivitySettingsBinding
import com.bookflip.reader.utils.PreferenceManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val database by lazy { BookDatabase.getDatabase(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Settings"

        setupThemeSection()
        setupReadingSection()
        setupStorageSection()
        setupAboutSection()
    }

    // ─── Theme ────────────────────────────────────────────────────────────────

    private fun setupThemeSection() {
        // Set current selection
        val radioId = when (PreferenceManager.themeMode) {
            1    -> R.id.rbDark
            2    -> R.id.rbSepia
            else -> R.id.rbLight
        }
        binding.rgTheme.check(radioId)

        binding.rgTheme.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.rbLight -> {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                    PreferenceManager.themeMode = 0
                }
                R.id.rbDark -> {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                    PreferenceManager.themeMode = 1
                }
                R.id.rbSepia -> {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                    PreferenceManager.themeMode = 2
                }
            }
        }
    }

    // ─── Reading ──────────────────────────────────────────────────────────────

    private fun setupReadingSection() {
        with(binding) {
            switchPageSound.isChecked = PreferenceManager.isPageFlipSoundEnabled
            switchPageSound.setOnCheckedChangeListener { _, checked ->
                PreferenceManager.isPageFlipSoundEnabled = checked
            }

            switchAutoHideScrollbar.isChecked = PreferenceManager.isAutoHideScrollbar
            switchAutoHideScrollbar.setOnCheckedChangeListener { _, checked ->
                PreferenceManager.isAutoHideScrollbar = checked
            }

            switchKeepScreenOn.isChecked = PreferenceManager.keepScreenOn
            switchKeepScreenOn.setOnCheckedChangeListener { _, checked ->
                PreferenceManager.keepScreenOn = checked
            }

            switchRememberPage.isChecked = PreferenceManager.rememberLastPage
            switchRememberPage.setOnCheckedChangeListener { _, checked ->
                PreferenceManager.rememberLastPage = checked
            }

            // Two-page mode visibility based on orientation
            val isLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            layoutTwoPageMode.visibility = if (isLandscape) View.VISIBLE else View.GONE
            
            switchTwoPageMode.isChecked = PreferenceManager.isTwoPageMode
            switchTwoPageMode.setOnCheckedChangeListener { _, checked ->
                PreferenceManager.isTwoPageMode = checked
            }
        }
    }

    // ─── Storage ──────────────────────────────────────────────────────────────

    private fun setupStorageSection() {
        updateCacheSize()

        binding.btnClearCache.setOnClickListener {
            showClearCacheDialog()
        }

        binding.btnExportData.setOnClickListener {
            exportData()
        }

        binding.btnClearAllData.setOnClickListener {
            showClearAllDataDialog()
        }
    }

    private fun updateCacheSize() {
        lifecycleScope.launch(Dispatchers.IO) {
            val cacheDir  = cacheDir
            val cacheSize = getFolderSize(cacheDir)
            val sizeText  = formatSize(cacheSize)
            withContext(Dispatchers.Main) {
                binding.tvCacheSize.text = sizeText
            }
        }
    }

    private fun getFolderSize(folder: File): Long {
        if (!folder.exists()) return 0L
        return folder.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024       -> "$bytes B"
        bytes < 1024*1024  -> "${bytes/1024} KB"
        else               -> "${"%.1f".format(bytes/1024f/1024f)} MB"
    }

    private fun showClearCacheDialog() {
        AlertDialog.Builder(this)
            .setTitle("Clear Cache")
            .setMessage("This will clear temporary files. Your books and bookmarks will not be affected.")
            .setPositiveButton("Clear") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    cacheDir.deleteRecursively()
                    withContext(Dispatchers.Main) {
                        updateCacheSize()
                        Toast.makeText(this@SettingsActivity, "Cache cleared", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun exportData() {
        Toast.makeText(this, "Export feature coming soon", Toast.LENGTH_SHORT).show()
    }

    private fun showClearAllDataDialog() {
        AlertDialog.Builder(this)
            .setTitle("⚠ Clear All Data")
            .setMessage(
                "This will delete ALL books, bookmarks, highlights and reading history. " +
                        "This action cannot be undone!"
            )
            .setPositiveButton("Delete Everything") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    database.clearAllTables()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@SettingsActivity,
                            "All data cleared",
                            Toast.LENGTH_SHORT
                        ).show()
                        finishAffinity()
                        startActivity(Intent(this@SettingsActivity, SplashActivity::class.java))
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ─── About ────────────────────────────────────────────────────────────────

    private fun setupAboutSection() {
        binding.tvVersion.text = "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

        binding.btnRateApp.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.facebook.com/technicalmamun90"))
            startActivity(intent)
        }

        binding.btnSendFeedback.setOnClickListener {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data    = Uri.parse("mailto:mamun995599@gmail.com")
                putExtra(Intent.EXTRA_SUBJECT, "BookFlip Feedback")
            }
            startActivity(Intent.createChooser(intent, "Send Feedback"))
        }

        binding.btnStats.setOnClickListener {
            startActivity(Intent(this, StatsActivity::class.java))
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) finish()
        return super.onOptionsItemSelected(item)
    }
}