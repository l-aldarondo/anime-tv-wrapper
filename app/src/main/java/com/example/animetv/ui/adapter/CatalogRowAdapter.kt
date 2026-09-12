package com.example.animetv.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.animetv.R
import com.example.animetv.core.model.AnimeCard

data class CatalogRow(
    val title: String,
    val cards: List<AnimeCard>
)

class CatalogRowAdapter(
    private val rows: MutableList<CatalogRow>,
    private val onCardClick: (AnimeCard) -> Unit,
    private val onCardLongClick: ((AnimeCard) -> Unit)? = null
) : RecyclerView.Adapter<CatalogRowAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.txtRowTitle)
        val recycler: RecyclerView = view.findViewById(R.id.recyclerRowCards)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_catalog_row, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val row = rows[position]
        holder.title.text = row.title

        val layoutManager = LinearLayoutManager(holder.itemView.context, LinearLayoutManager.HORIZONTAL, false)
        holder.recycler.layoutManager = layoutManager

        val cardAdapter = AnimeCardAdapter(
            row.cards.toMutableList(),
            onCardClick = onCardClick,
            onCardLongClick = onCardLongClick
        )
        holder.recycler.adapter = cardAdapter
    }

    override fun getItemCount(): Int = rows.size

    fun submitRows(newRows: List<CatalogRow>) {
        rows.clear()
        rows.addAll(newRows)
        notifyDataSetChanged()
    }
}
