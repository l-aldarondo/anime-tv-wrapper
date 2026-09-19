package com.example.animetv.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import java.util.Locale
import android.graphics.Typeface
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.example.animetv.FavoriteItem
import com.example.animetv.FavoritesStore
import com.example.animetv.R
import com.example.animetv.core.CatalogRepository
import com.example.animetv.core.history.PlaybackHistoryStore
import com.example.animetv.core.history.WatchedEpisodeStore
import com.example.animetv.core.model.AnimeCard
import com.example.animetv.core.model.AnimeDetail
import com.example.animetv.core.model.AnimeEpisode
import com.example.animetv.core.tmdb.TmdbMetadata
import com.example.animetv.core.tmdb.TmdbMetadataRepository
import com.example.animetv.core.torrent.TorrServerClient
import com.example.animetv.core.torrent.TorrentSearchRepository
import com.example.animetv.core.torrent.TorrentSettingsStore
import com.example.animetv.core.torrent.TorrentStreamItem
import com.example.animetv.core.util.CoverUtils
import com.example.animetv.ui.adapter.EpisodeAdapter
import com.example.animetv.ui.adapter.SeasonCapsuleAdapter
import com.example.animetv.ui.adapter.TorrentItemAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import com.example.animetv.core.model.StreamResult

class DetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ANIME_CARD = "extra_anime_card"

        fun start(context: Context, card: AnimeCard) {
            val intent = Intent(context, DetailActivity::class.java).apply {
                putExtra(EXTRA_ANIME_CARD, card)
            }
            context.startActivity(intent)
        }
    }

    private lateinit var imgBackdrop: ImageView
    private lateinit var imgDetailLogo: ImageView
    private lateinit var viewDetailTextGradient: com.example.animetv.ui.GradientOverlayView
    private lateinit var viewDetailBottomGradient: com.example.animetv.ui.GradientOverlayView
    private lateinit var txtTitle: TextView
    private lateinit var txtMeta: TextView
    private lateinit var layoutDetailImdb: View
    private lateinit var txtDetailImdbScore: TextView
    private lateinit var txtSynopsis: TextView
    private lateinit var txtMovieBadges: TextView
    private lateinit var txtMovieCredits: TextView
    private lateinit var btnPlayFirst: Button
    private lateinit var btnRestartEpisode: Button
    private lateinit var btnPlayTorrent: Button
    private lateinit var btnTrailer: Button
    private lateinit var btnToggleFavorite: Button
    private lateinit var btnToggleWatched: Button

    private lateinit var recyclerSeasons: RecyclerView
    private lateinit var recyclerEpisodes: RecyclerView
    private lateinit var progressBar: ProgressBar

    private var currentCard: AnimeCard? = null
    private var currentDetail: AnimeDetail? = null
    private var currentTmdbMeta: TmdbMetadata? = null
    private var currentlyFocusedEpisode: AnimeEpisode? = null
    private var isAscendingOrder: Boolean = true
    private var episodeAdapter: EpisodeAdapter? = null
    private var seasonAdapter: SeasonCapsuleAdapter? = null
    private var selectedSeason: Int = 1
    private var rawEpisodes: List<AnimeEpisode> = emptyList()
    // TMDB-derived season count used to show capsules for shows where the scraper returns 1 season
    private var tmdbSeasonCount: Int = 1
    // True when the current detail page is a movie (some scrapers still return a single
    // "episode" stub for movies, which must not be shown with a S01E01-style episode UI)
    private var isCurrentMovie: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detail)

        imgBackdrop = findViewById(R.id.imgDetailBackdrop)
        imgDetailLogo = findViewById(R.id.imgDetailLogo)
        viewDetailTextGradient = findViewById(R.id.viewDetailTextGradient)
        viewDetailBottomGradient = findViewById(R.id.viewDetailBottomGradient)
        setupDetailHeroGradients()
        txtTitle = findViewById(R.id.txtDetailTitle)
        txtMeta = findViewById(R.id.txtDetailMeta)
        layoutDetailImdb = findViewById(R.id.layoutDetailImdb)
        txtDetailImdbScore = findViewById(R.id.txtDetailImdbScore)
        txtSynopsis = findViewById(R.id.txtDetailSynopsis)
        txtMovieBadges = findViewById(R.id.txtMovieBadges)
        txtMovieCredits = findViewById(R.id.txtMovieCredits)
        btnPlayFirst = findViewById(R.id.btnPlayFirst)
        btnRestartEpisode = findViewById(R.id.btnRestartEpisode)
        btnPlayTorrent = findViewById(R.id.btnPlayTorrent)
        btnTrailer = findViewById(R.id.btnTrailer)
        btnToggleFavorite = findViewById(R.id.btnToggleFavorite)
        btnToggleWatched = findViewById(R.id.btnToggleWatched)

        setupButtonFocusAnimation(btnPlayFirst)
        setupButtonFocusAnimation(btnPlayTorrent)
        setupButtonFocusAnimation(btnToggleFavorite)
        setupButtonFocusAnimation(btnTrailer)
        setupButtonFocusAnimation(btnToggleWatched)
        setupButtonFocusAnimation(btnRestartEpisode)

        btnPlayTorrent.setOnClickListener {
            val ep = currentlyFocusedEpisode ?: rawEpisodes.firstOrNull()
            showTorrentSelectorDialog(ep)
        }
        btnToggleWatched.setOnClickListener {
            toggleWatchedShow()
        }

        val scrollDetail: ScrollView = findViewById(R.id.scrollDetail)
        val layoutDetailHero: View = findViewById(R.id.layoutDetailHero)
        recyclerSeasons = findViewById(R.id.recyclerSeasons)
        recyclerSeasons.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        recyclerSeasons.itemAnimator = null
        recyclerEpisodes = findViewById(R.id.recyclerEpisodes)
        recyclerEpisodes.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        recyclerEpisodes.itemAnimator = null

        // Smooth scroll controller for TV:
        // Down into Seasons/Episodes -> automatically glide down to reveal episode cards
        // Up back to Hero -> automatically glide back to the top
        var wasInBelowHero = false
        scrollDetail.viewTreeObserver.addOnGlobalFocusChangeListener { _, newFocus ->
            if (newFocus == null) return@addOnGlobalFocusChangeListener
            val isInSeasons = recyclerSeasons.indexOfChild(newFocus) != -1 || newFocus == recyclerSeasons
            val isInEpisodes = recyclerEpisodes.indexOfChild(newFocus) != -1 || newFocus == recyclerEpisodes
            val isInBelowHero = isInSeasons || isInEpisodes

            if (isInBelowHero && !wasInBelowHero) {
                scrollDetail.post {
                    val heroBottom = layoutDetailHero.bottom
                    val targetY = (heroBottom - (32 * resources.displayMetrics.density).toInt()).coerceAtLeast(0)
                    scrollDetail.smoothScrollTo(0, targetY)
                }
            } else if (!isInBelowHero && wasInBelowHero) {
                scrollDetail.post {
                    scrollDetail.smoothScrollTo(0, 0)
                }
            }
            wasInBelowHero = isInBelowHero
        }
        progressBar = findViewById(R.id.progressBarDetail)
        isAscendingOrder = com.example.animetv.core.history.UiPreferencesStore.isEpisodesAscending(this)

        @Suppress("DEPRECATION")
        currentCard = intent.getSerializableExtra(EXTRA_ANIME_CARD) as? AnimeCard
        if (currentCard == null) {
            Toast.makeText(this, "Error al cargar anime", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        bindInitialCard(currentCard!!)
        setupTrailerButton("", currentCard!!.title)
        loadDetail(currentCard!!)
    }

    override fun onResume() {
        super.onResume()
        // Refresh playback progress when returning from player
        refreshPlaybackState()
    }

    /**
     * Configures the hero's two gradient overlays: a left-to-right fade so the title/actions/
     * synopsis block on the left stays legible without flattening the backdrop art on the right,
     * and a bottom fade that dissolves the hero into the canvas color before the episode list.
     */
    private fun setupDetailHeroGradients() {
        val canvas = androidx.core.content.ContextCompat.getColor(this, R.color.primary_canvas)
        fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

        // Soft, airy left-to-right gradient: preserves image brightness and vivid colors across
        // the backdrop while keeping text legible via drop-shadows (matching Nuvio).
        viewDetailTextGradient.setGradient(
            com.example.animetv.ui.GradientOverlayView.Direction.LEFT_TO_RIGHT,
            intArrayOf(
                withAlpha(canvas, 180),
                withAlpha(canvas, 110),
                withAlpha(canvas, 20),
                withAlpha(canvas, 0)
            ),
            floatArrayOf(0f, 0.25f, 0.42f, 0.58f)
        )

        // Minimal bottom fade: only dissolves the very bottom edge into the canvas color before the episode list
        viewDetailBottomGradient.setGradient(
            com.example.animetv.ui.GradientOverlayView.Direction.BOTTOM_TO_TOP,
            intArrayOf(
                withAlpha(canvas, 255),
                withAlpha(canvas, 90),
                withAlpha(canvas, 0)
            ),
            floatArrayOf(0f, 0.08f, 0.22f)
        )
    }

    private fun setupButtonFocusAnimation(v: View) {
        v.setOnFocusChangeListener { view, hasFocus ->
            if (view == btnPlayFirst) {
                view.pivotX = 0f
            }
            if (hasFocus) {
                view.animate().scaleX(1.05f).scaleY(1.05f).translationZ(8f).setDuration(150).start()
            } else {
                view.animate().scaleX(1.0f).scaleY(1.0f).translationZ(0f).setDuration(150).start()
            }
        }
    }

    data class TargetEpisodeResult(
        val episode: AnimeEpisode,
        val targetSeason: Int,
        val isResume: Boolean,
        val actionText: String
    )

    private fun findNextChronologicalEpisode(currentEp: AnimeEpisode, allEpisodes: List<AnimeEpisode>): AnimeEpisode? {
        if (allEpisodes.isEmpty()) return null
        val sorted = allEpisodes.sortedWith(compareBy({ if (it.seasonNumber > 0) it.seasonNumber else 1 }, { it.episodeNumber }))
        val curSeason = if (currentEp.seasonNumber > 0) currentEp.seasonNumber else 1
        val curIndex = sorted.indexOfFirst {
            (it.episodeUrl.isNotEmpty() && it.episodeUrl == currentEp.episodeUrl) ||
            (it.episodeNumber == currentEp.episodeNumber && (if (it.seasonNumber > 0) it.seasonNumber else 1) == curSeason)
        }
        return if (curIndex >= 0 && curIndex < sorted.size - 1) sorted[curIndex + 1] else null
    }

    private fun resolveActiveOrNextEpisode(): TargetEpisodeResult? {
        val card = currentCard ?: return null
        val record = com.example.animetv.core.history.PlaybackHistoryStore.getRecordForAnime(this, card.detailUrl)

        if (record != null) {
            val recSeason = if (record.seasonNumber > 0) record.seasonNumber else 1
            val matchedEp = rawEpisodes.firstOrNull {
                (it.episodeUrl.isNotEmpty() && it.episodeUrl == record.episodeUrl) ||
                ((if (it.seasonNumber > 0) it.seasonNumber else 1) == recSeason && it.episodeNumber == record.episodeNumber) ||
                (record.seasonNumber <= 0 && it.episodeNumber == record.episodeNumber)
            }
            val currentEp = matchedEp ?: AnimeEpisode(
                episodeNumber = record.episodeNumber,
                seasonNumber = recSeason,
                title = record.episodeTitle,
                episodeUrl = record.episodeUrl
            )

            val isExplicitUnwatched = WatchedEpisodeStore.isEpisodeExplicitlyUnwatched(
                this, card.detailUrl, recSeason, record.episodeNumber, record.episodeUrl
            )
            val isExplicitWatched = WatchedEpisodeStore.isEpisodeWatched(
                this, card.detailUrl, recSeason, record.episodeNumber, record.episodeUrl
            )
            val isHistoryCompleted = record.durationMs > 0 && record.positionMs >= (record.durationMs * 0.85f)
            val isEpisodeFinished = !isExplicitUnwatched && (isExplicitWatched || isHistoryCompleted)

            if (isEpisodeFinished) {
                // Current episode finished -> find next chronological episode
                val nextEp = findNextChronologicalEpisode(currentEp, rawEpisodes)
                if (nextEp != null) {
                    val nextSeason = if (nextEp.seasonNumber > 0) nextEp.seasonNumber else 1
                    val btnText = if (isCurrentMovie) "▶ Reproducir" else "▶ Next S$nextSeason E${nextEp.episodeNumber}"
                    return TargetEpisodeResult(
                        episode = nextEp,
                        targetSeason = nextSeason,
                        isResume = false,
                        actionText = btnText
                    )
                } else {
                    // All episodes finished / finale
                    val firstEp = rawEpisodes.firstOrNull() ?: currentEp
                    val firstSeason = if (firstEp.seasonNumber > 0) firstEp.seasonNumber else 1
                    return TargetEpisodeResult(
                        episode = firstEp,
                        targetSeason = firstSeason,
                        isResume = false,
                        actionText = if (isCurrentMovie) "▶ Ver de nuevo" else "▶ Ver de nuevo (S1 E1)"
                    )
                }
            } else {
                // In progress (or unwatched): resume current episode
                val epSeason = if (currentEp.seasonNumber > 0) currentEp.seasonNumber else recSeason
                val btnText = if (isCurrentMovie) "▶ Continuar" else "▶ Continuar S$epSeason E${currentEp.episodeNumber}"
                return TargetEpisodeResult(
                    episode = currentEp,
                    targetSeason = epSeason,
                    isResume = true,
                    actionText = btnText
                )
            }
        } else if (rawEpisodes.isNotEmpty()) {
            val sorted = rawEpisodes.sortedWith(compareBy({ if (it.seasonNumber > 0) it.seasonNumber else 1 }, { it.episodeNumber }))
            val firstUnwatched = sorted.firstOrNull { ep ->
                val s = if (ep.seasonNumber > 0) ep.seasonNumber else 1
                !WatchedEpisodeStore.isEpisodeWatched(this, card.detailUrl, s, ep.episodeNumber, ep.episodeUrl)
            } ?: sorted.first()

            val targetSeason = if (firstUnwatched.seasonNumber > 0) firstUnwatched.seasonNumber else 1
            val isFirst = (firstUnwatched == sorted.first())
            val btnText = if (isCurrentMovie) {
                "▶ Reproducir"
            } else if (isFirst) {
                "▶ Reproducir S$targetSeason E${firstUnwatched.episodeNumber}"
            } else {
                "▶ Next S$targetSeason E${firstUnwatched.episodeNumber}"
            }
            return TargetEpisodeResult(
                episode = firstUnwatched,
                targetSeason = targetSeason,
                isResume = false,
                actionText = btnText
            )
        }

        return null
    }

    private fun refreshPlaybackState() {
        val card = currentCard ?: return
        val target = resolveActiveOrNextEpisode()

        if (target != null) {
            val ep = target.episode
            btnPlayFirst.text = target.actionText
            btnPlayFirst.setOnClickListener {
                val detail = currentDetail
                if (detail != null) {
                    playEpisode(detail, ep, startOver = !target.isResume)
                } else {
                    playEpisodeDirect(card, ep, startOver = !target.isResume)
                }
            }

            // Long-click to restart from beginning
            btnPlayFirst.setOnLongClickListener {
                val detail = currentDetail
                Toast.makeText(this, "↺ Reiniciando ${ep.title} desde el inicio...", Toast.LENGTH_SHORT).show()
                if (detail != null) {
                    playEpisode(detail, ep, startOver = true)
                } else {
                    playEpisodeDirect(card, ep, startOver = true)
                }
                true
            }

            val record = com.example.animetv.core.history.PlaybackHistoryStore.getRecordForAnime(this, card.detailUrl)
            if (target.isResume && record != null && record.positionMs > 5000) {
                btnRestartEpisode.visibility = View.VISIBLE
                btnRestartEpisode.text = "↺"
                btnRestartEpisode.setOnClickListener {
                    val detail = currentDetail
                    Toast.makeText(this, "↺ Reiniciando ${ep.title} desde el inicio...", Toast.LENGTH_SHORT).show()
                    if (detail != null) {
                        playEpisode(detail, ep, startOver = true)
                    } else {
                        playEpisodeDirect(card, ep, startOver = true)
                    }
                }
            } else {
                btnRestartEpisode.visibility = View.GONE
            }

            // Refresh progress indicators on current cards without destroying viewholders
            episodeAdapter?.updateRecord(record)
        } else {
            btnPlayFirst.text = if (isCurrentMovie) "▶ Película" else "▶ Reproducir"
            btnPlayFirst.setOnLongClickListener(null)
            btnPlayFirst.setOnClickListener {
                currentDetail?.let { playDirectUrl(it.detailUrl, it.title) }
            }
            btnRestartEpisode.visibility = View.GONE
        }

        btnPlayTorrent.text = "⚡ Torrent"
        btnPlayTorrent.setOnClickListener {
            val ep = target?.episode ?: currentlyFocusedEpisode ?: rawEpisodes.firstOrNull()
            if (ep != null) {
                showTorrentSelectorDialog(ep)
            }
        }
    }

    private fun getSortedEpisodes(list: List<AnimeEpisode>, ascending: Boolean): List<AnimeEpisode> {
        return if (ascending) {
            list.sortedWith(compareBy({ it.seasonNumber }, { it.episodeNumber }))
        } else {
            list.sortedWith(compareByDescending<AnimeEpisode> { it.seasonNumber }.thenByDescending { it.episodeNumber })
        }
    }

    /**
     * Tracks which episode currently has D-pad focus, updating the hero action buttons
     * so they immediately reflect and act on that episode.
     */
    private fun bindFocusedEpisode(ep: AnimeEpisode) {
        currentlyFocusedEpisode = ep
        val sNum = if (ep.seasonNumber > 0) ep.seasonNumber else selectedSeason
        val eNum = ep.episodeNumber
        val card = currentCard
        val rec = card?.let { PlaybackHistoryStore.getRecordForAnime(this, it.detailUrl) }
        val isSameEp = rec != null && (
            (ep.episodeUrl.isNotEmpty() && rec.episodeUrl.trimEnd('/') == ep.episodeUrl.trimEnd('/')) ||
            (rec.episodeNumber == eNum && (if (rec.seasonNumber > 0) rec.seasonNumber else 1) == sNum)
        )
        val isExplicitWatched = card?.let { WatchedEpisodeStore.isEpisodeWatched(this, it.detailUrl, sNum, eNum, ep.episodeUrl) } ?: false
        val isExplicitUnwatched = card?.let { WatchedEpisodeStore.isEpisodeExplicitlyUnwatched(this, it.detailUrl, sNum, eNum, ep.episodeUrl) } ?: false
        val isHistoryCompleted = isSameEp && rec!!.durationMs > 0 && rec.positionMs >= (rec.durationMs * 0.85f)
        val isWatched = if (isExplicitUnwatched) false else (isExplicitWatched || isHistoryCompleted)

        if (isCurrentMovie) {
            btnPlayFirst.text = if (isSameEp && rec!!.positionMs > 5000 && !isWatched) "▶ Continuar" else "▶ Reproducir"
        } else {
            if (isSameEp && rec!!.positionMs > 5000 && !isWatched) {
                btnPlayFirst.text = "▶ Continuar S$sNum E$eNum"
            } else if (isWatched) {
                btnPlayFirst.text = "▶ Ver de nuevo S$sNum E$eNum"
            } else {
                btnPlayFirst.text = "▶ Reproducir S$sNum E$eNum"
            }
        }
        btnPlayTorrent.text = "⚡ Torrent"
        btnPlayFirst.setOnClickListener {
            currentDetail?.let { playEpisode(it, ep, startOver = isWatched) }
                ?: currentCard?.let { playEpisodeDirect(it, ep, startOver = isWatched) }
        }
        btnPlayTorrent.setOnClickListener {
            showTorrentSelectorDialog(ep)
        }
    }

    private fun displayEpisodesForSeason(season: Int) {
        val hasMultiInRaw = rawEpisodes.any { it.seasonNumber > 1 }
        val seasonEpisodes = if (hasMultiInRaw) {
            rawEpisodes.filter { (if (it.seasonNumber > 0) it.seasonNumber else 1) == season }
        } else if (season == 1) {
            rawEpisodes
        } else {
            emptyList()
        }

        if (seasonEpisodes.isEmpty() && currentTmdbMeta != null && currentCard != null) {
            loadTmdbStubsForSeason(currentTmdbMeta!!, currentCard!!, season)
            return
        }

        val sortedList = getSortedEpisodes(seasonEpisodes, isAscendingOrder)
        val record = currentCard?.let { com.example.animetv.core.history.PlaybackHistoryStore.getRecordForAnime(this, it.detailUrl) }
        episodeAdapter?.updateList(sortedList, record = record)
        if (sortedList.isNotEmpty()) {
            val focused = currentlyFocusedEpisode
            val focusedSeason = if ((focused?.seasonNumber ?: 0) > 0) focused!!.seasonNumber else selectedSeason
            val focusedIndex = focused?.takeIf { focusedSeason == season }?.let { f ->
                sortedList.indexOfFirst { it.episodeUrl == f.episodeUrl || it.episodeNumber == f.episodeNumber }
            }?.takeIf { it >= 0 }
            val recordSeason = if ((record?.seasonNumber ?: 0) > 0) record!!.seasonNumber else 1
            val recordIndex = record?.takeIf { recordSeason == season }?.let { rec ->
                sortedList.indexOfFirst { it.episodeUrl == rec.episodeUrl || it.episodeNumber == rec.episodeNumber }
            }?.takeIf { it >= 0 }
            val resumeIndex = focusedIndex ?: recordIndex ?: 0
            bindFocusedEpisode(sortedList[resumeIndex])
            recyclerEpisodes.scrollToPosition(resumeIndex)
        } else {
            recyclerEpisodes.scrollToPosition(0)
        }
        refreshWatchedButtonState()
    }

    private fun toggleSortOrder() {
        isAscendingOrder = !isAscendingOrder
        com.example.animetv.core.history.UiPreferencesStore.setEpisodesAscending(this, isAscendingOrder)
        displayEpisodesForSeason(selectedSeason)
    }

    /**
     * Updates the watched-toggle button to reflect whether the whole show is watched.
     */
    private fun refreshWatchedButtonState() {
        val card = currentCard ?: return
        val isFully = com.example.animetv.core.history.WatchedEpisodeStore.isAnimeFullyWatched(this, card.detailUrl)
        btnToggleWatched.text = if (isFully) "✓" else "👁"
        btnToggleWatched.setTextColor(if (isFully) 0xFF81C784.toInt() else 0xFFFFFFFF.toInt())
    }

    /**
     * Toggles watched status for the entire show (all seasons and episodes), matching Stremio/Nuvio.
     */
    private fun toggleWatchedShow() {
        val card = currentCard ?: return
        val isFullyWatched = com.example.animetv.core.history.WatchedEpisodeStore.isAnimeFullyWatched(this, card.detailUrl)
        val target = !isFullyWatched
        com.example.animetv.core.history.WatchedEpisodeStore.setAnimeFullyWatched(this, card.detailUrl, rawEpisodes, target)

        val msg = if (target) {
            if (isCurrentMovie) "✓ Película marcada como vista" else "✓ Serie completa marcada como vista"
        } else {
            if (isCurrentMovie) "↩ Película marcada como no vista" else "↩ Serie marcada como no vista"
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

        refreshWatchedButtonState()
        episodeAdapter?.notifyDataSetChanged()
    }

    private fun bindInitialCard(card: AnimeCard) {
        imgDetailLogo.visibility = View.GONE
        txtTitle.visibility = View.VISIBLE
        txtTitle.text = card.title
        txtMeta.text = "${card.source}  •  ${card.episodeBadge.ifEmpty { "Serie" }}"
        val initScore = card.rating.replace(Regex("""[^\d.]"""), "")
        if (initScore.isNotEmpty() && initScore != "0.0") {
            txtDetailImdbScore.text = initScore
            layoutDetailImdb.visibility = View.VISIBLE
        } else {
            layoutDetailImdb.visibility = View.GONE
        }
        txtSynopsis.text = "Cargando detalles..."

        updateFavoriteButton(card)
        btnToggleFavorite.setOnClickListener {
            toggleFavorite(card)
        }
    }

    private fun updateFavoriteButton(card: AnimeCard) {
        val isFav = FavoritesStore.isFavorite(this, card.detailUrl)
        btnToggleFavorite.text = if (isFav) "✓" else "+"
        btnToggleFavorite.setTextColor(if (isFav) 0xFF81C784.toInt() else 0xFFFFFFFF.toInt())
    }

    private fun toggleFavorite(card: AnimeCard) {
        val isFav = FavoritesStore.isFavorite(this, card.detailUrl)
        if (isFav) {
            FavoritesStore.remove(this, card.detailUrl)
            Toast.makeText(this, "Eliminado de Mi Lista", Toast.LENGTH_SHORT).show()
        } else {
            val bestPoster = CoverUtils.pickBestCover(currentDetail?.posterUrl, card.posterUrl)
            val fav = FavoriteItem(
                url = card.detailUrl,
                title = card.title,
                poster = bestPoster,
                source = card.source
            )
            FavoritesStore.add(this, fav)
            Toast.makeText(this, "Añadido a Mi Lista", Toast.LENGTH_SHORT).show()
        }
        updateFavoriteButton(card)
    }


    private fun loadDetail(card: AnimeCard) {
        progressBar.visibility = View.VISIBLE

        // Fetch the scraper's own detail page once and share it between both consumers below.
        // `detail.title` (scraped fresh from the actual show page) is authoritative — unlike
        // `card.title`, which comes from a listing/grid row and can carry a stale or mismatched
        // alt-text from the source site. Searching TMDB with a wrong card.title was the root
        // cause of a show's synopsis/poster/torrent search occasionally resolving to a
        // completely unrelated title (e.g. a Dragon Ball movie's data on One Punch Man's page).
        val detailDeferred = lifecycleScope.async(Dispatchers.IO) {
            try {
                CatalogRepository.getAnimeDetail(card)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }

        // Asynchronously fetch TMDB metadata & high-resolution artwork. When the scraped page
        // carries an exact TMDB id (from its own JSON-LD), use it directly instead of guessing
        // from a title — that's what correctly tells apart e.g. One Piece from its live-action
        // adaptation, or Dragon Ball from Z/GT, which a fuzzy title search can't reliably do.
        lifecycleScope.launch {
            try {
                val detail = detailDeferred.await()
                val isMovie = card.detailUrl.contains("/pelicula/") || card.episodeBadge.equals("Película", ignoreCase = true)
                val isLiveAction = card.source.contains("SoloLatino", ignoreCase = true) && !card.detailUrl.contains("/animes")
                val tmdb = if (detail != null && detail.tmdbId > 0 && detail.tmdbMediaType.isNotEmpty()) {
                    TmdbMetadataRepository.getMetadataByTmdbId(this@DetailActivity, detail.tmdbId, detail.tmdbMediaType)
                } else {
                    val rawTitleForTmdb = detail?.title?.takeIf { it.isNotBlank() && it != "Anime" } ?: card.title
                    val titleForTmdb = TmdbMetadataRepository.sanitizeTitle(rawTitleForTmdb)
                    TmdbMetadataRepository.searchMetadata(
                        this@DetailActivity,
                        titleForTmdb,
                        isMovie = isMovie,
                        isLiveAction = isLiveAction
                    )
                }
                if (tmdb != null) {
                    currentTmdbMeta = tmdb
                    val bestBackdrop = if (tmdb.backdropUrl.isNotEmpty()) tmdb.backdropUrl else tmdb.posterUrl
                    if (bestBackdrop.isNotEmpty()) {
                        Glide.with(this@DetailActivity)
                            .load(bestBackdrop)
                            .centerCrop()
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                            .into(imgBackdrop)
                    }

                    if (tmdb.logoUrl.isNotEmpty()) {
                        Glide.with(this@DetailActivity)
                            .load(tmdb.logoUrl)
                            .fitCenter()
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                            .into(imgDetailLogo)
                        imgDetailLogo.visibility = View.VISIBLE
                        txtTitle.visibility = View.GONE
                    } else {
                        imgDetailLogo.visibility = View.GONE
                        txtTitle.visibility = View.VISIBLE
                    }

                    // TMDB's overview is authoritative and always wins over the scraper's synopsis
                    if (tmdb.overview.isNotEmpty()) {
                        txtSynopsis.text = tmdb.overview
                    }

                    // Genre(s) • Year — mirrors Nuvio's top metadata line
                    val metaParts = mutableListOf<String>()
                    if (tmdb.genres.isNotEmpty()) metaParts.add(tmdb.genres.take(3).joinToString(", "))
                    if (tmdb.releaseYear.isNotEmpty()) metaParts.add(tmdb.releaseYear)
                    if (metaParts.isNotEmpty()) {
                        txtMeta.text = metaParts.joinToString("  •  ")
                    }

                    // Yellow IMDb badge (matching Home & Nuvio)
                    val imdbScore = when {
                        tmdb.voteAverage > 0 -> String.format(Locale.US, "%.1f", tmdb.voteAverage)
                        tmdb.ratingText.isNotEmpty() -> tmdb.ratingText.replace(Regex("""[^\d.]"""), "")
                        currentCard?.rating?.isNotEmpty() == true -> currentCard!!.rating.replace(Regex("""[^\d.]"""), "")
                        else -> ""
                    }
                    if (imdbScore.isNotEmpty() && imdbScore != "0.0") {
                        txtDetailImdbScore.text = imdbScore
                        layoutDetailImdb.visibility = View.VISIBLE
                    } else {
                        layoutDetailImdb.visibility = View.GONE
                    }

                    // Certification • Runtime
                    val badgeParts = mutableListOf<String>()
                    if (tmdb.certification.isNotEmpty()) badgeParts.add(tmdb.certification)
                    if (tmdb.runtimeMinutes > 0) {
                        val h = tmdb.runtimeMinutes / 60
                        val m = tmdb.runtimeMinutes % 60
                        badgeParts.add(if (h > 0) "${h}h ${m}min" else "${m}min")
                    }
                    if (badgeParts.isNotEmpty()) {
                        txtMovieBadges.text = badgeParts.joinToString("  •  ")
                        txtMovieBadges.visibility = View.VISIBLE
                    }

                    val creditsParts = mutableListOf<String>()
                    if (tmdb.director.isNotEmpty()) {
                        val roleLabel = if (tmdb.mediaType == "tv") "Creador" else "Director"
                        creditsParts.add("$roleLabel: ${tmdb.director}")
                    }
                    if (tmdb.cast.isNotEmpty()) creditsParts.add("Reparto: ${tmdb.cast.take(3).joinToString(", ")}")
                    if (creditsParts.isNotEmpty()) {
                        txtMovieCredits.text = creditsParts.joinToString("  |  ")
                        txtMovieCredits.visibility = View.VISIBLE
                    }

                val bestTitle = tmdb.bestTitle.takeIf { it.isNotBlank() && !it.equals("Anime", ignoreCase = true) }
                if (!bestTitle.isNullOrBlank()) {
                    val currentTxt = txtTitle.text.toString()
                    if (currentTxt.equals("Anime", ignoreCase = true) ||
                        currentTxt.isBlank() ||
                        currentTxt.contains("online", ignoreCase = true) ||
                        currentTxt.contains("jkanime", ignoreCase = true)
                    ) {
                        txtTitle.text = bestTitle
                    }
                }
                enrichEpisodesWithTmdb(tmdb)
                if (tmdb.trailerUrl.isNotEmpty()) {
                    setupTrailerButton(tmdb.trailerUrl, bestTitle ?: currentDetail?.title ?: card.title)
                }
                // Apply TMDB-derived season capsules universally
                applyTmdbSeasonCapsules(tmdb, card)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    lifecycleScope.launch {
        try {
            val detail = detailDeferred.await() ?: run {
                progressBar.visibility = View.GONE
                return@launch
            }
            currentDetail = detail
            rawEpisodes = detail.episodes
            isCurrentMovie = card.detailUrl.contains("/pelicula/")
            progressBar.visibility = View.GONE

            val bestPoster = CoverUtils.pickBestCover(currentTmdbMeta?.posterUrl ?: detail.posterUrl, card.posterUrl)
            if (bestPoster.isNotEmpty() && (currentTmdbMeta == null || currentTmdbMeta?.posterUrl.isNullOrEmpty())) {
                Glide.with(this@DetailActivity)
                    .load(bestPoster)
                    .centerCrop()
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .into(imgBackdrop)
            }

            val rawResolvedTitle = detail.title.takeIf { it.isNotBlank() && !it.equals("Anime", ignoreCase = true) }
                ?: currentTmdbMeta?.bestTitle?.takeIf { it.isNotBlank() && !it.equals("Anime", ignoreCase = true) }
                ?: card.title.takeIf { it.isNotBlank() && !it.equals("Anime", ignoreCase = true) }
                ?: detail.title
            val resolvedTitle = TmdbMetadataRepository.sanitizeTitle(rawResolvedTitle).ifEmpty { rawResolvedTitle }
            txtTitle.text = resolvedTitle
                // TMDB's genre/year/rating line (built in the parallel TMDB lookup above) is more
                // complete than what the scraper alone provides — only fall back to the scraper's
                // own genres here if that lookup hasn't resolved yet or came up empty.
                if (currentTmdbMeta == null) {
                    val genresStr = if (detail.genres.isNotEmpty()) detail.genres.take(3).joinToString(", ") else "Anime"
                    txtMeta.text = genresStr
                    val fallbackScore = card.rating.replace(Regex("""[^\d.]"""), "")
                    if (fallbackScore.isNotEmpty() && fallbackScore != "0.0") {
                        txtDetailImdbScore.text = fallbackScore
                        layoutDetailImdb.visibility = View.VISIBLE
                    }
                }
                if (currentTmdbMeta == null || currentTmdbMeta?.overview.isNullOrEmpty()) {
                    txtSynopsis.text = detail.synopsis.ifEmpty { "Sin sinopsis disponible." }
                }


                // Trailer Button
                val effectiveTrailer = detail.trailerUrl.ifEmpty { currentTmdbMeta?.trailerUrl ?: "" }
                setupTrailerButton(effectiveTrailer, detail.title)

                // Movies never need the S01E01-style episode UI
                recyclerEpisodes.visibility = if (isCurrentMovie) View.GONE else View.VISIBLE

                if (detail.episodes.isNotEmpty()) {
                    val uniqueSeasons = detail.episodes.map { if (it.seasonNumber > 0) it.seasonNumber else 1 }.distinct().sorted()
                    val targetRes = resolveActiveOrNextEpisode()
                    val targetSeason = targetRes?.targetSeason ?: uniqueSeasons.firstOrNull() ?: 1

                    if (uniqueSeasons.isNotEmpty()) {
                        selectedSeason = if (uniqueSeasons.contains(targetSeason)) targetSeason else uniqueSeasons.first()

                        recyclerSeasons.visibility = View.VISIBLE
                        val sAdapter = SeasonCapsuleAdapter(uniqueSeasons, selectedSeason) { chosenSeason ->
                            selectedSeason = chosenSeason
                            displayEpisodesForSeason(chosenSeason)
                        }
                        seasonAdapter = sAdapter
                        recyclerSeasons.adapter = sAdapter
                        val sPos = uniqueSeasons.indexOf(selectedSeason)
                        if (sPos > 0) {
                            recyclerSeasons.scrollToPosition(sPos)
                        }
                    }

                    val initialEpisodes = if (uniqueSeasons.size > 1) {
                        detail.episodes.filter { (if (it.seasonNumber > 0) it.seasonNumber else 1) == selectedSeason }
                    } else {
                        detail.episodes
                    }
                    val sortedList = getSortedEpisodes(initialEpisodes, isAscendingOrder)
                    val showPoster = CoverUtils.pickBestCover(currentTmdbMeta?.posterUrl ?: detail.posterUrl, card.posterUrl)
                    val record = com.example.animetv.core.history.PlaybackHistoryStore.getRecordForAnime(this@DetailActivity, card.detailUrl)
                    val adapter = EpisodeAdapter(
                        episodes = sortedList,
                        animeDetailUrl = card.detailUrl,
                        showPosterUrl = showPoster,
                        lastWatchedRecord = record,
                        onEpisodeFocus = { ep ->
                            bindFocusedEpisode(ep)
                        },
                        onEpisodeClick = { ep ->
                            playEpisode(detail, ep)
                        },
                        onEpisodeTorrentClick = { ep ->
                            showTorrentSelectorDialog(ep)
                        },
                        onEpisodeOptionsClick = { ep ->
                            showEpisodeOptionsDialog(ep)
                        },
                        onWatchedChanged = {
                            refreshWatchedButtonState()
                            refreshPlaybackState()
                        }
                    )
                    episodeAdapter = adapter
                    recyclerEpisodes.adapter = adapter
                    if (sortedList.isNotEmpty()) {
                        val targetEp = targetRes?.episode
                        val resumeIndex = if (targetEp != null) {
                            sortedList.indexOfFirst {
                                (it.episodeUrl.isNotEmpty() && it.episodeUrl == targetEp.episodeUrl) ||
                                (it.episodeNumber == targetEp.episodeNumber)
                            }.takeIf { it >= 0 } ?: 0
                        } else {
                            0
                        }
                        bindFocusedEpisode(sortedList[resumeIndex])
                        if (resumeIndex > 0) {
                            recyclerEpisodes.scrollToPosition(resumeIndex)
                        }
                    }

                    refreshPlaybackState()
                    btnPlayFirst.requestFocus()
                } else {
                    btnPlayFirst.text = if (detail.detailUrl.contains("/pelicula/")) "▶ Película" else "▶ Reproducir"
                    btnPlayFirst.setOnClickListener {
                        playDirectUrl(detail.detailUrl, detail.title)
                    }
                }

                refreshWatchedButtonState()
                currentTmdbMeta?.let { enrichEpisodesWithTmdb(it) }
            } catch (e: Exception) {
                progressBar.visibility = View.GONE
                e.printStackTrace()
            }
        }
    }

    private fun setupTrailerButton(trailerUrl: String, title: String) {
        btnTrailer.visibility = View.VISIBLE
        btnTrailer.setOnClickListener {
            if (trailerUrl.isNotEmpty()) {
                PlayerActivity.start(
                    this@DetailActivity,
                    videoUrl = trailerUrl,
                    title = "Tráiler: $title",
                    isHls = false,
                    isEmbed = true,
                    referer = if (currentCard?.detailUrl?.isNotEmpty() == true) currentCard!!.detailUrl else "https://www.youtube.com"
                )
            } else {
                try {
                    val q = java.net.URLEncoder.encode("$title Trailer Oficial", "UTF-8")
                    val ytUri = Uri.parse("https://www.youtube.com/results?search_query=$q")
                    startActivity(Intent(Intent.ACTION_VIEW, ytUri))
                    Toast.makeText(this@DetailActivity, "Buscando tráiler en YouTube...", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this@DetailActivity, "No se pudo abrir YouTube", Toast.LENGTH_SHORT).show()
                }
            }
        }
        btnTrailer.setOnLongClickListener {
            val videoId = if (trailerUrl.contains("/embed/")) {
                trailerUrl.substringAfter("/embed/").substringBefore("?").substringBefore("/")
            } else {
                Regex("""(?:v=|youtu\.be/)([\w-]+)""").find(trailerUrl)?.groupValues?.get(1) ?: ""
            }
            if (videoId.isNotEmpty()) {
                try {
                    val ytUri = Uri.parse("https://www.youtube.com/watch?v=$videoId")
                    startActivity(Intent(Intent.ACTION_VIEW, ytUri))
                    Toast.makeText(this@DetailActivity, "Abriendo en YouTube...", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this@DetailActivity, "No se pudo abrir YouTube", Toast.LENGTH_SHORT).show()
                }
            } else {
                try {
                    val q = java.net.URLEncoder.encode("$title Trailer Oficial", "UTF-8")
                    val ytUri = Uri.parse("https://www.youtube.com/results?search_query=$q")
                    startActivity(Intent(Intent.ACTION_VIEW, ytUri))
                } catch (e: Exception) {}
            }
            true
        }
    }

    private fun showEpisodeOptionsDialog(ep: AnimeEpisode) {
        val sNum = if (ep.seasonNumber > 0) ep.seasonNumber else selectedSeason
        val eNum = ep.episodeNumber
        val cleanTitle = ep.title
            .replace(Regex("""^(?:Episodio|Episode|Capítulo|Capitulo|Cap\.?|Ep\.?)\s*\d+[\s:\.\-–—]*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""^\d+[\s:\.\-–—]+"""), "")
            .trim()
        val displayTitle = if (cleanTitle.isNotEmpty()) "T$sNum E$eNum • $cleanTitle" else "Temporada $sNum • Episodio $eNum"
        val isWatched = WatchedEpisodeStore.isEpisodeWatched(this, currentCard?.detailUrl ?: "", sNum, eNum, ep.episodeUrl)
        val watchedLabel = if (isWatched) "↩ Marcar como No Visto" else "✓ Marcar como Visto"

        val options = arrayOf(
            "🌐 Reproducir WEB (HTTP)",
            "⚡ Reproducir con Torrent",
            "↺ Reiniciar desde el inicio",
            watchedLabel
        )

        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle(displayTitle)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        currentDetail?.let { playEpisode(it, ep, startOver = false) }
                            ?: currentCard?.let { playEpisodeDirect(it, ep, startOver = false) }
                    }
                    1 -> {
                        showTorrentSelectorDialog(ep)
                    }
                    2 -> {
                        currentDetail?.let { playEpisode(it, ep, startOver = true) }
                            ?: currentCard?.let { playEpisodeDirect(it, ep, startOver = true) }
                    }
                    3 -> {
                        val nowWatched = WatchedEpisodeStore.toggleEpisodeWatched(
                            this, currentCard?.detailUrl ?: "", sNum, eNum, ep.episodeUrl
                        )
                        val msg = if (nowWatched) "✓ Episodio $eNum marcado como visto" else "↩ Episodio $eNum marcado como no visto"
                        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                        val pos = episodeAdapter?.getEpisodePosition(ep) ?: -1
                        if (pos >= 0) {
                            episodeAdapter?.notifyItemChanged(pos)
                            recyclerEpisodes.post {
                                recyclerEpisodes.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus()
                            }
                        } else {
                            episodeAdapter?.notifyDataSetChanged()
                        }
                        refreshWatchedButtonState()
                        refreshPlaybackState()
                    }
                }
            }
            .show()
    }

    private fun enrichEpisodesWithTmdb(tmdb: TmdbMetadata) {
        if (rawEpisodes.isEmpty()) return
        lifecycleScope.launch {
            try {
                val hasMultipleSeasonsInRaw = rawEpisodes.any { it.seasonNumber > 1 }
                val seasons = rawEpisodes.map { if (it.seasonNumber > 0) it.seasonNumber else 1 }.distinct()
                val tmdbEpisodesMap = mutableMapOf<Pair<Int, Int>, com.example.animetv.core.tmdb.TmdbEpisode>()

                val deferreds = seasons.map { sNum ->
                    async(Dispatchers.IO) {
                        try {
                            TmdbMetadataRepository.getSeasonEpisodes(this@DetailActivity, tmdb.tmdbId, sNum)
                        } catch (e: Exception) {
                            emptyList()
                        }
                    }
                }
                val results = deferreds.awaitAll()
                for (eps in results) {
                    for (ep in eps) {
                        tmdbEpisodesMap[Pair(ep.seasonNumber, ep.episodeNumber)] = ep
                    }
                }

                if (tmdbEpisodesMap.isNotEmpty()) {
                    rawEpisodes = rawEpisodes.map { ep ->
                        val effectiveSeason = if (ep.seasonNumber > 0) ep.seasonNumber else 1
                        // CRITICAL: For shows with multiple seasons (e.g. Adventure Time), NEVER fallback
                        // to Season 1! Only fall back to Season 1 if the scraper provided all episodes under Season 1.
                        val tEp = tmdbEpisodesMap[Pair(effectiveSeason, ep.episodeNumber)]
                            ?: if (!hasMultipleSeasonsInRaw) tmdbEpisodesMap[Pair(1, ep.episodeNumber)] else null

                        if (tEp != null) {
                            val enrichedTitle = if (tEp.name.isNotEmpty() && !tEp.name.equals("Episodio ${ep.episodeNumber}", true)) {
                                "${ep.episodeNumber}. ${tEp.name}"
                            } else ep.title

                            ep.copy(
                                title = enrichedTitle,
                                synopsis = tEp.overview.ifEmpty { ep.synopsis },
                                releaseDate = tEp.airDate.ifEmpty { ep.releaseDate },
                                stillUrl = tEp.stillUrl.ifEmpty { ep.stillUrl }
                            )
                        } else {
                            ep
                        }
                    }

                    displayEpisodesForSeason(selectedSeason)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Applies season capsules universally using TMDB metadata.
     * Called after TMDB search resolves. If the show has multiple seasons per TMDB
     * but the scraper only loaded one season's episodes, we still display all season
     * capsules so the user can navigate between them.
     */
    private fun applyTmdbSeasonCapsules(tmdb: TmdbMetadata, card: AnimeCard) {
        val nSeasons = tmdb.numberOfSeasons
        if (nSeasons <= 1) return
        tmdbSeasonCount = nSeasons

        // Only apply if the episode adapter already shows a single-season list
        // (i.e., the scraper didn't already produce multi-season capsules)
        val currentCapsuleCount = seasonAdapter?.itemCount ?: 0
        if (currentCapsuleCount > 1) return // multi-season already shown by scraper data

        val seasonList = (1..nSeasons).toList()
        selectedSeason = selectedSeason.coerceIn(1, nSeasons)

        recyclerSeasons.visibility = View.VISIBLE
        val sAdapter = SeasonCapsuleAdapter(seasonList, selectedSeason) { chosenSeason ->
            selectedSeason = chosenSeason
            // If we have scraped episodes for this season, show them directly
            val scrapedForSeason = rawEpisodes.filter { (if (it.seasonNumber > 0) it.seasonNumber else 1) == chosenSeason }
            if (scrapedForSeason.isNotEmpty()) {
                displayEpisodesForSeason(chosenSeason)
            } else {
                // Load TMDB stubs for this season since the scraper doesn't have them
                loadTmdbStubsForSeason(tmdb, card, chosenSeason)
            }
        }
        seasonAdapter = sAdapter
        recyclerSeasons.adapter = sAdapter
        val sPos = seasonList.indexOf(selectedSeason)
        if (sPos > 0) {
            recyclerSeasons.scrollToPosition(sPos)
        }
    }

    /**
     * When no scraped episodes exist for a season, load TMDB episode data and display as stubs.
     * These stubs show title, synopsis, and still images from TMDB but clicking will attempt
     * to play via the current scraper source.
     */
    private fun loadTmdbStubsForSeason(tmdb: TmdbMetadata, card: AnimeCard, season: Int) {
        lifecycleScope.launch {
            try {
                progressBar.visibility = View.VISIBLE
                val tmdbEps = TmdbMetadataRepository.getSeasonEpisodes(this@DetailActivity, tmdb.tmdbId, season)
                progressBar.visibility = View.GONE

                if (tmdbEps.isEmpty()) {

                    episodeAdapter?.updateList(emptyList())
                    return@launch
                }

                // Create stub AnimeEpisodes from TMDB data
                val stubs = tmdbEps.map { tEp ->
                    AnimeEpisode(
                        episodeNumber = tEp.episodeNumber,
                        seasonNumber = season,
                        title = if (tEp.name.isNotEmpty()) "${tEp.episodeNumber}. ${tEp.name}" else "Episodio ${tEp.episodeNumber}",
                        episodeUrl = "", // No scraper URL — will trigger toast
                        synopsis = tEp.overview,
                        stillUrl = tEp.stillUrl,
                        releaseDate = tEp.airDate
                    )
                }

                val record = card.let { com.example.animetv.core.history.PlaybackHistoryStore.getRecordForAnime(this@DetailActivity, it.detailUrl) }
                val detail = currentDetail
                if (detail != null) {
                    // Wire up a fresh adapter if the season changes
                    val showPoster = CoverUtils.pickBestCover(currentTmdbMeta?.posterUrl ?: detail.posterUrl, card.posterUrl)
                    val adapter = EpisodeAdapter(
                        episodes = stubs,
                        animeDetailUrl = card.detailUrl,
                        showPosterUrl = showPoster,
                        lastWatchedRecord = record,
                        onEpisodeFocus = { ep -> bindFocusedEpisode(ep) },
                        onEpisodeClick = { ep ->
                            if (ep.episodeUrl.isEmpty()) {
                                Toast.makeText(this@DetailActivity, "Episodio no disponible en esta fuente para T$season", Toast.LENGTH_SHORT).show()
                            } else {
                                playEpisode(detail, ep)
                            }
                        },
                        onEpisodeTorrentClick = { ep ->
                            showTorrentSelectorDialog(ep)
                        },
                        onEpisodeOptionsClick = { ep ->
                            showEpisodeOptionsDialog(ep)
                        },
                        onWatchedChanged = {
                            refreshWatchedButtonState()
                        }
                    )
                    episodeAdapter = adapter
                    recyclerEpisodes.adapter = adapter
                    if (stubs.isNotEmpty()) bindFocusedEpisode(stubs[0])
                }


                recyclerEpisodes.scrollToPosition(0)
            } catch (e: Exception) {
                progressBar.visibility = View.GONE
                e.printStackTrace()
            }
        }
    }

    private fun playEpisode(detail: AnimeDetail, episode: AnimeEpisode, startOver: Boolean = false) {
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                Toast.makeText(this@DetailActivity, "Conectando al reproductor...", Toast.LENGTH_SHORT).show()
                val bestPoster = CoverUtils.pickBestCover(detail.posterUrl, currentCard?.posterUrl)

                // Resolve ALL ranked streams from all sources in parallel
                val streams = try {
                    CatalogRepository.resolveAllStreams(this@DetailActivity, episode.episodeUrl, detail.source)
                } catch (e: Exception) {
                    e.printStackTrace()
                    emptyList()
                }
                progressBar.visibility = View.GONE

                val bestStream = streams.firstOrNull()
                if (bestStream != null && bestStream.videoUrl.isNotEmpty()) {
                    PlayerActivity.start(
                        this@DetailActivity,
                        videoUrl = bestStream.videoUrl,
                        title = "${detail.title} - ${episode.title}",
                        isHls = bestStream.isHls,
                        isEmbed = bestStream.isEmbed,
                        referer = bestStream.headers["Referer"] ?: "",
                        animeDetailUrl = detail.detailUrl,
                        animeTitle = detail.title,
                        posterUrl = bestPoster,
                        source = detail.source,
                        episodeUrl = episode.episodeUrl,
                        episodeTitle = episode.title,
                        episodeNumber = episode.episodeNumber,
                        seasonNumber = episode.seasonNumber,
                        startOver = startOver,
                        synopsis = detail.synopsis,
                        availableStreams = ArrayList(streams)
                    )
                } else {
                    progressBar.visibility = View.GONE
                    Toast.makeText(this@DetailActivity, "No se encontró enlace de video disponible para este episodio", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                progressBar.visibility = View.GONE
                Toast.makeText(this@DetailActivity, "Error al conectar con reproductor: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun playEpisodeDirect(card: AnimeCard, episode: AnimeEpisode, startOver: Boolean = false) {
        val dummyDetail = AnimeDetail(
            title = card.title,
            posterUrl = card.posterUrl,
            synopsis = card.synopsis,
            source = card.source,
            detailUrl = card.detailUrl,
            episodes = rawEpisodes
        )
        playEpisode(dummyDetail, episode, startOver)
    }

    private fun playDirectUrl(url: String, title: String) {
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val bestPoster = CoverUtils.pickBestCover(currentDetail?.posterUrl, currentCard?.posterUrl)
            val resolvedShowTitle = when {
                currentDetail?.title?.isNotEmpty() == true && currentDetail!!.title != "Película Completa" -> currentDetail!!.title
                currentCard?.title?.isNotEmpty() == true && currentCard!!.title != "Película Completa" -> currentCard!!.title
                title.isNotEmpty() && title != "Película Completa" -> title
                else -> "Película"
            }
            val resolvedSynopsis = currentDetail?.synopsis?.ifEmpty { currentCard?.synopsis ?: "" } ?: ""
            try {
                val sourceName = currentCard?.source ?: currentDetail?.source ?: ""
                val stream = if (sourceName.isNotEmpty()) {
                    CatalogRepository.resolveStream(this@DetailActivity, url, sourceName)
                } else null
                progressBar.visibility = View.GONE
                if (stream != null && stream.videoUrl.isNotEmpty()) {
                    PlayerActivity.start(
                        this@DetailActivity,
                        videoUrl = stream.videoUrl,
                        title = resolvedShowTitle,
                        isHls = stream.isHls,
                        isEmbed = stream.isEmbed,
                        referer = stream.headers["Referer"] ?: "",
                        animeDetailUrl = currentCard?.detailUrl ?: url,
                        animeTitle = resolvedShowTitle,
                        posterUrl = bestPoster,
                        source = sourceName,
                        episodeUrl = url,
                        episodeTitle = "Película",
                        episodeNumber = 1,
                        synopsis = resolvedSynopsis
                    )
                    return@launch
                }
            } catch (e: Exception) {
                progressBar.visibility = View.GONE
                e.printStackTrace()
            }
            progressBar.visibility = View.GONE
            Toast.makeText(this@DetailActivity, "No se encontró enlace de video disponible para esta película", Toast.LENGTH_LONG).show()
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        val m = (totalSec / 60) % 60
        val s = totalSec % 60
        val h = totalSec / 3600
        return if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s) else String.format(java.util.Locale.US, "%02d:%02d", m, s)
    }

    private fun showEpisodeChoiceDialog(detail: AnimeDetail, episode: AnimeEpisode) {
        val sNum = if (episode.seasonNumber > 0) episode.seasonNumber else 1
        val eNum = episode.episodeNumber
        val titleText: CharSequence = String.format(Locale.US, "S%02dE%02d", sNum, eNum)
        val msgText: CharSequence = if (episode.title.isNotEmpty()) episode.title else "¿Cómo deseas reproducir este capítulo?"

        AlertDialog.Builder(this)
            .setTitle(titleText)
            .setMessage(msgText)
            .setPositiveButton("▶ WEB") { _, _ ->
                playEpisode(detail, episode)
            }
            .setNeutralButton("⚡ TOR") { _, _ ->
                showTorrentSelectorDialog(episode)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showTorrentSelectorDialog(targetEpisode: AnimeEpisode? = null) {
        val card = currentCard ?: return
        val detail = currentDetail

        val isMovie = card.detailUrl.contains("/pelicula/") || (detail != null && detail.episodes.isEmpty())
        val targetSeason = targetEpisode?.let { if (it.seasonNumber > 0) it.seasonNumber else 1 } ?: selectedSeason
        val seasonEpisodes = if (rawEpisodes.any { it.seasonNumber > 1 }) {
            rawEpisodes.filter { (if (it.seasonNumber > 0) it.seasonNumber else 1) == targetSeason }
        } else {
            rawEpisodes
        }
        val sortedList = getSortedEpisodes(seasonEpisodes, isAscendingOrder)

        var currentEpIndex = if (targetEpisode != null) {
            val idx = sortedList.indexOfFirst {
                it.episodeUrl == targetEpisode.episodeUrl ||
                        (it.seasonNumber == targetEpisode.seasonNumber && it.episodeNumber == targetEpisode.episodeNumber) ||
                        (it.episodeNumber == targetEpisode.episodeNumber && it.seasonNumber == targetEpisode.seasonNumber)
            }
            if (idx >= 0) idx else 0
        } else {
            val focused = currentlyFocusedEpisode
            if (focused != null) {
                val idx = sortedList.indexOfFirst {
                    it.episodeUrl == focused.episodeUrl ||
                            (it.seasonNumber == focused.seasonNumber && it.episodeNumber == focused.episodeNumber)
                }
                if (idx >= 0) idx else 0
            } else {
                val record = com.example.animetv.core.history.PlaybackHistoryStore.getRecordForAnime(this, card.detailUrl)
                if (record != null) {
                    val idx = sortedList.indexOfFirst { it.episodeUrl == record.episodeUrl || (it.episodeNumber == record.episodeNumber && it.seasonNumber == targetSeason) }
                    if (idx >= 0) idx else 0
                } else 0
            }
        }
        if (currentEpIndex < 0 || (sortedList.isNotEmpty() && currentEpIndex >= sortedList.size)) currentEpIndex = 0

        var currentEp = if (sortedList.isNotEmpty()) sortedList[currentEpIndex] else targetEpisode

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_torrent_selector, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val txtHeader = dialogView.findViewById<TextView>(R.id.txtDialogTorrentHeader)
        val txtSubtitle = dialogView.findViewById<TextView>(R.id.txtDialogTorrentSubtitle)
        val btnClose = dialogView.findViewById<Button>(R.id.btnDialogClose)
        val btnSettings = dialogView.findViewById<Button>(R.id.btnTorrentQuickSettings)
        val progressBar = dialogView.findViewById<ProgressBar>(R.id.progressBarTorrents)
        val txtEmpty = dialogView.findViewById<TextView>(R.id.txtEmptyTorrents)
        val recycler = dialogView.findViewById<RecyclerView>(R.id.recyclerTorrents)
        val scrollProviderFilters = dialogView.findViewById<HorizontalScrollView>(R.id.scrollProviderFilters)
        val rgProviderFilters = dialogView.findViewById<RadioGroup>(R.id.rgProviderFilters)

        val layoutEpisodeNavigator = dialogView.findViewById<View>(R.id.layoutEpisodeNavigator)
        val btnTorrentEpFirst = dialogView.findViewById<Button>(R.id.btnTorrentEpFirst)
        val btnTorrentEpPrev = dialogView.findViewById<Button>(R.id.btnTorrentEpPrev)
        val txtTorrentCurrentEp = dialogView.findViewById<TextView>(R.id.txtTorrentCurrentEp)
        val btnTorrentEpNext = dialogView.findViewById<Button>(R.id.btnTorrentEpNext)
        val btnTorrentEpLast = dialogView.findViewById<Button>(R.id.btnTorrentEpLast)

        // Prefer the freshly-scraped detail page title over card.title: the latter comes from a
        // listing/grid row and can carry stale/mismatched alt-text from the source site, which
        // was feeding the wrong show's title into both the TMDB re-lookup and the torrent query
        // below (e.g. Dragon Ball torrents showing up for One Punch Man).
        val titleDisplay = detail?.title?.takeIf { it.isNotBlank() && it != "Anime" } ?: card.title

        btnClose.setOnClickListener { dialog.dismiss() }

        recycler.layoutManager = LinearLayoutManager(this)
        val adapter = TorrentItemAdapter(emptyList()) { item ->
            handleTorrentSelection(dialog, item, card, detail, currentEp)
        }
        recycler.adapter = adapter

        var searchJob: kotlinx.coroutines.Job? = null

        fun searchEpisode(ep: AnimeEpisode?) {
            searchJob?.cancel()
            val seasonNum = if (ep?.seasonNumber != null && ep.seasonNumber > 0) ep.seasonNumber else 1
            val epNum = ep?.episodeNumber ?: 1
            val epCode = String.format(Locale.US, "S%02dE%02d", seasonNum, epNum)

            txtHeader.text = if (isMovie) "⚡ Torrents: $titleDisplay" else "⚡ Torrents: $titleDisplay ($epCode)"
            val qualityBadge = TorrentSettingsStore.getQualityFilter(this)
            val langFilter = TorrentSettingsStore.getLanguageFilter(this)
            val langText = when (langFilter) {
                "spanish_only" -> "Solo Español"
                "dual_audio" -> "Dual Audio"
                "sub_only" -> "Subtitulado"
                else -> "Español > Dual > Seeds"
            }
            val epExtraTitle = if (ep?.title?.isNotEmpty() == true && !ep.title.startsWith("Episodio", true)) " • ${ep.title}" else ""
            txtSubtitle.text = if (isMovie) "(Película) | $qualityBadge | $langText" else "$epCode$epExtraTitle | $qualityBadge | $langText"

            if (sortedList.isNotEmpty() && !isMovie) {
                layoutEpisodeNavigator.visibility = View.VISIBLE
                txtTorrentCurrentEp.text = "$epCode (${currentEpIndex + 1}/${sortedList.size})"
                btnTorrentEpFirst.isEnabled = currentEpIndex > 0
                btnTorrentEpPrev.isEnabled = currentEpIndex > 0
                btnTorrentEpNext.isEnabled = currentEpIndex < sortedList.size - 1
                btnTorrentEpLast.isEnabled = currentEpIndex < sortedList.size - 1
            } else {
                layoutEpisodeNavigator.visibility = View.GONE
            }

            scrollProviderFilters.visibility = View.GONE
            rgProviderFilters.removeAllViews()

            progressBar.visibility = View.VISIBLE
            txtEmpty.visibility = View.VISIBLE
            txtEmpty.text = "Buscando torrents exclusivos para ${if (isMovie) "película" else epCode}..."
            recycler.visibility = View.GONE

            searchJob = lifecycleScope.launch {
                try {
                    var origTitle = currentTmdbMeta?.titleOriginal ?: ""
                    var engTitle = currentTmdbMeta?.titleEnglish ?: ""
                    var isLiveAction = currentTmdbMeta?.let { !it.isAnimation } ?: false
                    var imdbId = currentTmdbMeta?.imdbId ?: ""

                    if (imdbId.isEmpty()) {
                        val fastTmdb = if (detail != null && detail.tmdbId > 0 && detail.tmdbMediaType.isNotEmpty()) {
                            TmdbMetadataRepository.getMetadataByTmdbId(this@DetailActivity, detail.tmdbId, detail.tmdbMediaType)
                        } else {
                            TmdbMetadataRepository.searchMetadata(this@DetailActivity, titleDisplay, isMovie = isMovie, isLiveAction = isLiveAction)
                        }
                        if (fastTmdb != null) {
                            currentTmdbMeta = fastTmdb
                            origTitle = fastTmdb.titleOriginal
                            engTitle = fastTmdb.titleEnglish
                            isLiveAction = !fastTmdb.isAnimation
                            imdbId = fastTmdb.imdbId
                        }
                    }

                    val results = TorrentSearchRepository.searchAndFilter(
                        context = this@DetailActivity,
                        query = titleDisplay,
                        originalQuery = origTitle,
                        englishQuery = engTitle,
                        imdbId = imdbId,
                        seasonNumber = seasonNum,
                        episodeNumber = epNum,
                        isMovie = isMovie,
                        isLiveAction = isLiveAction,
                        year = currentTmdbMeta?.releaseYear ?: ""
                    )

                    progressBar.visibility = View.GONE
                    if (results.isEmpty()) {
                        scrollProviderFilters.visibility = View.GONE
                        txtEmpty.visibility = View.VISIBLE
                        txtEmpty.text = "No se encontraron torrents para $epCode.\nPuedes verificar los ajustes de Jackett / Prowlarr o reproducir desde Web."
                        recycler.visibility = View.GONE
                    } else {
                        var activeProviderKey = "all"
                        fun applyProviderFilter() {
                            val filtered = if (activeProviderKey == "all") {
                                results
                            } else {
                                results.filter { getProviderKey(it) == activeProviderKey }
                            }
                            if (filtered.isEmpty()) {
                                txtEmpty.visibility = View.VISIBLE
                                txtEmpty.text = "No se encontraron torrents para este proveedor."
                                recycler.visibility = View.GONE
                            } else {
                                txtEmpty.visibility = View.GONE
                                recycler.visibility = View.VISIBLE
                                adapter.updateList(filtered)
                            }
                        }

                        val providerGroups = results.groupBy { getProviderKey(it) }
                        rgProviderFilters.removeAllViews()

                        if (providerGroups.size > 1) {
                            val density = resources.displayMetrics.density
                            fun dp(v: Int) = (v * density).toInt()

                            fun createProviderChip(text: String, tagValue: String, isChecked: Boolean): RadioButton {
                                return RadioButton(this@DetailActivity).apply {
                                    id = View.generateViewId()
                                    this.text = text
                                    tag = tagValue
                                    buttonDrawable = null
                                    background = ContextCompat.getDrawable(this@DetailActivity, R.drawable.bg_filter_chip_selector)
                                    setTextColor(ContextCompat.getColorStateList(this@DetailActivity, R.color.color_filter_chip_text))
                                    textSize = 12f
                                    typeface = Typeface.DEFAULT_BOLD
                                    gravity = Gravity.CENTER
                                    setPadding(dp(14), dp(6), dp(14), dp(6))
                                    isFocusable = true
                                    isFocusableInTouchMode = true
                                    val params = RadioGroup.LayoutParams(
                                        ViewGroup.LayoutParams.WRAP_CONTENT,
                                        dp(36)
                                    ).apply {
                                        if (rgProviderFilters.childCount > 0) {
                                            marginStart = dp(8)
                                        }
                                    }
                                    layoutParams = params
                                    this.isChecked = isChecked
                                }
                            }

                            // 1. All chip
                            val allRb = createProviderChip("🌟 Todos (${results.size})", "all", true)
                            rgProviderFilters.addView(allRb)

                            // 2. Individual provider chips
                            providerGroups.keys.sorted().forEach { pKey ->
                                val count = providerGroups[pKey]?.size ?: 0
                                val label = "${getProviderDisplayLabel(pKey)} ($count)"
                                val rb = createProviderChip(label, pKey, false)
                                rgProviderFilters.addView(rb)
                            }

                            rgProviderFilters.setOnCheckedChangeListener { _, checkedId ->
                                val checkedRb = dialogView.findViewById<RadioButton>(checkedId)
                                activeProviderKey = checkedRb?.tag as? String ?: "all"
                                applyProviderFilter()
                            }

                            scrollProviderFilters.visibility = View.VISIBLE
                        } else {
                            scrollProviderFilters.visibility = View.GONE
                        }

                        applyProviderFilter()
                        recycler.requestFocus()
                    }
                } catch (e: Exception) {
                    if (e !is kotlinx.coroutines.CancellationException) {
                        scrollProviderFilters.visibility = View.GONE
                        progressBar.visibility = View.GONE
                        txtEmpty.visibility = View.VISIBLE
                        txtEmpty.text = "Error al buscar torrents: ${e.message}"
                    }
                }
            }
        }

        btnTorrentEpFirst.setOnClickListener {
            if (sortedList.isNotEmpty() && currentEpIndex != 0) {
                currentEpIndex = 0
                currentEp = sortedList[0]
                searchEpisode(currentEp)
            }
        }

        btnTorrentEpPrev.setOnClickListener {
            if (currentEpIndex > 0) {
                currentEpIndex--
                currentEp = sortedList[currentEpIndex]
                searchEpisode(currentEp)
            }
        }

        btnTorrentEpNext.setOnClickListener {
            if (currentEpIndex < sortedList.size - 1) {
                currentEpIndex++
                currentEp = sortedList[currentEpIndex]
                searchEpisode(currentEp)
            }
        }

        btnTorrentEpLast.setOnClickListener {
            if (sortedList.isNotEmpty() && currentEpIndex != sortedList.size - 1) {
                currentEpIndex = sortedList.size - 1
                currentEp = sortedList[currentEpIndex]
                searchEpisode(currentEp)
            }
        }

        btnSettings.setOnClickListener {
            TorrentSettingsDialog.show(this@DetailActivity) {
                searchEpisode(currentEp)
            }
        }

        searchEpisode(currentEp)
        dialog.show()
        dialog.window?.setLayout(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun handleTorrentSelection(
        dialog: AlertDialog,
        item: TorrentStreamItem,
        card: AnimeCard,
        detail: AnimeDetail?,
        episode: AnimeEpisode?
    ) {
        val currentEp = episode ?: currentlyFocusedEpisode ?: rawEpisodes.firstOrNull()
        val sNum = currentEp?.seasonNumber?.takeIf { it > 0 } ?: selectedSeason
        val eNum = currentEp?.episodeNumber ?: 1
        val epUrl = currentEp?.episodeUrl ?: ""
        val title = "${card.title} - ${currentEp?.title ?: item.resolutionBadge}"
        val torrServerUrl = TorrentSettingsStore.getTorrServerUrl(this)

        lifecycleScope.launch {
            // 1. If a local TorrServer instance is already running (e.g. TorrServer APK on TV), use native ExoPlayer
            val isTorrServerAlive = TorrServerClient.isServerAlive(torrServerUrl)
            if (isTorrServerAlive) {
                Toast.makeText(this@DetailActivity, "Conectando con motor de streaming...", Toast.LENGTH_SHORT).show()
                val streamUrl = TorrServerClient.getStreamUrl(
                    serverUrl = torrServerUrl,
                    magnetUrl = item.magnetUrl,
                    title = title,
                    fileIndex = item.fileIndex
                )
                dialog.dismiss()

                val bestPoster = CoverUtils.pickBestCover(currentTmdbMeta?.posterUrl, detail?.posterUrl ?: card.posterUrl)
                PlayerActivity.start(
                    context = this@DetailActivity,
                    videoUrl = streamUrl,
                    title = title,
                    isHls = false,
                    isEmbed = false,
                    referer = "",
                    animeDetailUrl = card.detailUrl,
                    animeTitle = card.title,
                    posterUrl = bestPoster,
                    source = "Torrent (${item.provider})",
                    episodeUrl = epUrl.ifEmpty { item.magnetUrl },
                    episodeTitle = currentEp?.title ?: item.title,
                    episodeNumber = eNum,
                    seasonNumber = sNum,
                    startOver = false
                )
                return@launch
            }

            val targetMagnet = if (item.fileIndex > 0 && !item.magnetUrl.contains("&indices=") && !item.magnetUrl.contains("&so=")) {
                "${item.magnetUrl}&indices=${item.fileIndex - 1}"
            } else item.magnetUrl

            // 2. Zero-Server / Zero-PC mode: check if Nova Video Player is installed
            if (TorrServerClient.isNovaPlayerInstalled(this@DetailActivity)) {
                Toast.makeText(this@DetailActivity, "Iniciando streaming en Nova Video Player...", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                val bestPoster = CoverUtils.pickBestCover(currentTmdbMeta?.posterUrl, detail?.posterUrl ?: card.posterUrl)
                WatchedEpisodeStore.setEpisodeWatched(this@DetailActivity, card.detailUrl, sNum, eNum, epUrl, watched = true)
                PlaybackHistoryStore.saveProgress(
                    context = this@DetailActivity,
                    animeDetailUrl = card.detailUrl,
                    animeTitle = card.title,
                    posterUrl = bestPoster,
                    source = "Torrent (${item.provider})",
                    episodeUrl = epUrl.ifEmpty { item.magnetUrl },
                    episodeTitle = currentEp?.title ?: item.title,
                    episodeNumber = eNum,
                    seasonNumber = sNum,
                    positionMs = 1440_000L,
                    durationMs = 1440_000L,
                    synopsis = currentEp?.synopsis ?: detail?.synopsis ?: ""
                )
                refreshPlaybackState()
                TorrServerClient.launchNovaPlayer(this@DetailActivity, targetMagnet, title)
                return@launch
            }

            // 3. Check if VLC is installed
            if (TorrServerClient.isVlcInstalled(this@DetailActivity)) {
                showVlcOrNovaPromptDialog(item, title, targetMagnet, currentEp)
                return@launch
            }

            // 4. No torrent player installed: Guide user to install Nova Video Player from Google Play Store
            showInstallNovaPromptDialog()
        }
    }

    private fun showVlcOrNovaPromptDialog(item: TorrentStreamItem, title: String, targetMagnet: String = item.magnetUrl, episode: AnimeEpisode? = null) {
        val currentEp = episode ?: currentlyFocusedEpisode ?: rawEpisodes.firstOrNull()
        val sNum = currentEp?.seasonNumber?.takeIf { it > 0 } ?: selectedSeason
        val eNum = currentEp?.episodeNumber ?: 1
        val epUrl = currentEp?.episodeUrl ?: ""

        AlertDialog.Builder(this)
            .setTitle("🎬 Reproductor de Torrents")
            .setMessage("Se detectó VLC en este dispositivo.\n\nPara la mejor experiencia con motor BitTorrent integrado en la memoria, recomendamos Nova Video Player (gratuito y de código abierto).\n\n¿Cómo deseas reproducir?")
            .setPositiveButton("▶ Abrir en VLC") { _, _ ->
                val bestPoster = CoverUtils.pickBestCover(currentTmdbMeta?.posterUrl, currentDetail?.posterUrl ?: currentCard?.posterUrl ?: "")
                WatchedEpisodeStore.setEpisodeWatched(this, currentCard?.detailUrl ?: "", sNum, eNum, epUrl, watched = true)
                PlaybackHistoryStore.saveProgress(
                    context = this,
                    animeDetailUrl = currentCard?.detailUrl ?: "",
                    animeTitle = currentCard?.title ?: "",
                    posterUrl = bestPoster,
                    source = "Torrent (${item.provider})",
                    episodeUrl = epUrl.ifEmpty { item.magnetUrl },
                    episodeTitle = currentEp?.title ?: item.title,
                    episodeNumber = eNum,
                    seasonNumber = sNum,
                    positionMs = 1440_000L,
                    durationMs = 1440_000L,
                    synopsis = currentEp?.synopsis ?: currentDetail?.synopsis ?: ""
                )
                refreshPlaybackState()
                TorrServerClient.launchVlc(this, targetMagnet, title)
            }
            .setNeutralButton("📥 Instalar Nova Player") { _, _ ->
                TorrServerClient.openPlayStoreForNova(this)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showInstallNovaPromptDialog() {
        AlertDialog.Builder(this)
            .setTitle("🎬 Nova Video Player Necesario")
            .setMessage("Para reproducir torrents en 1080p sin necesidad de una computadora o servidor físico, necesitas el reproductor gratuito Nova Video Player (incluye motor de streaming integrado en la TV/teléfono).\n\n¿Deseas instalarlo gratis desde Google Play Store?")
            .setPositiveButton("📥 Instalar desde Google Play") { _, _ ->
                TorrServerClient.openPlayStoreForNova(this)
            }
            .setNeutralButton("⚙ Ajustes Avanzados") { _, _ ->
                TorrentSettingsDialog.show(this)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun getProviderKey(item: TorrentStreamItem): String {
        val p = item.provider.lowercase(Locale.ROOT)
        return when {
            p.startsWith("yts") -> "YTS"
            p.startsWith("torrentio") -> "Torrentio"
            p.startsWith("nyaa") -> "Nyaa"
            p.startsWith("animetosho") || p.startsWith("tosho") -> "AnimeTosho"
            p.startsWith("jackett") -> "Jackett"
            p.startsWith("prowlarr") -> "Prowlarr"
            else -> item.provider.split(" ").firstOrNull() ?: item.provider
        }
    }

    private fun getProviderDisplayLabel(key: String): String {
        return when (key) {
            "all" -> "🌟 Todos"
            "YTS" -> "🎬 YTS"
            "Torrentio" -> "⚡ Torrentio"
            "Nyaa" -> "🐱 Nyaa"
            "AnimeTosho" -> "📦 Tosho"
            "Jackett" -> "🔍 Jackett"
            "Prowlarr" -> "🔍 Prowlarr"
            else -> key
        }
    }

}
