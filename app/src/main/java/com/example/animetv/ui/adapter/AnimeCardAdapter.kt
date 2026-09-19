package com.example.animetv.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.example.animetv.R
import com.example.animetv.core.model.AnimeCard
import com.example.animetv.core.util.CoverUtils
import kotlinx.coroutines.*

class AnimeCardAdapter(
    private val onCardClick: (AnimeCard) -> Unit,
    private val onCardLongClick: ((AnimeCard) -> Unit)? = null,
    private val onCardFocus: ((AnimeCard) -> Unit)? = null
) : RecyclerView.Adapter<AnimeCardAdapter.ViewHolder>() {

    // Immutable snapshot — swapped via submitList() with DiffUtil
    private var items: List<AnimeCard> = emptyList()

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
        try {
            val item = items.getOrNull(position) ?: return

            holder.title?.text = item.title
            holder.source.text = formatSourceTag(item.source)

            // Badge logic
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

            // Asynchronously fetch HQ metadata from TMDB with debounce to avoid network floods during fast D-pad scroll
            holder.tmdbJob?.cancel()
            holder.tmdbJob = adapterScope.launch {
                delay(200) // Debounce: only load if card remains on screen for 200ms
                if (!isActive) return@launch
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

            // TV Focus Animation — scale up + elevate on focus
            val focusInterpolator = AnimationUtils.loadInterpolator(holder.itemView.context, R.interpolator.premium_focus)
            holder.itemView.apply {
                setOnFocusChangeListener { view, hasFocus ->
                    view.animate().cancel()
                    if (hasFocus) {
                        view.animate().scaleX(1.08f).scaleY(1.08f).translationZ(12f)
                            .setInterpolator(focusInterpolator).setDuration(150).start()
                        onCardFocus?.invoke(item)
                    } else {
                        view.animate().scaleX(1.0f).scaleY(1.0f).translationZ(0f)
                            .setInterpolator(focusInterpolator).setDuration(150).start()
                    }
                }
                setOnKeyListener { _, keyCode, event ->
                    if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                        val pos = holder.bindingAdapterPosition
                        if (pos == RecyclerView.NO_POSITION) return@setOnKeyListener false
                        if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT && pos >= items.size - 1) {
                            return@setOnKeyListener true // Clamp at end of row
                        }
                        if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT && pos <= 0) {
                            return@setOnKeyListener true // Clamp at start of row
                        }
                    }
                    false
                }
                setOnClickListener { onCardClick(item) }
                setOnLongClickListener {
                    onCardLongClick?.invoke(item)
                    true
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("AnimeCardAdapter", "Error binding card: ${e.message}", e)
        }
    }

    override fun getItemCount(): Int = items.size

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        holder.itemView.animate().cancel()
        holder.itemView.scaleX = 1.0f
        holder.itemView.scaleY = 1.0f
        holder.itemView.translationZ = 0f
        holder.tmdbJob?.cancel()
        holder.tmdbJob = null
        Glide.with(holder.itemView).clear(holder.poster)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        adapterScope.cancel()
    }

    /**
     * Submits a new list using DiffUtil so only changed items are updated.
     * This preserves RecyclerView scroll position and D-pad focus — no jump to position 0.
     */
    fun submitList(newItems: List<AnimeCard>) {
        val oldItems = items
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = oldItems.size
            override fun getNewListSize() = newItems.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int) =
                oldItems[oldPos].detailUrl == newItems[newPos].detailUrl
            override fun areContentsTheSame(oldPos: Int, newPos: Int) =
                oldItems[oldPos] == newItems[newPos]
        })
        items = newItems.toList() // snapshot
        diff.dispatchUpdatesTo(this) // granular updates — NO notifyDataSetChanged()
    }

    companion object {
        fun formatSourceTag(raw: String): String {
            return when {
                raw.contains("SoloLatino", ignoreCase = true) -> "SoloLatino"
                raw.contains("9Anime", ignoreCase = true) -> "9Anime HD"
                raw.contains("GoGoAnime", ignoreCase = true) -> "GoGoAnime"
                raw.contains("JKAnime", ignoreCase = true) || raw.contains("JK Anime", ignoreCase = true) -> "JK Anime"
                raw.contains("SoloStream", ignoreCase = true) -> "SoloStream"
                raw.contains("AnimeYT", ignoreCase = true) -> "AnimeYT"
                else -> raw.replace(Regex("""\s*\(.*?\)"""), "").trim()
            }
        }
    }
}
