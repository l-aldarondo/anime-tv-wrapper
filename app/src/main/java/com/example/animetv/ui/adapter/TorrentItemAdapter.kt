package com.example.animetv.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.animetv.R
import com.example.animetv.core.torrent.TorrentStreamItem

class TorrentItemAdapter(
    private var items: List<TorrentStreamItem>,
    private val onItemClick: (TorrentStreamItem) -> Unit
) : RecyclerView.Adapter<TorrentItemAdapter.TorrentViewHolder>() {

    fun updateList(newItems: List<TorrentStreamItem>) {
        this.items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TorrentViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_torrent_stream, parent, false)
        return TorrentViewHolder(view)
    }

    override fun onBindViewHolder(holder: TorrentViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class TorrentViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val txtTitle: TextView = itemView.findViewById(R.id.txtTorrentTitle)
        private val txtBadgeResolution: TextView = itemView.findViewById(R.id.txtBadgeResolution)
        private val txtBadgeLanguage: TextView = itemView.findViewById(R.id.txtBadgeLanguage)
        private val txtBadgeSeeders: TextView = itemView.findViewById(R.id.txtBadgeSeeders)
        private val txtBadgeSize: TextView = itemView.findViewById(R.id.txtBadgeSize)
        private val txtBadgeProvider: TextView = itemView.findViewById(R.id.txtBadgeProvider)

        fun bind(item: TorrentStreamItem) {
            txtTitle.text = item.title

            txtBadgeResolution.text = item.resolutionBadge.ifEmpty { "1080p" }

            txtBadgeLanguage.text = item.languageBadge
            if (item.languagePriority == 1) {
                txtBadgeLanguage.setTextColor(0xFF69F0AE.toInt()) // Vibrant green for Spanish/Latino
            } else if (item.languagePriority == 2) {
                txtBadgeLanguage.setTextColor(0xFF00E5FF.toInt()) // Cyan for Dual Audio
            } else {
                txtBadgeLanguage.setTextColor(0xFFE0E0E0.toInt()) // Neutral for English/Others
            }

            txtBadgeSeeders.text = "🌱 ${item.seeders} seeds"
            txtBadgeSize.text = item.sizeFormatted
            txtBadgeProvider.text = item.provider

            itemView.setOnClickListener {
                onItemClick(item)
            }

            // TV focus animation
            itemView.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    itemView.animate().scaleX(1.02f).scaleY(1.02f).setDuration(120).start()
                } else {
                    itemView.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
                }
            }
        }
    }
}
