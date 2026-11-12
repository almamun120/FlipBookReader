package com.bookflip.reader.utils

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

object FileUtils {

    fun getFileNameFromUri(context: Context, uri: Uri): String {
        var fileName: String? = null
        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (i >= 0) fileName = c.getString(i)
                    }
                }
            } catch (_: Exception) {}
        }
        return fileName ?: uri.lastPathSegment?.substringAfterLast('/') ?: "book"
    }

    /** ফাইলের নাম/মাইম/হেডার দেখে "PDF" | "EPUB" | "UNKNOWN" */
    fun detectType(context: Context, uri: Uri, fileName: String): String {
        val n = fileName.lowercase()
        if (n.endsWith(".pdf")) return "PDF"
        if (n.endsWith(".epub")) return "EPUB"
        val mime = try { context.contentResolver.getType(uri) } catch (_: Exception) { null }
        if (mime == "application/pdf") return "PDF"
        if (mime == "application/epub+zip") return "EPUB"
        // হেডার স্নিফ
        return try {
            context.contentResolver.openInputStream(uri)?.use { ins ->
                val b = ByteArray(1024)
                val len = ins.read(b)
                val s = String(b, 0, maxOf(len, 0), Charsets.ISO_8859_1)
                when {
                    s.startsWith("%PDF") -> "PDF"
                    s.startsWith("PK") && s.contains("application/epub+zip") -> "EPUB"
                    else -> "UNKNOWN"
                }
            } ?: "UNKNOWN"
        } catch (e: Exception) { "UNKNOWN" }
    }

    fun getFileType(fileName: String): String = when {
        fileName.lowercase().endsWith(".pdf") -> "PDF"
        fileName.lowercase().endsWith(".epub") -> "EPUB"
        else -> "UNKNOWN"
    }

    /**
     * Uri → app storage; ফাইলের SHA-1 হ্যাশই নাম। একই বই দ্বিতীয়বার আনলে ডুপ্লিকেট হয় না,
     * আবার একই নামের ভিন্ন বই একটা আরেকটাকে ওভাররাইটও করে না (আগে এই বাগ ছিল)।
     * @return Pair(absolutePath, alreadyExisted)
     */
    fun copyToBooksDir(context: Context, uri: Uri, ext: String): Pair<String, Boolean>? {
        val booksDir = File(context.filesDir, "books").apply { mkdirs() }
        val tmp = File(context.cacheDir, "import_${System.nanoTime()}.tmp")
        return try {
            val md = MessageDigest.getInstance("SHA-1")
            val input = context.contentResolver.openInputStream(uri) ?: return null
            input.use { ins ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        md.update(buf, 0, n)
                        out.write(buf, 0, n)
                    }
                }
            }
            val hash = md.digest().joinToString("") { "%02x".format(it) }
            val dest = File(booksDir, "$hash.${ext.lowercase()}")
            if (dest.exists() && dest.length() == tmp.length()) {
                tmp.delete()
                Pair(dest.absolutePath, true)
            } else {
                if (!tmp.renameTo(dest)) { tmp.copyTo(dest, overwrite = true); tmp.delete() }
                Pair(dest.absolutePath, false)
            }
        } catch (e: Exception) {
            tmp.delete()
            null
        }
    }

    fun copyFileToAppStorage(context: Context, uri: Uri, fileName: String): String? {
        return try {
            val dir = File(context.filesDir, "covers").apply { mkdirs() }
            val dest = File(dir, fileName)
            context.contentResolver.openInputStream(uri)?.use { ins ->
                FileOutputStream(dest).use { out -> ins.copyTo(out) }
            } ?: return null
            dest.absolutePath
        } catch (e: Exception) { null }
    }

    fun deleteBookFiles(filePath: String, coverPath: String) {
        try { File(filePath).delete() } catch (_: Exception) {}
        if (coverPath.isNotEmpty() && coverPath.contains("/covers/")) {
            try { File(coverPath).delete() } catch (_: Exception) {}
        }
    }

    fun formatFileSize(size: Long): String = when {
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> "${size / 1024} KB"
        else -> "${"%.1f".format(size / (1024f * 1024f))} MB"
    }
}
