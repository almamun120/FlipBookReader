package com.bookflip.reader.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bookflip.reader.R
import com.bookflip.reader.models.Book
import com.bookflip.reader.models.FileType
import com.bumptech.glide.Glide
import java.io.File

class BookAdapter(
    private val onClick: (Book) -> Unit,
    private val onLongClick: (Book) -> Unit,
    private val onFavorite: (Book) -> Unit
) : ListAdapter<Book, BookAdapter.VH>(Diff()) {

    var isGrid: Boolean = true
        set(value) {
            if (field != value) { field = value; notifyDataSetChanged() }
        }

    override fun getItemViewType(position: Int) = if (isGrid) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layout = if (viewType == 0) R.layout.item_book_grid else R.layout.item_book_list
        return VH(LayoutInflater.from(parent.context).inflate(layout, parent, false))
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        private val cover: ImageView = v.findViewById(R.id.ivBookCover)
        private val title: TextView = v.findViewById(R.id.tvBookTitle)
        private val author: TextView = v.findViewById(R.id.tvBookAuthor)
        private val type: TextView = v.findViewById(R.id.tvFileType)
        private val fav: ImageButton = v.findViewById(R.id.btnFavorite)
        private val progress: ProgressBar = v.findViewById(R.id.progressBar)
        private val progressText: TextView = v.findViewById(R.id.tvProgress)
        private val desc: TextView? = v.findViewById(R.id.tvDescription)
        private val category: TextView? = v.findViewById(R.id.tvCategory)

        fun bind(book: Book) {
            title.text = book.title
            author.text = book.author.ifBlank { "Unknown author" }
            type.text = book.fileType.name

            val pct = (book.readingProgress * 100).toInt().coerceIn(0, 100)
            progress.progress = pct
            progressText.text = when {
                book.lastReadDate == 0L -> "New"
                pct >= 99 -> "Finished"
                else -> "$pct% · page ${book.lastReadPage + 1}"
            }

            desc?.let {
                it.text = book.description
                it.visibility = if (book.description.isBlank()) View.GONE else View.VISIBLE
            }
            category?.let {
                it.text = book.category
                it.visibility = if (book.category.isBlank()) View.GONE else View.VISIBLE
            }

            fav.setImageResource(if (book.isFavorite) R.drawable.ic_favorite else R.drawable.ic_favorite_border)
            fav.setColorFilter(if (book.isFavorite) 0xFFE5484D.toInt() else 0xFFFFFFFF.toInt().takeIf { isGrid } ?: 0xFF8A4B2D.toInt())

            val placeholder = if (book.fileType == FileType.PDF) R.drawable.ic_pdf_cover else R.drawable.ic_epub_cover
            if (book.coverImagePath.isNotEmpty() && File(book.coverImagePath).exists()) {
                Glide.with(cover).load(File(book.coverImagePath)).placeholder(placeholder).error(placeholder).into(cover)
            } else {
                Glide.with(cover).clear(cover)
                cover.setImageResource(placeholder)
            }

            itemView.setOnClickListener { onClick(book) }
            itemView.setOnLongClickListener { onLongClick(book); true }
            fav.setOnClickListener { onFavorite(book) }
        }
    }

    class Diff : DiffUtil.ItemCallback<Book>() {
        override fun areItemsTheSame(a: Book, b: Book) = a.id == b.id
        override fun areContentsTheSame(a: Book, b: Book) = a == b
    }
}
