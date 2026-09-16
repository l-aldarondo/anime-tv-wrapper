package com.example.animetv.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.animetv.R
import com.example.animetv.core.model.AnimeCard
import com.example.animetv.core.model.CatalogRow
import com.example.animetv.core.model.CatalogRowType

class CatalogRowAdapter(
    private val rows: MutableList<CatalogRow>,
    private val onCardClick: (AnimeCard) -> Unit,
    private val onCardLongClick: ((AnimeCard) -> Unit)? = null,
    // Per-row overrides keyed by a substring of the row title
    private val rowLongClickOverrides: Map<String, (AnimeCard) -> Unit> = emptyMap()
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

        // Determine the effective long-click handler for this row
        val effectiveLongClick: ((AnimeCard) -> Unit)? = rowLongClickOverrides.entries
            .firstOrNull { (key, _) -> row.title.contains(key, ignoreCase = true) }
            ?.value ?: onCardLongClick

        holder.recycler.adapter = if (row.type == CatalogRowType.CONTINUE_WATCHING) {
            ContinueWatchingCardAdapter(
                row.cards.toMutableList(),
                onCardClick = onCardClick,
                onCardLongClick = effectiveLongClick
            )
        } else {
            AnimeCardAdapter(
                row.cards.toMutableList(),
                onCardClick = onCardClick,
                onCardLongClick = effectiveLongClick
            )
        }
    }

    override fun getItemCount(): Int = rows.size

    fun submitRows(newRows: List<CatalogRow>) {
        rows.clear()
        rows.addAll(newRows)
        notifyDataSetChanged()
    }
}
