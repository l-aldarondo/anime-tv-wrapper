package com.example.animetv.ui.adapter

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.example.animetv.R
import com.example.animetv.core.history.PlaybackRecord
import com.example.animetv.core.history.WatchedEpisodeStore
import com.example.animetv.core.model.AnimeEpisode
import java.util.Locale

class EpisodeAdapter(
    private var episodes: List<AnimeEpisode>,
    private var animeDetailUrl: String = "",
    private var showPosterUrl: String = "",
    private var lastWatchedRecord: PlaybackRecord? = null,
    private val onEpisodeFocus: ((AnimeEpisode) -> Unit)? = null,
    private val onEpisodeClick: (AnimeEpisode) -> Unit,
    private val onEpisodeTorrentClick: ((AnimeEpisode) -> Unit)? = null,
    private val onEpisodeOptionsClick: ((AnimeEpisode) -> Unit)? = null,
    private val onWatchedChanged: (() -> Unit)? = null
) : RecyclerView.Adapter<EpisodeAdapter.ViewHolder>() {

    class ViewHolder(val view: View) : RecyclerView.ViewHolder(view) {
        val still: ImageView = view.findViewById(R.id.imgEpisodeStill)
        val watchedBadge: TextView = view.findViewById(R.id.txtEpisodeWatchedBadge)
        val torBadge: TextView = view.findViewById(R.id.txtEpisodeTorBadge)
        val badge: TextView = view.findViewById(R.id.txtEpisodeBadge)
        val title: TextView = view.findViewById(R.id.txtEpisodeTitle)
        val synopsis: TextView = view.findViewById(R.id.txtEpisodeSynopsis)
        val duration: TextView = view.findViewById(R.id.txtEpisodeDuration)
        val rating: TextView = view.findViewById(R.id.txtEpisodeRating)
        val releaseDate: TextView = view.findViewById(R.id.txtEpisodeReleaseDate)
        val progressWatched: ProgressBar = view.findViewById(R.id.progressEpisodeWatched)
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

        // Episode Badge (e.g. EPISODIO 11)
        holder.badge.text = "EPISODIO $eNum"
        val cleanTitle = ep.title
            .replace(Regex("""^(?:Episodio|Episode|Capítulo|Capitulo|Cap\.?|Ep\.?)\s*\d+[\s:\.\-–—]*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""^\d+[\s:\.\-–—]+"""), "")
            .trim()
        holder.title.text = if (cleanTitle.isNotEmpty()) cleanTitle else "Episodio $eNum"
        holder.synopsis.text = ep.synopsis.ifEmpty { "Sin sinopsis disponible para este episodio." }
        holder.torBadge.visibility = if (onEpisodeTorrentClick != null || onEpisodeOptionsClick != null) View.VISIBLE else View.GONE

        if (ep.releaseDate.isNotEmpty()) {
            holder.releaseDate.text = ep.releaseDate
            holder.releaseDate.visibility = View.VISIBLE
        } else {
            holder.releaseDate.visibility = View.GONE
        }

        // Full-bleed Still
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
        val isExplicitWatched = WatchedEpisodeStore.isEpisodeWatched(
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

        // Playback progress indicator
        if (rec != null && (rec.episodeUrl == ep.episodeUrl || rec.episodeNumber == ep.episodeNumber) && rec.durationMs > 0) {
            val pct = ((rec.positionMs * 100) / rec.durationMs).toInt().coerceIn(0, 100)
            holder.progressWatched.progress = pct
            holder.progressWatched.visibility = View.VISIBLE

            if (!isWatched && rec.positionMs > 5000) {
                val remainingMs = rec.durationMs - rec.positionMs
                val remMin = (remainingMs / 60000).toInt()
                holder.duration.text = if (remMin > 0) "⏱ ${remMin}m restantes" else "⏱ ${formatTime(rec.positionMs)}"
                holder.duration.setTextColor(0xFFFFD54F.toInt())
                holder.duration.visibility = View.VISIBLE
            } else {
                holder.duration.visibility = View.GONE
            }
        } else {
            holder.progressWatched.visibility = View.GONE
            holder.duration.visibility = View.GONE
        }

        // Focus scale animation
        val focusInterpolator = AnimationUtils.loadInterpolator(holder.itemView.context, R.interpolator.premium_focus)
        holder.itemView.setOnFocusChangeListener { view, hasFocus ->
            if (hasFocus) {
                view.animate().scaleX(1.06f).scaleY(1.06f).translationZ(12f)
                    .setInterpolator(focusInterpolator).setDuration(200).start()
                onEpisodeFocus?.invoke(ep)
            } else {
                view.animate().scaleX(1.0f).scaleY(1.0f).translationZ(0f)
                    .setInterpolator(focusInterpolator).setDuration(200).start()
            }
        }

        // Regular click to play
        holder.itemView.setOnClickListener {
            onEpisodeClick(ep)
        }

        // Toggle Watched Logic
        fun toggleWatchedStatus() {
            val ctx = holder.itemView.context
            val nowWatched = WatchedEpisodeStore.toggleEpisodeWatched(
                ctx, animeDetailUrl, sNum, ep.episodeNumber, ep.episodeUrl
            )
            holder.watchedBadge.visibility = if (nowWatched) View.VISIBLE else View.GONE
            val msg = if (nowWatched) "✓ Episodio $eNum marcado como visto" else "↩ Episodio $eNum marcado como no visto"
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
            onWatchedChanged?.invoke()
        }

        fun showOptionsOrWatched() {
            if (onEpisodeOptionsClick != null) {
                onEpisodeOptionsClick.invoke(ep)
            } else {
                toggleWatchedStatus()
            }
        }

        // Touch/mouse long click
        holder.itemView.isLongClickable = true
        holder.itemView.setOnLongClickListener {
            showOptionsOrWatched()
            true
        }

        // Android TV remote handling:
        // 1. Quick press DPAD_CENTER / ENTER -> Play episode (instant WEB play)
        // 2. Hold DPAD_CENTER / ENTER (~500ms) -> Show options dialog (WEB / Torrent / Watched / Restart)
        // 3. Press MENU / INFO -> Instantly show options dialog
        var isLongPressTriggered = false
        val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong().coerceIn(400L, 600L)
        val longPressRunnable = Runnable {
            isLongPressTriggered = true
            holder.itemView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            showOptionsOrWatched()
        }

        holder.itemView.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> {
                        if (event.repeatCount == 0) {
                            isLongPressTriggered = false
                            holder.itemView.handler?.postDelayed(longPressRunnable, longPressTimeout)
                        }
                        return@setOnKeyListener true
                    }
                    KeyEvent.ACTION_UP -> {
                        holder.itemView.handler?.removeCallbacks(longPressRunnable)
                        if (isLongPressTriggered) {
                            isLongPressTriggered = false
                            return@setOnKeyListener true
                        } else {
                            holder.itemView.performClick()
                            return@setOnKeyListener true
                        }
                    }
                }
            } else if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_INFO) {
                if (event.action == KeyEvent.ACTION_UP) {
                    showOptionsOrWatched()
                    return@setOnKeyListener true
                }
            }
            false
        }
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
