package com.example.animetv.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.example.animetv.R
import com.example.animetv.core.model.AnimeCard
import com.example.animetv.core.util.CoverUtils
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AnimeCardAdapter(
    private val items: MutableList<AnimeCard>,
    private val onCardClick: (AnimeCard) -> Unit,
    private val onCardLongClick: ((AnimeCard) -> Unit)? = null,
    private val onCardFocus: ((AnimeCard) -> Unit)? = null
) : RecyclerView.Adapter<AnimeCardAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val poster: ImageView = view.findViewById(R.id.imgPoster)
        val title: TextView? = view.findViewById(R.id.txtTitle)
        val source: TextView = view.findViewById(R.id.txtSource)
        val badge: TextView = view.findViewById(R.id.txtBadge)
        var tmdbJob: kotlinx.coroutines.Job? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_anime_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.title?.text = item.title
        holder.source.text = item.source

        if (item.episodeBadge.isNotEmpty()) {
            holder.badge.visibility = View.VISIBLE
            holder.badge.text = item.episodeBadge
        } else if (item.rating.isNotEmpty()) {
            holder.badge.visibility = View.VISIBLE
            holder.badge.text = item.rating
        } else {
            holder.badge.visibility = View.GONE
        }

        // Load fallback (scraped) poster first
        val validPoster = if (CoverUtils.isValidCover(item.posterUrl)) item.posterUrl.trim() else ""
        if (validPoster.isNotEmpty()) {
            Glide.with(holder.poster.context)
                .load(validPoster)
                .centerCrop()
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .placeholder(R.drawable.bg_card_poster_placeholder)
                .error(R.drawable.bg_card_poster_placeholder)
                .into(holder.poster)
        } else {
            holder.poster.setImageResource(R.drawable.bg_card_poster_placeholder)
        }

        // Asynchronously fetch high-quality TMDB poster
        holder.tmdbJob?.cancel()
        holder.tmdbJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            val isMovie = item.detailUrl.contains("/pelicula/") || item.episodeBadge.equals("Película", ignoreCase = true)
            val isLiveAction = item.source.contains("SoloLatino", ignoreCase = true) && !item.detailUrl.contains("/animes")
            val meta = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    com.example.animetv.core.tmdb.TmdbMetadataRepository.searchMetadata(
                        holder.itemView.context, item.title, isMovie, isLiveAction
                    )
                } catch (e: Exception) { null }
            }
            if (meta != null && meta.posterUrl.isNotEmpty()) {
                Glide.with(holder.poster.context)
                    .load(meta.posterUrl)
                    .centerCrop()
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .placeholder(holder.poster.drawable)
                    .into(holder.poster)
            }
        }

        // Native 10-foot TV smooth hardware scaling on remote focus — 110% scale, elevated
        // shadow, and the "premium split" cubic-bezier(0.25, 1, 0.5, 1) motion curve.
        val focusInterpolator = AnimationUtils.loadInterpolator(holder.itemView.context, R.interpolator.premium_focus)
        holder.itemView.setOnFocusChangeListener { view, hasFocus ->
            if (hasFocus) {
                view.animate().scaleX(1.1f).scaleY(1.1f).translationZ(16f)
                    .setInterpolator(focusInterpolator).setDuration(275).start()
                onCardFocus?.invoke(item)
            } else {
                view.animate().scaleX(1.0f).scaleY(1.0f).translationZ(0f)
                    .setInterpolator(focusInterpolator).setDuration(275).start()
            }
        }

        holder.itemView.setOnClickListener {
            onCardClick(item)
        }

        holder.itemView.setOnLongClickListener {
            onCardLongClick?.invoke(item)
            true
        }
    }

    override fun getItemCount(): Int = items.size

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        holder.tmdbJob?.cancel()
    }

    fun submitList(newItems: List<AnimeCard>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }
}
