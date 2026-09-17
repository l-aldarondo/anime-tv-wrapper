package com.example.animetv.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.example.animetv.R
import com.example.animetv.core.history.PlaybackRecord
import com.example.animetv.core.model.AnimeEpisode
import java.util.Locale

class EpisodeAdapter(
    private var episodes: List<AnimeEpisode>,
    private var animeDetailUrl: String = "",
    private var showPosterUrl: String = "",
    private var lastWatchedRecord: PlaybackRecord? = null,
    private val onEpisodeFocus: ((AnimeEpisode) -> Unit)? = null,
    private val onEpisodeClick: (AnimeEpisode) -> Unit,
    private val onEpisodeTorrentClick: ((AnimeEpisode) -> Unit)? = null
) : RecyclerView.Adapter<EpisodeAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val still: ImageView = view.findViewById(R.id.imgEpisodeStill)
        val badge: TextView = view.findViewById(R.id.txtEpisodeBadge)
        val title: TextView = view.findViewById(R.id.txtEpisodeTitle)
        val synopsis: TextView = view.findViewById(R.id.txtEpisodeSynopsis)
        val releaseDate: TextView = view.findViewById(R.id.txtEpisodeReleaseDate)
        val progress: TextView = view.findViewById(R.id.txtEpisodeProgress)
        val watchedBadge: TextView = view.findViewById(R.id.txtEpisodeWatchedBadge)
        val btnWeb: Button = view.findViewById(R.id.btnEpisodeWeb)
        val btnTorrent: Button = view.findViewById(R.id.btnEpisodeTorrent)
    }

    fun updateList(
        newList: List<AnimeEpisode>,
        detailUrl: String = "",
        posterUrl: String = "",
        record: PlaybackRecord? = null
    ) {
        this.episodes = newList
        if (detailUrl.isNotEmpty()) {
            this.animeDetailUrl = detailUrl
        }
        if (posterUrl.isNotEmpty()) {
            this.showPosterUrl = posterUrl
        }
        if (record != null) {
            this.lastWatchedRecord = record
        }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_episode_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val ep = episodes[position]
        val sNum = if (ep.seasonNumber > 0) ep.seasonNumber else 1
        val eNum = ep.episodeNumber
        holder.badge.text = String.format(Locale.US, "S%02dE%02d", sNum, eNum)
        holder.title.text = ep.title.ifEmpty { "Episodio $eNum" }
        holder.synopsis.text = ep.synopsis.ifEmpty { "Sin sinopsis disponible para este episodio." }
        if (ep.releaseDate.isNotEmpty()) {
            holder.releaseDate.text = ep.releaseDate
            holder.releaseDate.visibility = View.VISIBLE
        } else {
            holder.releaseDate.visibility = View.GONE
        }

        // Fall back to the show's own poster when this episode has no still, so the card never
        // shows a blank image area (TMDB often lacks per-episode stills for anime).
        val imageUrl = ep.stillUrl.ifEmpty { showPosterUrl }
        if (imageUrl.isNotEmpty()) {
            holder.still.visibility = View.VISIBLE
            Glide.with(holder.itemView.context)
                .load(imageUrl)
                .placeholder(R.drawable.bg_card_poster_placeholder)
                .error(R.drawable.bg_card_poster_placeholder)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .centerCrop()
                .into(holder.still)
        } else {
            holder.still.visibility = View.GONE
            Glide.with(holder.itemView.context).clear(holder.still)
        }

        val context = holder.itemView.context
        val isExplicitWatched = com.example.animetv.core.history.WatchedEpisodeStore.isEpisodeWatched(
            context,
            animeDetailUrl,
            sNum,
            eNum,
            ep.episodeUrl
        )

        val rec = lastWatchedRecord
        val isHistoryWatched = rec != null &&
                (rec.episodeUrl == ep.episodeUrl || rec.episodeNumber == ep.episodeNumber) &&
                rec.durationMs > 0 && rec.positionMs >= (rec.durationMs * 0.85)

        val isWatched = isExplicitWatched || isHistoryWatched
        holder.watchedBadge.visibility = if (isWatched) View.VISIBLE else View.GONE

        if (rec != null && (rec.episodeUrl == ep.episodeUrl || rec.episodeNumber == ep.episodeNumber)) {
            holder.progress.visibility = View.VISIBLE
            if (isWatched) {
                holder.progress.text = "Completado"
                holder.progress.setTextColor(0xFF81C784.toInt()) // Light green
            } else if (rec.positionMs > 5000) {
                holder.progress.text = "▶ ${formatTime(rec.positionMs)}"
                holder.progress.setTextColor(0xFFFFD54F.toInt()) // Gold
            } else {
                holder.progress.text = "▶ Viendo"
                holder.progress.setTextColor(0xFFFFD54F.toInt())
            }
        } else {
            holder.progress.visibility = View.GONE
        }

        // The card itself pops on focus, but WEB/TOR are the two focusable/actionable elements
        // (D-pad up/down moves between them, left/right moves to the next episode card).
        val focusInterpolator = AnimationUtils.loadInterpolator(holder.itemView.context, R.interpolator.premium_focus)
        val onFocus = View.OnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                holder.itemView.animate().scaleX(1.1f).scaleY(1.1f).translationZ(16f)
                    .setInterpolator(focusInterpolator).setDuration(275).start()
                onEpisodeFocus?.invoke(ep)
            } else if (!holder.btnWeb.isFocused && !holder.btnTorrent.isFocused) {
                holder.itemView.animate().scaleX(1.0f).scaleY(1.0f).translationZ(0f)
                    .setInterpolator(focusInterpolator).setDuration(275).start()
            }
        }
        holder.btnWeb.onFocusChangeListener = onFocus
        holder.btnTorrent.onFocusChangeListener = onFocus

        holder.btnWeb.setOnClickListener {
            onEpisodeClick(ep)
        }
        holder.btnTorrent.setOnClickListener {
            onEpisodeTorrentClick?.invoke(ep)
        }

        // Long-press toggles watched/unwatched status with immediate visual feedback
        val toggleWatched = {
            val ctx = holder.itemView.context
            val nowWatched = com.example.animetv.core.history.WatchedEpisodeStore.toggleEpisodeWatched(
                ctx, animeDetailUrl, sNum, ep.episodeNumber, ep.episodeUrl
            )
            holder.watchedBadge.visibility = if (nowWatched) View.VISIBLE else View.GONE
            val msg = if (nowWatched) "✓ Marcado como visto" else "↩ Marcado como no visto"
            android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
        // Standard long-click covers BOTH touch and the TV remote D-pad: Android's View already
        // has its own built-in long-press timer for a focused view — for touch it's the usual
        // ~500ms hold, and for a focused Button it also fires from held DPAD_CENTER/ENTER
        // (View.onKeyDown schedules the same check for those keys). A separate custom
        // OnKeyListener used to duplicate this by independently timing DOWN→UP itself, which
        // doesn't consume ACTION_DOWN — so both the built-in timer AND the custom one fired,
        // toggling watched status twice per hold and netting no visible change (or, worse,
        // silently reverting an episode a season-wide "mark watched" had just set). One listener
        // is enough.
        val onLongClick = View.OnLongClickListener { toggleWatched(); true }
        holder.btnWeb.setOnLongClickListener(onLongClick)
        holder.btnTorrent.setOnLongClickListener(onLongClick)
        // The parent RecyclerView scrolls horizontally, so by default it intercepts the touch
        // stream the moment it sees any movement while a finger is held down — including the
        // pixel-level shift caused by this card's own focus-scale animation, which starts the
        // instant the button is touched. That intercept cancels the pending long-press before it
        // ever fires. Telling the parent to leave the gesture alone for the duration of the touch
        // fixes long-press without touching the click/scroll behavior otherwise.
        val holdDisallowIntercept = View.OnTouchListener { v, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN ->
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                    v.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
        holder.btnWeb.setOnTouchListener(holdDisallowIntercept)
        holder.btnTorrent.setOnTouchListener(holdDisallowIntercept)
    }

    override fun getItemCount(): Int = episodes.size

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        val m = (totalSec / 60) % 60
        val s = totalSec % 60
        val h = totalSec / 3600
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%02d:%02d", m, s)
    }
}
