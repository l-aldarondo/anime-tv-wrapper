package com.example.animetv

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

// Plain click opens the show; long-press removes it from My List (long-press-to-toggle is the
// same gesture used to ADD a show from anywhere else in the app - see MainActivity's
// handleLongPressFavorite() - so it stays consistent as the one gesture for "this show's saved
// status" everywhere, including here). D-pad OK/long-OK on a focused card map to these same
// click/long-click listeners automatically via Android's standard View key handling - no custom
// key routing needed for this screen (see MainActivity.dispatchKeyEvent's SOURCE_FAVORITES bypass).
class FavoritesAdapter(
    private val items: MutableList<FavoriteItem>,
    private val onOpen: (FavoriteItem) -> Unit,
    private val onRemove: (FavoriteItem) -> Unit
) : RecyclerView.Adapter<FavoritesAdapter.ViewHolder>() {

    class ViewHolder(view: android.view.View) : RecyclerView.ViewHolder(view) {
        val poster: ImageView = view.findViewById(R.id.imgFavoritePoster)
        val title: TextView = view.findViewById(R.id.txtFavoriteTitle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_favorite_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.title.text = item.title
        Glide.with(holder.poster)
            .load(item.poster.ifEmpty { null })
            .centerCrop()
            .into(holder.poster)
        holder.itemView.setOnClickListener { onOpen(item) }
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
