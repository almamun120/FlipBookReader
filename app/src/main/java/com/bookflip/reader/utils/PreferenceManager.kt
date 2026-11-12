// utils/PreferenceManager.kt
package com.bookflip.reader.utils

import android.content.Context
import android.content.SharedPreferences

object PreferenceManager {

    private const val PREF_NAME = "bookflip_prefs"

    // Keys
    private const val KEY_THEME              = "theme_mode"
    private const val KEY_FONT_SIZE          = "font_size"
    private const val KEY_BRIGHTNESS         = "brightness"
    private const val KEY_PAGE_FLIP_SOUND    = "page_flip_sound"
    private const val KEY_AUTO_HIDE          = "auto_hide_scrollbar"
    private const val KEY_READING_MODE       = "reading_mode"
    private const val KEY_LAST_BOOK          = "last_opened_book"
    private const val KEY_KEEP_SCREEN        = "keep_screen_on"
    private const val KEY_REMEMBER_PAGE      = "remember_last_page"
    private const val KEY_LINE_HEIGHT        = "line_height"
    private const val KEY_MARGIN             = "margin_size"
    private const val KEY_FONT_FAMILY        = "font_family"
    private const val KEY_SCROLL_DIRECTION   = "scroll_direction"   // HORIZONTAL / VERTICAL
    private const val KEY_TWO_PAGE_MODE      = "two_page_mode"
    private const val KEY_FIRST_LAUNCH       = "first_launch"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    // ── Appearance ──────────────────────────────────────────────────────────

    /** 0 = Light, 1 = Dark, 2 = Sepia */
    var themeMode: Int
        get()      = prefs.getInt(KEY_THEME, 0)
        set(value) = prefs.edit().putInt(KEY_THEME, value).apply()

    var fontSize: Float
        get()      = prefs.getFloat(KEY_FONT_SIZE, 16f)
        set(value) = prefs.edit().putFloat(KEY_FONT_SIZE, value).apply()

    var lineHeight: Float
        get()      = prefs.getFloat(KEY_LINE_HEIGHT, 1.8f)
        set(value) = prefs.edit().putFloat(KEY_LINE_HEIGHT, value).apply()

    var marginSize: Int
        get()      = prefs.getInt(KEY_MARGIN, 20)
        set(value) = prefs.edit().putInt(KEY_MARGIN, value).apply()

    /** "Georgia", "Lato", "OpenDyslexic", etc. */
    var fontFamily: String
        get()      = prefs.getString(KEY_FONT_FAMILY, "Georgia") ?: "Georgia"
        set(value) = prefs.edit().putString(KEY_FONT_FAMILY, value).apply()

    // ── Reader Behaviour ────────────────────────────────────────────────────

    var brightness: Float
        get()      = prefs.getFloat(KEY_BRIGHTNESS, -1f)
        set(value) = prefs.edit().putFloat(KEY_BRIGHTNESS, value).apply()

    var isPageFlipSoundEnabled: Boolean
        get()      = prefs.getBoolean(KEY_PAGE_FLIP_SOUND, true)
        set(value) = prefs.edit().putBoolean(KEY_PAGE_FLIP_SOUND, value).apply()

    var isAutoHideScrollbar: Boolean
        get()      = prefs.getBoolean(KEY_AUTO_HIDE, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_HIDE, value).apply()

    var keepScreenOn: Boolean
        get()      = prefs.getBoolean(KEY_KEEP_SCREEN, true)
        set(value) = prefs.edit().putBoolean(KEY_KEEP_SCREEN, value).apply()

    var rememberLastPage: Boolean
        get()      = prefs.getBoolean(KEY_REMEMBER_PAGE, true)
        set(value) = prefs.edit().putBoolean(KEY_REMEMBER_PAGE, value).apply()

    /** "FLIP" or "SCROLL" */
    var readingMode: String
        get()      = prefs.getString(KEY_READING_MODE, "FLIP") ?: "FLIP"
        set(value) = prefs.edit().putString(KEY_READING_MODE, value).apply()

    /** "HORIZONTAL" or "VERTICAL" */
    var scrollDirection: String
        get()      = prefs.getString(KEY_SCROLL_DIRECTION, "HORIZONTAL") ?: "HORIZONTAL"
        set(value) = prefs.edit().putString(KEY_SCROLL_DIRECTION, value).apply()

    var isTwoPageMode: Boolean
        get()      = prefs.getBoolean(KEY_TWO_PAGE_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_TWO_PAGE_MODE, value).apply()

    // ── Navigation ──────────────────────────────────────────────────────────

    var lastOpenedBookId: Long
        get()      = prefs.getLong(KEY_LAST_BOOK, -1L)
        set(value) = prefs.edit().putLong(KEY_LAST_BOOK, value).apply()

    var isFirstLaunch: Boolean
        get()      = prefs.getBoolean(KEY_FIRST_LAUNCH, true)
        set(value) = prefs.edit().putBoolean(KEY_FIRST_LAUNCH, value).apply()

    // ── Helpers ─────────────────────────────────────────────────────────────

    /** রিডারের নাইট মোড (অ্যাপ থিম থেকে আলাদা) */
    var readerNightMode: Boolean
        get()      = prefs.getBoolean("reader_night_mode", false)
        set(value) = prefs.edit().putBoolean("reader_night_mode", value).apply()

    fun clearAll() = prefs.edit().clear().apply()
}