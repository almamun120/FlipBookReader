package com.bookflip.reader.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.File

data class BookMeta(
    val title: String = "",
    val author: String = "",
    val totalPages: Int = 0,
    val coverBytes: ByteArray? = null
)

object BookMetadataExtractor {

    fun extract(filePath: String, fileType: String): BookMeta = when (fileType.uppercase()) {
        "EPUB" -> extractEpub(filePath)
        "PDF" -> extractPdf(filePath)
        else -> BookMeta()
    }

    private fun extractEpub(filePath: String): BookMeta {
        val book = EpubBook.open(File(filePath)) ?: return BookMeta()
        return book.use { b ->
            val cover = b.coverBytes()?.let { downscaleJpeg(it) }
            BookMeta(b.title, b.author, b.chapterCount, cover)
        }
    }

    private fun extractPdf(filePath: String): BookMeta {
        return try {
            ParcelFileDescriptor.open(File(filePath), ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { r ->
                    val count = r.pageCount
                    val cover = r.openPage(0).use { page ->
                        val w = 480
                        val h = (w * page.height.toFloat() / page.width).toInt().coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        val m = Matrix().apply { val s = w.toFloat() / page.width; postScale(s, s) }
                        page.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val out = ByteArrayOutputStream()
                        bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)
                        bmp.recycle()
                        out.toByteArray()
                    }
                    BookMeta(totalPages = count, coverBytes = cover)
                }
            }
        } catch (e: Exception) {
            BookMeta()
        }
    }

    /** বড় কভার ছবি ছোট করে JPEG বানায় (লাইব্রেরি গ্রিডে মেমোরি বাঁচাতে) */
    private fun downscaleJpeg(bytes: ByteArray): ByteArray {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            var sample = 1
            while (opts.outWidth / sample > 960) sample *= 2
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return bytes
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
            bmp.recycle()
            out.toByteArray()
        } catch (e: Exception) { bytes }
    }

    fun saveCoverToFile(context: Context, bytes: ByteArray, key: String): String? {
        return try {
            val dir = File(context.filesDir, "covers").also { it.mkdirs() }
            val file = File(dir, "cover_$key.jpg")
            file.writeBytes(bytes)
            file.absolutePath
        } catch (e: Exception) { null }
    }
}
