package com.example.animetv.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.example.animetv.R
import com.example.animetv.core.history.PlaybackRecord
import com.example.animetv.core.model.AnimeEpisode
import java.util.Locale

class EpisodeAdapter(
    private var episodes: List<AnimeEpisode>,
    private var lastWatchedRecord: PlaybackRecord? = null,
    private val onEpisodeFocus: ((AnimeEpisode) -> Unit)? = null,
    private val onEpisodeLongClick: ((AnimeEpisode) -> Unit)? = null,
    private val onEpisodeClick: (AnimeEpisode) -> Unit
) : RecyclerView.Adapter<EpisodeAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val still: ImageView = view.findViewById(R.id.imgEpisodeStill)
        val badge: TextView = view.findViewById(R.id.txtEpisodeBadge)
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
        val sNum = if (ep.seasonNumber > 0) ep.seasonNumber else 1
        val eNum = ep.episodeNumber
        holder.badge.text = String.format(Locale.US, "S%02dE%02d", sNum, eNum)
        holder.title.text = ep.title.ifEmpty { "Episodio $eNum" }

        if (ep.stillUrl.isNotEmpty()) {
            holder.still.visibility = View.VISIBLE
            Glide.with(holder.itemView.context)
                .load(ep.stillUrl)
                .placeholder(R.drawable.bg_card_poster_placeholder)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .centerCrop()
                .into(holder.still)
        } else {
            holder.still.visibility = View.GONE
            Glide.with(holder.itemView.context).clear(holder.still)
        }

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
                onEpisodeFocus?.invoke(ep)
            } else {
                view.animate().scaleX(1.0f).scaleY(1.0f).translationZ(0f).setDuration(150).start()
            }
        }

        holder.itemView.setOnClickListener {
            onEpisodeClick(ep)
        }

        holder.itemView.setOnLongClickListener {
            if (onEpisodeLongClick != null) {
                onEpisodeLongClick.invoke(ep)
                true
            } else {
                false
            }
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
