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
import kotlinx.coroutines.*

class AnimeCardAdapter(
    private val items: MutableList<AnimeCard>,
    private val onCardClick: (AnimeCard) -> Unit,
    private val onCardLongClick: ((AnimeCard) -> Unit)? = null,
    private val onCardFocus: ((AnimeCard) -> Unit)? = null
) : RecyclerView.Adapter<AnimeCardAdapter.ViewHolder>() {

    private val adapterScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val poster: ImageView = view.findViewById(R.id.imgPoster)
        val title: TextView? = view.findViewById(R.id.txtTitle)
        val source: TextView = view.findViewById(R.id.txtSource)
        val badge: TextView = view.findViewById(R.id.txtBadge)
        var tmdbJob: Job? = null
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

        // Badge logic simplification
        val badgeText = item.episodeBadge.ifEmpty { item.rating }
        if (badgeText.isNotEmpty()) {
            holder.badge.visibility = View.VISIBLE
            holder.badge.text = badgeText
        } else {
            holder.badge.visibility = View.GONE
        }

        // Load initial scraped poster
        val validPoster = if (CoverUtils.isValidCover(item.posterUrl)) item.posterUrl.trim() else ""
        Glide.with(holder.itemView)
            .load(validPoster.ifEmpty { R.drawable.bg_card_poster_placeholder })
            .centerCrop()
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .placeholder(R.drawable.bg_card_poster_placeholder)
            .error(R.drawable.bg_card_poster_placeholder)
            .into(holder.poster)

        // Asynchronously fetch HQ metadata from TMDB
        holder.tmdbJob?.cancel()
        holder.tmdbJob = adapterScope.launch {
            val isMovie = item.detailUrl.contains("/pelicula/") || item.episodeBadge.equals("Película", ignoreCase = true)
            val isLiveAction = item.source.contains("SoloLatino", ignoreCase = true) && !item.detailUrl.contains("/animes")
            
            val meta = withContext(Dispatchers.IO) {
                try {
                    com.example.animetv.core.tmdb.TmdbMetadataRepository.searchMetadata(
                        holder.itemView.context, item.title, isMovie, isLiveAction
                    )
                } catch (_: Exception) { null }
            }

            if (isActive && meta != null && meta.posterUrl.isNotEmpty()) {
                Glide.with(holder.itemView)
                    .load(meta.posterUrl)
                    .centerCrop()
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .placeholder(holder.poster.drawable)
                    .into(holder.poster)
            }
        }

        // TV Focus Animation
        val focusInterpolator = AnimationUtils.loadInterpolator(holder.itemView.context, R.interpolator.premium_focus)
        holder.itemView.apply {
            setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    view.animate().scaleX(1.1f).scaleY(1.1f).translationZ(16f)
                        .setInterpolator(focusInterpolator).setDuration(275).start()
                    onCardFocus?.invoke(item)
                } else {
                    view.animate().scaleX(1.0f).scaleY(1.0f).translationZ(0f)
                        .setInterpolator(focusInterpolator).setDuration(275).start()
                }
            }
            setOnClickListener { onCardClick(item) }
            setOnLongClickListener {
                onCardLongClick?.invoke(item)
                true
            }
        }
    }

    override fun getItemCount(): Int = items.size

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        holder.tmdbJob?.cancel()
        Glide.with(holder.itemView).clear(holder.poster)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        adapterScope.cancel()
    }

    fun submitList(newItems: List<AnimeCard>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }
}
