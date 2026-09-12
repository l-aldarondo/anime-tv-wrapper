package com.example.animetv.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.animetv.R
import com.example.animetv.core.model.AnimeEpisode

class EpisodeAdapter(
    private val episodes: List<AnimeEpisode>,
    private val onEpisodeClick: (AnimeEpisode) -> Unit
) : RecyclerView.Adapter<EpisodeAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.txtEpisodeTitle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_episode_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val ep = episodes[position]
        holder.title.text = ep.title.ifEmpty { "Episodio ${ep.episodeNumber}" }

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
}
