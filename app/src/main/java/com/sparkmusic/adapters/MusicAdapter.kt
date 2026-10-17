package com.sparkmusic.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.sparkmusic.MusicItem
import com.sparkmusic.R

class MusicAdapter(
    private val onItemClick: (MusicItem) -> Unit
) : RecyclerView.Adapter<MusicAdapter.MusicViewHolder>() {

    private var musicList: List<MusicItem> = emptyList()

    fun submitList(list: List<MusicItem>) {
        musicList = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MusicViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_music, parent, false)
        return MusicViewHolder(view)
    }

    override fun onBindViewHolder(holder: MusicViewHolder, position: Int) {
        holder.bind(musicList[position])
    }

    override fun getItemCount() = musicList.size

    inner class MusicViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTitle: TextView = itemView.findViewById(R.id.tvTitle)
        private val tvArtist: TextView = itemView.findViewById(R.id.tvArtist)

        fun bind(musicItem: MusicItem) {
            tvTitle.text = musicItem.title
            tvArtist.text = musicItem.artist
            itemView.setOnClickListener { onItemClick(musicItem) }
        }
    }
}