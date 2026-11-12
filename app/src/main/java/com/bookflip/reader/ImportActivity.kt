package com.bookflip.reader

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bookflip.reader.databinding.ActivityImportBinding
import com.bookflip.reader.utils.BookImporter
import kotlinx.coroutines.launch

/**
 * বাইরের অ্যাপ (ফাইল ম্যানেজার, Chrome, Telegram, WhatsApp, Gmail…) থেকে আসা
 * "Open with" (ACTION_VIEW) ও "Share" (ACTION_SEND) ইন্টেন্ট এখানে ধরা হয়।
 *
 * আগের বাগ: ইন্টেন্ট-ফিল্টার ছিল exported=false ReaderActivity-তে, আর সেটা Intent-এর Uri
 * পড়তই না — তাই বাইরে থেকে PDF/EPUB খোলা যেত না। এখন ফাইল অ্যাপের নিজস্ব স্টোরেজে কপি হয়ে
 * লাইব্রেরিতে যোগ হয় এবং সরাসরি রিডার খোলে।
 */
class ImportActivity : AppCompatActivity() {

    private lateinit var binding: ActivityImportBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImportBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val uris = extractUris(intent)
        if (uris.isEmpty()) {
            Toast.makeText(this, "No file received", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        lifecycleScope.launch {
            var firstId = -1L
            var count = 0
            var error: String? = null
            for (uri in uris) {
                when (val r = BookImporter.import(this@ImportActivity, uri)) {
                    is BookImporter.Result.Success -> { if (firstId < 0) firstId = r.book.id; count++ }
                    is BookImporter.Result.Error -> if (error == null) error = r.message
                }
            }
            when {
                firstId >= 0 && uris.size == 1 -> {
                    startActivity(Intent(this@ImportActivity, ReaderActivity::class.java)
                        .putExtra(ReaderActivity.EXTRA_BOOK_ID, firstId))
                }
                count > 0 -> {
                    Toast.makeText(this@ImportActivity, "$count books added to your library", Toast.LENGTH_LONG).show()
                    startActivity(Intent(this@ImportActivity, HomeActivity::class.java))
                }
                else -> Toast.makeText(this@ImportActivity, error ?: "Could not open the file", Toast.LENGTH_LONG).show()
            }
            finish()
        }
    }

    private fun extractUris(i: Intent): List<Uri> {
        val out = LinkedHashSet<Uri>()
        when (i.action) {
            Intent.ACTION_VIEW -> i.data?.let { out.add(it) }
            Intent.ACTION_SEND -> streamExtra(i)?.let { out.add(it) }
            Intent.ACTION_SEND_MULTIPLE -> streamList(i).forEach { out.add(it) }
        }
        i.clipData?.let { cd -> for (n in 0 until cd.itemCount) cd.getItemAt(n).uri?.let { out.add(it) } }
        return out.toList()
    }

    @Suppress("DEPRECATION")
    private fun streamExtra(i: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else i.getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun streamList(i: Intent): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)) ?: emptyList()
}
