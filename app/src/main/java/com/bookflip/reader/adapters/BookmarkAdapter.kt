// adapters/BookmarkAdapter.kt
package com.bookflip.reader.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bookflip.reader.databinding.ItemBookmarkBinding
import com.bookflip.reader.models.Bookmark
import java.text.SimpleDateFormat
import java.util.*

class BookmarkAdapter(
    private val onBookmarkClick: (Bookmark) -> Unit,
    private val onDeleteClick: (Bookmark) -> Unit
) : ListAdapter<Bookmark, BookmarkAdapter.BookmarkViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BookmarkViewHolder {
        val binding = ItemBookmarkBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return BookmarkViewHolder(binding)
    }

    override fun onBindViewHolder(holder: BookmarkViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class BookmarkViewHolder(private val binding: ItemBookmarkBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(bookmark: Bookmark) {
            binding.apply {
                tvBookmarkTitle.text = bookmark.title
                tvPageNumber.text = "Page ${bookmark.pageNumber + 1}"
                tvBookmarkNote.text = bookmark.note.ifEmpty { "No note added" }
                tvDate.text = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
                    .format(Date(bookmark.createdDate))

                root.setOnClickListener { onBookmarkClick(bookmark) }
                btnDelete.setOnClickListener { onDeleteClick(bookmark) }
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<Bookmark>() {
        override fun areItemsTheSame(oldItem: Bookmark, newItem: Bookmark) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Bookmark, newItem: Bookmark) = oldItem == newItem
    }
}