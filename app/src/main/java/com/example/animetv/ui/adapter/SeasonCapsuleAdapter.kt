package com.example.animetv.ui.adapter

import android.os.Handler
import android.os.Looper
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

    private val handler = Handler(Looper.getMainLooper())
    private var pendingSelectionRunnable: Runnable? = null
    private var attachedRecyclerView: RecyclerView? = null

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val txtSeasonName: TextView = itemView.findViewById(R.id.txtSeasonName)
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        attachedRecyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        attachedRecyclerView = null
        pendingSelectionRunnable?.let { handler.removeCallbacks(it) }
    }

    fun updateSeasons(newSeasons: List<Int>, newSelectedSeason: Int = selectedSeason) {
        pendingSelectionRunnable?.let { handler.removeCallbacks(it) }
        this.seasons = newSeasons
        this.selectedSeason = if (newSeasons.contains(newSelectedSeason)) newSelectedSeason else (newSeasons.firstOrNull() ?: 1)
        notifyDataSetChanged()
    }

    fun setSelected(season: Int) {
        if (selectedSeason != season) {
            val oldPos = seasons.indexOf(selectedSeason)
            selectedSeason = season
            val newPos = seasons.indexOf(selectedSeason)
            if (oldPos >= 0) notifyItemChanged(oldPos)
            if (newPos >= 0) notifyItemChanged(newPos)
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
            holder.txtSeasonName.setTextColor(0xFF101216.toInt()) // Crisp dark text on white pill
        } else {
            holder.txtSeasonName.setBackgroundResource(R.drawable.bg_season_capsule_unselected)
            holder.txtSeasonName.setTextColor(0xFFB0B5C5.toInt())
        }

        // Prevent focus from escaping boundaries (e.g. from last season jumping back 5-6 seasons)
        holder.itemView.nextFocusRightId = if (position == seasons.size - 1) holder.itemView.id else View.NO_ID
        holder.itemView.nextFocusLeftId = if (position == 0) holder.itemView.id else View.NO_ID

        // Auto-select on focus (Stremio style) with debounced episode loading to prevent fast-scroll crashes
        holder.itemView.setOnFocusChangeListener { view, hasFocus ->
            if (hasFocus) {
                if (position == 0) {
                    view.pivotX = 0f
                } else {
                    view.pivotX = view.width / 2f
                }
                view.animate().scaleX(1.06f).scaleY(1.06f).translationZ(6f).setDuration(150).start()

                if (selectedSeason != s) {
                    val oldPos = seasons.indexOf(selectedSeason)
                    selectedSeason = s

                    // Safely update old pill without calling notifyItemChanged during focus/layout
                    if (oldPos >= 0) {
                        val oldVh = attachedRecyclerView?.findViewHolderForAdapterPosition(oldPos) as? ViewHolder
                        oldVh?.txtSeasonName?.setBackgroundResource(R.drawable.bg_season_capsule_unselected)
                        oldVh?.txtSeasonName?.setTextColor(0xFFB0B5C5.toInt())
                    }
                    holder.txtSeasonName.setBackgroundResource(R.drawable.bg_season_capsule_selected)
                    holder.txtSeasonName.setTextColor(0xFF101216.toInt())

                    // Debounce episode list reload by 200ms so fast D-pad flings never crash
                    pendingSelectionRunnable?.let { handler.removeCallbacks(it) }
                    val r = Runnable {
                        onSeasonSelected(s)
                    }
                    pendingSelectionRunnable = r
                    handler.postDelayed(r, 200L)
                }
            } else {
                view.animate().scaleX(1.0f).scaleY(1.0f).translationZ(0f).setDuration(150).start()
            }
        }

        holder.itemView.setOnClickListener {
            pendingSelectionRunnable?.let { handler.removeCallbacks(it) }
            if (selectedSeason != s) {
                val oldPos = seasons.indexOf(selectedSeason)
                selectedSeason = s
                if (oldPos >= 0) {
                    val oldVh = attachedRecyclerView?.findViewHolderForAdapterPosition(oldPos) as? ViewHolder
                    oldVh?.txtSeasonName?.setBackgroundResource(R.drawable.bg_season_capsule_unselected)
                    oldVh?.txtSeasonName?.setTextColor(0xFFB0B5C5.toInt())
                }
                holder.txtSeasonName.setBackgroundResource(R.drawable.bg_season_capsule_selected)
                holder.txtSeasonName.setTextColor(0xFF101216.toInt())
            }
            onSeasonSelected(s)
        }
    }

    override fun getItemCount(): Int = seasons.size
}
