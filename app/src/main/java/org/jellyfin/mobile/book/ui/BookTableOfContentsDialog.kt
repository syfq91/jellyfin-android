package org.jellyfin.mobile.book.ui

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.jellyfin.mobile.R
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Publication

class BookTableOfContentsDialog : DialogFragment() {

    private var onLinkSelected: ((Link) -> Unit)? = null
    private var tocLinks: List<Link> = emptyList()

    fun setup(publication: Publication, onSelected: (Link) -> Unit) {
        this.tocLinks = flattenLinks(publication.tableOfContents)
        this.onLinkSelected = onSelected
    }

    private fun flattenLinks(links: List<Link>): List<Link> {
        val result = mutableListOf<Link>()
        for (link in links) {
            result.add(link)
            if (link.children.isNotEmpty()) {
                result.addAll(flattenLinks(link.children))
            }
        }
        return result
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val recyclerView = RecyclerView(requireContext()).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = TocAdapter { link ->
                onLinkSelected?.invoke(link)
                dismiss()
            }.apply {
                submitList(tocLinks)
            }
        }

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.book_reader_toc_title)
            .setView(recyclerView)
            .setNegativeButton(R.string.book_reader_close, null)
            .create()
    }

    private class TocAdapter(
        private val onItemClick: (Link) -> Unit,
    ) : ListAdapter<Link, TocViewHolder>(LinkDiffCallback) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TocViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(
                android.R.layout.simple_list_item_1,
                parent,
                false,
            )
            return TocViewHolder(view, onItemClick)
        }

        override fun onBindViewHolder(holder: TocViewHolder, position: Int) {
            holder.bind(getItem(position))
        }
    }

    private class TocViewHolder(
        itemView: View,
        private val onItemClick: (Link) -> Unit,
    ) : RecyclerView.ViewHolder(itemView) {
        private val text1: TextView = itemView.findViewById(android.R.id.text1)

        fun bind(link: Link) {
            text1.text = link.title ?: link.href.toString()
            itemView.setOnClickListener { onItemClick(link) }
        }
    }

    private object LinkDiffCallback : DiffUtil.ItemCallback<Link>() {
        override fun areItemsTheSame(oldItem: Link, newItem: Link): Boolean =
            oldItem.href == newItem.href

        override fun areContentsTheSame(oldItem: Link, newItem: Link): Boolean =
            oldItem.title == newItem.title
    }

    companion object {
        const val TAG = "BookTableOfContentsDialog"
    }
}
