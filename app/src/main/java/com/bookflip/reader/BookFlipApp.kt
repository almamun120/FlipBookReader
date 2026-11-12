// BookFlipApp.kt
package com.bookflip.reader

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.bookflip.reader.database.BookDatabase
import com.bookflip.reader.utils.PreferenceManager

class BookFlipApp : Application() {

    val database: BookDatabase by lazy { BookDatabase.getDatabase(this) }

    override fun onCreate() {
        super.onCreate()

        // Init shared prefs helper
        PreferenceManager.init(this)

        // Apply saved theme
        when (PreferenceManager.themeMode) {
            1    -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        }
    }
}