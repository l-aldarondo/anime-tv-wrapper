package com.example.animetv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.example.animetv.core.util.CoverUtils

/**
 * RecyclerView Adapter for displaying saved anime in the Netflix/Prime-style "Mi Lista" grid.
 */
class FavoritesAdapter(
    private val items: MutableList<FavoriteItem>,
    private val onOpen: (FavoriteItem) -> Unit,
    private val onRemove: (FavoriteItem) -> Unit
) : RecyclerView.Adapter<FavoritesAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val poster: ImageView = view.findViewById(R.id.imgFavoritePoster)
        val title: TextView = view.findViewById(R.id.txtFavoriteTitle)
        val source: TextView = view.findViewById(R.id.txtFavoriteSource)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_favorite_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.title.text = item.title
        holder.source.text = item.source.ifEmpty { "Anime" }

        val validPoster = if (CoverUtils.isValidCover(item.poster)) item.poster.trim() else ""
        if (validPoster.isNotEmpty()) {
            Glide.with(holder.poster.context)
                .load(validPoster)
                .centerCrop()
                .placeholder(R.drawable.bg_card_poster_placeholder)
                .error(R.drawable.bg_card_poster_placeholder)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .into(holder.poster)
        } else {
            holder.poster.setImageResource(R.drawable.bg_card_poster_placeholder)
        }

        holder.itemView.setOnClickListener {
            onOpen(item)
        }

        holder.itemView.setOnLongClickListener {
            onRemove(item)
            true
        }
    }

    override fun getItemCount(): Int = items.size

    fun submit(newItems: List<FavoriteItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }
}
