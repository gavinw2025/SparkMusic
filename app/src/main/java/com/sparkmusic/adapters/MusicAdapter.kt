package com.sparkmusic.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.sparkmusic.LibraryGroup
import com.sparkmusic.MusicItem
import com.sparkmusic.R

class MusicAdapter(
    private val onSongClick: (MusicItem) -> Unit,
    private val onGroupClick: (LibraryGroup) -> Unit,
    private val onFavouriteClick: (MusicItem) -> Unit,
    private val isFavourite: (MusicItem) -> Boolean
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    sealed class Row {
        data class Song(val item: MusicItem) : Row()
        data class Group(val group: LibraryGroup) : Row()
    }

    private var rows: List<Row> = emptyList()

    fun submitRows(newRows: List<Row>) {
        rows = newRows
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is Row.Song -> TYPE_SONG
        is Row.Group -> TYPE_GROUP
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_GROUP) {
            GroupViewHolder(inflater.inflate(R.layout.item_group, parent, false))
        } else {
            SongViewHolder(inflater.inflate(R.layout.item_music, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Song -> (holder as SongViewHolder).bind(row.item)
            is Row.Group -> (holder as GroupViewHolder).bind(row.group)
        }
    }

    override fun getItemCount(): Int = rows.size

    inner class SongViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTitle: TextView = itemView.findViewById(R.id.tvTitle)
        private val tvArtist: TextView = itemView.findViewById(R.id.tvArtist)
        private val btnFavourite: ImageButton = itemView.findViewById(R.id.btnFavourite)

        fun bind(musicItem: MusicItem) {
            tvTitle.text = musicItem.title
            tvArtist.text = musicItem.artist

            val favourite = isFavourite(musicItem)
            btnFavourite.setImageResource(
                if (favourite) R.drawable.ic_favorite else R.drawable.ic_favorite_border
            )
            btnFavourite.contentDescription = itemView.context.getString(
                if (favourite) R.string.remove_from_favourites else R.string.add_to_favourites
            )
            btnFavourite.setOnClickListener { onFavouriteClick(musicItem) }
            itemView.setOnClickListener { onSongClick(musicItem) }
        }
    }

    inner class GroupViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvGroupTitle: TextView = itemView.findViewById(R.id.tvGroupTitle)
        private val tvGroupSubtitle: TextView = itemView.findViewById(R.id.tvGroupSubtitle)
        private val tvGroupCount: TextView = itemView.findViewById(R.id.tvGroupCount)

        fun bind(group: LibraryGroup) {
            tvGroupTitle.text = group.title
            tvGroupSubtitle.text = group.subtitle
            tvGroupSubtitle.visibility = if (group.subtitle.isEmpty()) View.GONE else View.VISIBLE
            tvGroupCount.text = itemView.context.resources.getQuantityString(
                R.plurals.song_count,
                group.count,
                group.count
            )
            itemView.setOnClickListener { onGroupClick(group) }
        }
    }

    private companion object {
        private const val TYPE_SONG = 0
        private const val TYPE_GROUP = 1
    }
}
