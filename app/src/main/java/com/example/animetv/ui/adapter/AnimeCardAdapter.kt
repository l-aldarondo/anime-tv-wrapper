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
import com.example.animetv.core.model.AnimeCard

class AnimeCardAdapter(
    private val items: MutableList<AnimeCard>,
    private val onCardClick: (AnimeCard) -> Unit,
    private val onCardLongClick: ((AnimeCard) -> Unit)? = null
) : RecyclerView.Adapter<AnimeCardAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val poster: ImageView = view.findViewById(R.id.imgPoster)
        val title: TextView = view.findViewById(R.id.txtTitle)
        val source: TextView = view.findViewById(R.id.txtSource)
        val badge: TextView = view.findViewById(R.id.txtBadge)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_anime_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.title.text = item.title
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

        if (item.posterUrl.isNotEmpty()) {
            Glide.with(holder.poster.context)
                .load(item.posterUrl)
                .centerCrop()
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .placeholder(R.drawable.tv_banner)
                .into(holder.poster)
        } else {
            holder.poster.setImageResource(R.drawable.tv_banner)
        }

        // Native 10-foot TV smooth hardware scaling on remote focus
        holder.itemView.setOnFocusChangeListener { view, hasFocus ->
            if (hasFocus) {
                view.animate().scaleX(1.08f).scaleY(1.08f).translationZ(12f).setDuration(150).start()
            } else {
                view.animate().scaleX(1.0f).scaleY(1.0f).translationZ(0f).setDuration(150).start()
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

    fun submitList(newItems: List<AnimeCard>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }
}
