package com.bookflip.reader.utils

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executors

/**
 * PdfRenderer-এর থ্রেড-সেফ র‍্যাপার। PdfRenderer একসাথে একটাই পেজ খুলতে দেয় ও থ্রেড-সেফ নয়,
 * তাই সব রেন্ডার একটা সিঙ্গেল-থ্রেড এক্সিকিউটরে চলে। মেইন থ্রেড কখনো ব্লক হয় না।
 */
class PdfPageProvider private constructor(
    private val fd: ParcelFileDescriptor,
    private val renderer: PdfRenderer
) : Closeable {

    val pageCount: Int = renderer.pageCount

    /** প্রথম পেজের width/height অনুপাত (পুরো বইয়ের লিফ-সাইজ নির্ধারণে) */
    var aspect: Float = 0.707f
        private set

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "pdf-render").apply { priority = Thread.NORM_PRIORITY - 1 } }
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var closed = false

    init {
        try {
            renderer.openPage(0).use { p -> if (p.height > 0) aspect = p.width.toFloat() / p.height.toFloat() }
        } catch (_: Exception) {}
    }

    /** ব্যাকগ্রাউন্ডে [width]x[height] বিটম্যাপে পেজ আঁকে; ফলাফল মেইন থ্রেডে আসে */
    fun render(pageIndex: Int, width: Int, height: Int, callback: (Bitmap?) -> Unit) {
        if (closed || pageIndex !in 0 until pageCount || width <= 0 || height <= 0) { callback(null); return }
        try {
            executor.execute {
                val bmp = if (closed) null else renderSync(pageIndex, width, height)
                main.post { if (!closed) callback(bmp) else bmp?.recycle() }
            }
        } catch (e: Exception) { callback(null) }
    }

    private fun renderSync(pageIndex: Int, width: Int, height: Int): Bitmap? {
        return try {
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            renderer.openPage(pageIndex).use { page ->
                // পেজ বক্সে ফিট (aspect বজায় রেখে, মাঝখানে)
                val scale = minOf(width.toFloat() / page.width, height.toFloat() / page.height)
                val dx = (width - page.width * scale) / 2f
                val dy = (height - page.height * scale) / 2f
                val m = Matrix().apply { postScale(scale, scale); postTranslate(dx, dy) }
                page.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
            bmp
        } catch (e: OutOfMemoryError) {
            null
        } catch (e: Exception) {
            null
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            executor.execute {
                try { renderer.close() } catch (_: Exception) {}
                try { fd.close() } catch (_: Exception) {}
            }
            executor.shutdown()
        } catch (_: Exception) {
            try { renderer.close() } catch (_: Exception) {}
            try { fd.close() } catch (_: Exception) {}
        }
    }

    companion object {
        /** খুলতে না পারলে (পাসওয়ার্ড/করাপ্ট) null */
        fun open(file: File): PdfPageProvider? {
            var fd: ParcelFileDescriptor? = null
            return try {
                fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                val r = PdfRenderer(fd)
                if (r.pageCount <= 0) { r.close(); fd.close(); null } else PdfPageProvider(fd, r)
            } catch (e: Exception) {
                try { fd?.close() } catch (_: Exception) {}
                null
            }
        }
    }
}
