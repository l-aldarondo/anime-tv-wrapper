package com.example.animetv.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.animetv.R

class SeasonCapsuleAdapter(
    private var seasons: List<Int>,
    var selectedSeason: Int,
    private val onSeasonSelected: (Int) -> Unit
) : RecyclerView.Adapter<SeasonCapsuleAdapter.ViewHolder>() {

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val txtSeasonName: TextView = itemView.findViewById(R.id.txtSeasonName)
    }

    fun updateSeasons(newSeasons: List<Int>, newSelectedSeason: Int = selectedSeason) {
        this.seasons = newSeasons
        this.selectedSeason = if (newSeasons.contains(newSelectedSeason)) newSelectedSeason else (newSeasons.firstOrNull() ?: 1)
        notifyDataSetChanged()
    }

    fun setSelected(season: Int) {
        if (selectedSeason != season) {
            selectedSeason = season
            notifyDataSetChanged()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_season_capsule, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val s = seasons[position]
        holder.txtSeasonName.text = "Temporada $s"

        val isCurrentSelected = (s == selectedSeason)
        if (isCurrentSelected) {
            holder.txtSeasonName.setBackgroundResource(R.drawable.bg_season_capsule_selected)
            holder.txtSeasonName.setTextColor(0xFFFFFFFF.toInt())
        } else {
            holder.txtSeasonName.setBackgroundResource(R.drawable.bg_season_capsule_unselected)
            holder.txtSeasonName.setTextColor(0xFFB0B3C0.toInt())
        }

        holder.itemView.setOnFocusChangeListener { view, hasFocus ->
            if (hasFocus) {
                view.animate().scaleX(1.08f).scaleY(1.08f).translationZ(6f).setDuration(150).start()
            } else {
                view.animate().scaleX(1.0f).scaleY(1.0f).translationZ(0f).setDuration(150).start()
            }
        }

        holder.itemView.setOnClickListener {
            if (selectedSeason != s) {
                selectedSeason = s
                notifyDataSetChanged()
                onSeasonSelected(s)
            }
        }
    }

    override fun getItemCount(): Int = seasons.size
}
