package com.example.animetv.ui.adapter

import android.view.KeyEvent
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

    companion object {
        private const val LONG_PRESS_MS = 500L
    }

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
        // Standard long-click covers touch input.
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
        // Explicit hold-to-toggle for the TV remote D-pad: measures actual key-down/up elapsed
        // time instead of relying on the platform's default long-press timer via performLongClick,
        // which isn't always reliable for a focused Button on D-pad-driven Android TV apps.
        val holdToggle = createHoldToWatchedToggleKeyListener(toggleWatched)
        holder.btnWeb.setOnKeyListener(holdToggle)
        holder.btnTorrent.setOnKeyListener(holdToggle)
    }

    override fun getItemCount(): Int = episodes.size

    /**
     * Builds an [View.OnKeyListener] that fires [onHold] when DPAD_CENTER/ENTER has been held
     * down for at least [LONG_PRESS_MS], and otherwise lets the key event fall through normally
     * (so a quick press still triggers the view's own click listener via the default ACTION_UP
     * handling). Consumes the ACTION_UP only when it followed a detected hold, so the click isn't
     * also fired right after toggling watched status.
     */
    private fun createHoldToWatchedToggleKeyListener(onHold: () -> Unit): View.OnKeyListener {
        var downAt = 0L
        return View.OnKeyListener { _, keyCode, event ->
            if (keyCode != KeyEvent.KEYCODE_DPAD_CENTER && keyCode != KeyEvent.KEYCODE_ENTER) {
                return@OnKeyListener false
            }
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    // Some TV remotes/launchers never deliver repeated ACTION_DOWN events for a
                    // held DPAD_CENTER/ENTER (unlike the D-pad direction keys), so the hold can't
                    // be detected while the key is down — only measured once it's released.
                    if (event.repeatCount == 0) downAt = System.currentTimeMillis()
                    false
                }
                KeyEvent.ACTION_UP -> {
                    val held = if (downAt > 0) System.currentTimeMillis() - downAt else 0L
                    downAt = 0L
                    if (held >= LONG_PRESS_MS) {
                        onHold()
                        true // consume so the normal click doesn't also fire right after
                    } else {
                        false // quick press: let the default click-on-release proceed
                    }
                }
                else -> false
            }
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        val m = (totalSec / 60) % 60
        val s = totalSec % 60
        val h = totalSec / 3600
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%02d:%02d", m, s)
    }
}
