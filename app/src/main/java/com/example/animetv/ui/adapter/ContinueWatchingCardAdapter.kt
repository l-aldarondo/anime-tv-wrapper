package com.example.animetv.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.example.animetv.R
import com.example.animetv.core.model.AnimeCard
import com.example.animetv.core.util.CoverUtils

/**
 * Landscape 16:9 card with an embedded progress bar, used only by the "Continuar Viendo" row —
 * every other row uses [AnimeCardAdapter]'s portrait poster cards instead.
 */
class ContinueWatchingCardAdapter(
    private val items: MutableList<AnimeCard>,
    private val onCardClick: (AnimeCard) -> Unit,
    private val onCardLongClick: ((AnimeCard) -> Unit)? = null,
    private val onCardFocus: ((AnimeCard) -> Unit)? = null
) : RecyclerView.Adapter<ContinueWatchingCardAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val still: ImageView = view.findViewById(R.id.imgPoster)
        val title: TextView = view.findViewById(R.id.txtTitle)
        val badge: TextView = view.findViewById(R.id.txtBadge)
        val progress: ProgressBar = view.findViewById(R.id.progressWatched)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_continue_watching_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.title.text = item.title
        holder.badge.text = item.episodeBadge
        holder.progress.progress = item.progressPercent.coerceIn(0, 100)

        val validPoster = if (CoverUtils.isValidCover(item.posterUrl)) item.posterUrl.trim() else ""
        if (validPoster.isNotEmpty()) {
            Glide.with(holder.still.context)
                .load(validPoster)
                .centerCrop()
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .placeholder(R.drawable.bg_card_poster_placeholder)
                .error(R.drawable.bg_card_poster_placeholder)
                .into(holder.still)
        } else {
            holder.still.setImageResource(R.drawable.bg_card_poster_placeholder)
        }

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
}
