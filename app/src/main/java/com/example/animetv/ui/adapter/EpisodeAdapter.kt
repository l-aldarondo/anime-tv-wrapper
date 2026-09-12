package com.example.animetv.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.animetv.R
import com.example.animetv.core.history.PlaybackRecord
import com.example.animetv.core.model.AnimeEpisode
import java.util.Locale

class EpisodeAdapter(
    private var episodes: List<AnimeEpisode>,
    private var lastWatchedRecord: PlaybackRecord? = null,
    private val onEpisodeClick: (AnimeEpisode) -> Unit
) : RecyclerView.Adapter<EpisodeAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.txtEpisodeTitle)
        val progress: TextView = view.findViewById(R.id.txtEpisodeProgress)
    }

    fun updateList(newList: List<AnimeEpisode>, record: PlaybackRecord? = null) {
        this.episodes = newList
        if (record != null) {
            this.lastWatchedRecord = record
        }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_episode_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val ep = episodes[position]
        holder.title.text = ep.title.ifEmpty { "Episodio ${ep.episodeNumber}" }

        val rec = lastWatchedRecord
        if (rec != null && (rec.episodeUrl == ep.episodeUrl || rec.episodeNumber == ep.episodeNumber)) {
            holder.progress.visibility = View.VISIBLE
            if (rec.durationMs > 0 && rec.positionMs >= (rec.durationMs * 0.9)) {
                holder.progress.text = "✓ Visto"
                holder.progress.setTextColor(0xFF81C784.toInt()) // Light green
            } else if (rec.positionMs > 5000) {
                holder.progress.text = "▶ ${formatTime(rec.positionMs)}"
                holder.progress.setTextColor(0xFFFFD54F.toInt()) // Gold
            } else {
                holder.progress.text = "▶ Viendo"
                holder.progress.setTextColor(0xFFFFD54F.toInt())
            }
        } else {
            holder.progress.visibility = View.GONE
        }

        holder.itemView.setOnFocusChangeListener { view, hasFocus ->
            if (hasFocus) {
                view.animate().scaleX(1.08f).scaleY(1.08f).translationZ(8f).setDuration(150).start()
            } else {
                view.animate().scaleX(1.0f).scaleY(1.0f).translationZ(0f).setDuration(150).start()
            }
        }

        holder.itemView.setOnClickListener {
            onEpisodeClick(ep)
        }
    }

    override fun getItemCount(): Int = episodes.size

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        val m = (totalSec / 60) % 60
        val s = totalSec % 60
        val h = totalSec / 3600
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%02d:%02d", m, s)
    }
}

