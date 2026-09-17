package com.example.animetv.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import java.util.Locale
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.example.animetv.FavoriteItem
import com.example.animetv.FavoritesStore
import com.example.animetv.R
import com.example.animetv.core.CatalogRepository
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
import kotlinx.coroutines.launch

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
    private lateinit var imgPoster: ImageView
    private lateinit var txtTitle: TextView
    private lateinit var txtMeta: TextView
    private lateinit var txtSynopsis: TextView
    private lateinit var txtMovieBadges: TextView
    private lateinit var txtMovieCredits: TextView
    private lateinit var btnPlayFirst: Button
    private lateinit var btnRestartEpisode: Button
    private lateinit var btnPlayTorrent: Button
    private lateinit var btnTrailer: Button
    private lateinit var btnToggleFavorite: Button
    private lateinit var btnToggleWatched: Button
    // Icon-only/focus-reveal-label wrappers around the buttons above (same order as the row)
    private lateinit var actionPlayFirst: IconRevealButton
    private lateinit var actionRestart: IconRevealButton
    private lateinit var actionTorrent: IconRevealButton
    private lateinit var actionTrailer: IconDrawableRevealButton
    private lateinit var actionFavorite: IconRevealButton
    private lateinit var actionWatched: IconDrawableRevealButton
    private lateinit var layoutEpisodesHeaderRow: View
    private lateinit var txtEpisodesHeader: TextView
    private lateinit var btnSortOrder: Button
    private lateinit var btnJumpStart: Button
    private lateinit var btnJumpEnd: Button
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
        imgPoster = findViewById(R.id.imgDetailPoster)
        txtTitle = findViewById(R.id.txtDetailTitle)
        txtMeta = findViewById(R.id.txtDetailMeta)
        txtSynopsis = findViewById(R.id.txtDetailSynopsis)
        txtMovieBadges = findViewById(R.id.txtMovieBadges)
        txtMovieCredits = findViewById(R.id.txtMovieCredits)
        btnPlayFirst = findViewById(R.id.btnPlayFirst)
        btnRestartEpisode = findViewById(R.id.btnRestartEpisode)
        btnPlayTorrent = findViewById(R.id.btnPlayTorrent)
        btnTrailer = findViewById(R.id.btnTrailer)
        btnToggleFavorite = findViewById(R.id.btnToggleFavorite)
        btnToggleWatched = findViewById(R.id.btnToggleWatched)

        actionPlayFirst = IconRevealButton(btnPlayFirst, "▶", "WEB")
        actionTorrent = IconRevealButton(btnPlayTorrent, "⚡", "TOR")
        actionRestart = IconRevealButton(btnRestartEpisode, "↺", "Reiniciar")
        actionTrailer = IconDrawableRevealButton(btnTrailer, R.drawable.ic_movie, "Tráiler")
        actionFavorite = IconRevealButton(btnToggleFavorite, "+", "Mi Lista")
        actionWatched = IconDrawableRevealButton(btnToggleWatched, R.drawable.ic_eye, "Marcar Visto")

        btnPlayTorrent.setOnClickListener {
            val ep = currentlyFocusedEpisode ?: rawEpisodes.firstOrNull()
            showTorrentSelectorDialog(ep)
        }
        btnToggleWatched.setOnClickListener {
            toggleWatchedForCurrentSeason()
        }
        layoutEpisodesHeaderRow = findViewById(R.id.layoutEpisodesHeaderRow)
        txtEpisodesHeader = findViewById(R.id.txtEpisodesHeader)
        btnSortOrder = findViewById(R.id.btnSortOrder)
        btnJumpStart = findViewById(R.id.btnJumpStart)
        btnJumpEnd = findViewById(R.id.btnJumpEnd)
        recyclerSeasons = findViewById(R.id.recyclerSeasons)
        recyclerSeasons.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        recyclerEpisodes = findViewById(R.id.recyclerEpisodes)
        progressBar = findViewById(R.id.progressBarDetail)

        recyclerEpisodes.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)

        isAscendingOrder = com.example.animetv.core.history.UiPreferencesStore.isEpisodesAscending(this)
        btnSortOrder.text = if (isAscendingOrder) "⇄ Orden: 1 ➔ N" else "⇄ Orden: N ➔ 1"

        btnSortOrder.setOnClickListener {
            toggleSortOrder()
        }

        btnJumpStart.setOnClickListener {
            val count = episodeAdapter?.itemCount ?: 0
            if (count > 0) {
                recyclerEpisodes.smoothScrollToPosition(0)
            }
        }

        btnJumpEnd.setOnClickListener {
            val count = episodeAdapter?.itemCount ?: 0
            if (count > 0) {
                recyclerEpisodes.smoothScrollToPosition(count - 1)
            }
        }

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

    private fun refreshPlaybackState() {
        val card = currentCard ?: return
        val record = com.example.animetv.core.history.PlaybackHistoryStore.getRecordForAnime(this, card.detailUrl)
        
        if (record != null) {
            actionPlayFirst.label = "WEB (E${record.episodeNumber})"
            btnPlayFirst.setOnClickListener {
                // Play last watched episode with auto-resume
                val detail = currentDetail
                val ep = rawEpisodes.firstOrNull { it.episodeUrl == record.episodeUrl || it.episodeNumber == record.episodeNumber }
                    ?: AnimeEpisode(record.episodeNumber, 1, record.episodeTitle, record.episodeUrl)
                if (detail != null) {
                    playEpisode(detail, ep, startOver = false)
                } else {
                    playEpisodeDirect(card, ep, startOver = false)
                }
            }

            // Long-click on Continuar to restart from beginning
            btnPlayFirst.setOnLongClickListener {
                val detail = currentDetail
                val ep = rawEpisodes.firstOrNull { it.episodeUrl == record.episodeUrl || it.episodeNumber == record.episodeNumber }
                    ?: AnimeEpisode(record.episodeNumber, 1, record.episodeTitle, record.episodeUrl)
                Toast.makeText(this, "↺ Reiniciando ${ep.title} desde el inicio...", Toast.LENGTH_SHORT).show()
                if (detail != null) {
                    playEpisode(detail, ep, startOver = true)
                } else {
                    playEpisodeDirect(card, ep, startOver = true)
                }
                true
            }

            // Show dedicated Restart button if user has watched more than 5s
            if (record.positionMs > 5000) {
                btnRestartEpisode.visibility = View.VISIBLE
                actionRestart.label = "Reiniciar Ep"
                btnRestartEpisode.setOnClickListener {
                    val detail = currentDetail
                    val ep = rawEpisodes.firstOrNull { it.episodeUrl == record.episodeUrl || it.episodeNumber == record.episodeNumber }
                        ?: AnimeEpisode(record.episodeNumber, 1, record.episodeTitle, record.episodeUrl)
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
        } else if (rawEpisodes.isNotEmpty()) {
            val firstEp = rawEpisodes.first()
            actionPlayFirst.label = "WEB"
            btnPlayFirst.setOnLongClickListener(null)
            btnPlayFirst.setOnClickListener {
                currentDetail?.let { playEpisode(it, firstEp, startOver = false) }
            }
            btnRestartEpisode.visibility = View.GONE
        } else {
            actionPlayFirst.label = "WEB"
            btnRestartEpisode.visibility = View.GONE
        }

        actionTorrent.label = "TOR"

        episodeAdapter?.let { adapter ->
            val list = getSortedEpisodes(rawEpisodes, isAscendingOrder)
            adapter.updateList(list, record = record)
            if (list.isNotEmpty()) {
                bindFocusedEpisode(list[0])
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
     * Tracks which episode currently has D-pad focus, so the top WEB/TOR action buttons act on
     * it. Each episode card is now self-contained (still, title, synopsis) per the redesign, so
     * there's no separate "spotlight" panel left to populate here.
     */
    private fun bindFocusedEpisode(ep: AnimeEpisode) {
        currentlyFocusedEpisode = ep
    }

    private fun displayEpisodesForSeason(season: Int) {
        val seasonEpisodes = if (rawEpisodes.any { it.seasonNumber > 1 }) {
            rawEpisodes.filter { (if (it.seasonNumber > 0) it.seasonNumber else 1) == season }
        } else {
            rawEpisodes
        }
        val sortedList = getSortedEpisodes(seasonEpisodes, isAscendingOrder)
        val record = currentCard?.let { com.example.animetv.core.history.PlaybackHistoryStore.getRecordForAnime(this, it.detailUrl) }
        episodeAdapter?.updateList(sortedList, record = record)
        if (sortedList.isNotEmpty()) {
            // Prefer keeping the previously focused episode's position (e.g. after a sort
            // toggle); otherwise land on the last-watched episode for this season, if any.
            val focused = currentlyFocusedEpisode
            val focusedIndex = focused?.let { f ->
                sortedList.indexOfFirst { it.episodeUrl == f.episodeUrl || (it.seasonNumber == f.seasonNumber && it.episodeNumber == f.episodeNumber) }
            }?.takeIf { it >= 0 }
            val recordIndex = record?.let { rec ->
                sortedList.indexOfFirst { it.episodeUrl == rec.episodeUrl || it.episodeNumber == rec.episodeNumber }
            }?.takeIf { it >= 0 }
            val resumeIndex = focusedIndex ?: recordIndex ?: 0
            bindFocusedEpisode(sortedList[resumeIndex])
            recyclerEpisodes.scrollToPosition(resumeIndex)
            // scrollToPosition only scrolls — it never moves D-pad focus off whatever still has
            // it (e.g. the season capsule just pressed), so without this the remote keeps acting
            // on stale focus from the previous season instead of the new episode list.
            recyclerEpisodes.post {
                val holder = recyclerEpisodes.findViewHolderForAdapterPosition(resumeIndex) as? EpisodeAdapter.ViewHolder
                holder?.btnWeb?.requestFocus()
            }
        } else {
            recyclerEpisodes.scrollToPosition(0)
        }
        txtEpisodesHeader.text = if (rawEpisodes.any { it.seasonNumber > 1 }) {
            "Temporada $season (${sortedList.size} Episodios)"
        } else {
            "Episodios Disponibles (${sortedList.size})"
        }
        refreshWatchedButtonState()
    }

    private fun toggleSortOrder() {
        isAscendingOrder = !isAscendingOrder
        com.example.animetv.core.history.UiPreferencesStore.setEpisodesAscending(this, isAscendingOrder)
        btnSortOrder.text = if (isAscendingOrder) "⇄ Orden: 1 ➔ N" else "⇄ Orden: N ➔ 1"
        displayEpisodesForSeason(selectedSeason)
    }

    /** Episodes belonging to [selectedSeason] (or all raw episodes for single-season shows). */
    private fun currentSeasonEpisodes(): List<AnimeEpisode> {
        return if (rawEpisodes.any { it.seasonNumber > 1 }) {
            rawEpisodes.filter { (if (it.seasonNumber > 0) it.seasonNumber else 1) == selectedSeason }
        } else {
            rawEpisodes
        }
    }

    /**
     * Updates the 👁 watched-toggle button's label to reflect whether the relevant scope (the
     * whole movie, or every episode of [selectedSeason] for a show) is already fully watched.
     */
    private fun refreshWatchedButtonState() {
        val card = currentCard ?: return
        val episodes = if (isCurrentMovie) rawEpisodes else currentSeasonEpisodes()
        if (episodes.isEmpty()) return
        val watchedCount = com.example.animetv.core.history.WatchedEpisodeStore.getWatchedCount(this, card.detailUrl, episodes)
        val allWatched = watchedCount >= episodes.size
        actionWatched.label = when {
            isCurrentMovie && allWatched -> "Vista ✓"
            isCurrentMovie -> "Marcar Vista"
            allWatched -> "Temporada Vista ✓"
            else -> "Marcar Vista"
        }
    }

    /**
     * Toggles watched status for the whole movie, or for every episode of the currently selected
     * season (so a 20-season show can be marked watched one season at a time while newer,
     * still-unwatched seasons are tracked episode-by-episode as usual via the episode cards).
     */
    private fun toggleWatchedForCurrentSeason() {
        val card = currentCard ?: return
        val episodes = if (isCurrentMovie) rawEpisodes else currentSeasonEpisodes()
        if (episodes.isEmpty()) return
        val watchedCount = com.example.animetv.core.history.WatchedEpisodeStore.getWatchedCount(this, card.detailUrl, episodes)
        val markAsWatched = watchedCount < episodes.size
        com.example.animetv.core.history.WatchedEpisodeStore.markSeasonWatched(this, card.detailUrl, episodes, markAsWatched)

        val msg = when {
            markAsWatched && isCurrentMovie -> "✓ Marcada como vista"
            markAsWatched -> "✓ Temporada marcada como vista"
            isCurrentMovie -> "↩ Marcada como no vista"
            else -> "↩ Temporada marcada como no vista"
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

        if (isCurrentMovie) {
            refreshWatchedButtonState()
        } else {
            displayEpisodesForSeason(selectedSeason)
        }
    }

    private fun bindInitialCard(card: AnimeCard) {
        txtTitle.text = card.title
        txtMeta.text = "${card.source}  •  ${card.episodeBadge.ifEmpty { "Serie" }}"
        txtSynopsis.text = card.synopsis.ifEmpty { "Cargando sinopsis y capítulos..." }

        val initPoster = if (CoverUtils.isValidCover(card.posterUrl)) card.posterUrl else ""
        if (initPoster.isNotEmpty()) {
            Glide.with(this)
                .load(initPoster)
                .centerCrop()
                .placeholder(R.drawable.bg_card_poster_placeholder)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .into(imgPoster)

            Glide.with(this)
                .load(initPoster)
                .centerCrop()
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .into(imgBackdrop)
        } else {
            imgPoster.setImageResource(R.drawable.bg_card_poster_placeholder)
        }

        updateFavoriteButton(card)
        btnToggleFavorite.setOnClickListener {
            toggleFavorite(card)
        }
    }

    private fun updateFavoriteButton(card: AnimeCard) {
        val isFav = FavoritesStore.isFavorite(this, card.detailUrl)
        if (isFav) {
            actionFavorite.icon = "✓"
            actionFavorite.label = "En Mi Lista"
        } else {
            actionFavorite.icon = "+"
            actionFavorite.label = "Mi Lista"
        }
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
                val tmdb = if (detail != null && detail.tmdbId > 0 && detail.tmdbMediaType.isNotEmpty()) {
                    TmdbMetadataRepository.getMetadataByTmdbId(this@DetailActivity, detail.tmdbId, detail.tmdbMediaType)
                } else {
                    val titleForTmdb = detail?.title?.takeIf { it.isNotBlank() && it != "Anime" } ?: card.title
                    TmdbMetadataRepository.searchMetadata(this@DetailActivity, titleForTmdb)
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
                    if (tmdb.posterUrl.isNotEmpty()) {
                        Glide.with(this@DetailActivity)
                            .load(tmdb.posterUrl)
                            .centerCrop()
                            .placeholder(R.drawable.bg_card_poster_placeholder)
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                            .into(imgPoster)
                    }
                    // TMDB's overview is authoritative and always wins over the scraper's synopsis,
                    // which is frequently just an SEO description (e.g. "Ver X en español latino
                    // online...") rather than an actual plot summary.
                    if (tmdb.overview.isNotEmpty()) {
                        txtSynopsis.text = tmdb.overview
                    }
                    if (tmdb.ratingText.isNotEmpty() && !txtMeta.text.contains("★")) {
                        txtMeta.text = "${txtMeta.text}  •  ${tmdb.ratingText}"
                    }

                    val badgeParts = mutableListOf<String>()
                    if (tmdb.certification.isNotEmpty()) badgeParts.add(tmdb.certification)
                    if (tmdb.runtimeMinutes > 0) {
                        val h = tmdb.runtimeMinutes / 60
                        val m = tmdb.runtimeMinutes % 60
                        badgeParts.add(if (h > 0) "${h}h ${m}min" else "${m}min")
                    }
                    if (tmdb.genres.isNotEmpty()) badgeParts.add(tmdb.genres.take(3).joinToString(", "))
                    if (badgeParts.isNotEmpty()) {
                        txtMovieBadges.text = badgeParts.joinToString("  •  ")
                        txtMovieBadges.visibility = View.VISIBLE
                    }

                    val creditsParts = mutableListOf<String>()
                    if (tmdb.director.isNotEmpty()) creditsParts.add("Director: ${tmdb.director}")
                    if (tmdb.cast.isNotEmpty()) creditsParts.add("Reparto: ${tmdb.cast.joinToString(", ")}")
                    if (creditsParts.isNotEmpty()) {
                        txtMovieCredits.text = creditsParts.joinToString("\n")
                        txtMovieCredits.visibility = View.VISIBLE
                    }

                    enrichEpisodesWithTmdb(tmdb)
                    if (tmdb.trailerUrl.isNotEmpty()) {
                        setupTrailerButton(tmdb.trailerUrl, currentDetail?.title ?: card.title)
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
                        .placeholder(R.drawable.bg_card_poster_placeholder)
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .into(imgPoster)

                    Glide.with(this@DetailActivity)
                        .load(bestPoster)
                        .centerCrop()
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .into(imgBackdrop)
                }

                txtTitle.text = detail.title
                val genresStr = if (detail.genres.isNotEmpty()) detail.genres.take(3).joinToString(", ") else "Anime"
                val ratingPart = if (currentTmdbMeta?.ratingText?.isNotEmpty() == true) "  •  ${currentTmdbMeta?.ratingText}" else ""
                txtMeta.text = "${detail.source}  •  $genresStr$ratingPart"
                if (currentTmdbMeta == null || currentTmdbMeta?.overview.isNullOrEmpty()) {
                    txtSynopsis.text = detail.synopsis.ifEmpty { "Sin sinopsis disponible." }
                }
                txtEpisodesHeader.text = "Episodios Disponibles (${detail.episodes.size})"

                // Trailer Button
                val effectiveTrailer = detail.trailerUrl.ifEmpty { currentTmdbMeta?.trailerUrl ?: "" }
                setupTrailerButton(effectiveTrailer, detail.title)

                // Movies never need the S01E01-style episode UI, even if the scraper returned a
                // single "episode" stub for the movie itself — the WEB/TOR buttons still play it
                // correctly via the existing episode-based wiring below, only the episode chrome
                // (header, grid, spotlight panel) is hidden.
                layoutEpisodesHeaderRow.visibility = if (isCurrentMovie) View.GONE else View.VISIBLE
                recyclerEpisodes.visibility = if (isCurrentMovie) View.GONE else View.VISIBLE

                if (detail.episodes.isNotEmpty()) {
                    val uniqueSeasons = detail.episodes.map { if (it.seasonNumber > 0) it.seasonNumber else 1 }.distinct().sorted()
                    val record = com.example.animetv.core.history.PlaybackHistoryStore.getRecordForAnime(this@DetailActivity, card.detailUrl)

                    if (uniqueSeasons.size > 1) {
                        val lastWatchedSeason = record?.let { rec ->
                            detail.episodes.firstOrNull { it.episodeUrl == rec.episodeUrl || it.episodeNumber == rec.episodeNumber }?.seasonNumber
                        } ?: uniqueSeasons.first()
                        selectedSeason = if (uniqueSeasons.contains(lastWatchedSeason)) lastWatchedSeason else uniqueSeasons.first()

                        recyclerSeasons.visibility = View.VISIBLE
                        val sAdapter = SeasonCapsuleAdapter(uniqueSeasons, selectedSeason) { chosenSeason ->
                            selectedSeason = chosenSeason
                            displayEpisodesForSeason(chosenSeason)
                        }
                        seasonAdapter = sAdapter
                        recyclerSeasons.adapter = sAdapter
                    } else {
                        // Season capsules may still be applied once TMDB metadata is available
                        recyclerSeasons.visibility = View.GONE
                        selectedSeason = 1
                    }

                    val initialEpisodes = if (uniqueSeasons.size > 1) {
                        detail.episodes.filter { (if (it.seasonNumber > 0) it.seasonNumber else 1) == selectedSeason }
                    } else {
                        detail.episodes
                    }
                    val sortedList = getSortedEpisodes(initialEpisodes, isAscendingOrder)
                    val showPoster = CoverUtils.pickBestCover(currentTmdbMeta?.posterUrl ?: detail.posterUrl, card.posterUrl)
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
                        }
                    )
                    episodeAdapter = adapter
                    recyclerEpisodes.adapter = adapter
                    if (sortedList.isNotEmpty()) {
                        // Land on the last-watched episode's card (e.g. S08E03) instead of
                        // always starting the row scrolled to episode 1.
                        val resumeIndex = record?.let { rec ->
                            sortedList.indexOfFirst { it.episodeUrl == rec.episodeUrl || it.episodeNumber == rec.episodeNumber }
                        }?.takeIf { it >= 0 } ?: 0
                        bindFocusedEpisode(sortedList[resumeIndex])
                        if (resumeIndex > 0) {
                            recyclerEpisodes.scrollToPosition(resumeIndex)
                        }
                    }

                    txtEpisodesHeader.text = if (uniqueSeasons.size > 1) {
                        "Temporada $selectedSeason (${sortedList.size} Episodios)"
                    } else {
                        "Episodios Disponibles (${sortedList.size})"
                    }

                    refreshPlaybackState()
                    btnPlayFirst.requestFocus()
                } else {
                    actionPlayFirst.label = if (detail.detailUrl.contains("/pelicula/")) "WEB (Película)" else "WEB"
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

    private fun enrichEpisodesWithTmdb(tmdb: TmdbMetadata) {
        if (rawEpisodes.isEmpty()) return
        lifecycleScope.launch {
            try {
                val seasons = rawEpisodes.map { it.seasonNumber }.distinct()
                val tmdbEpisodesMap = mutableMapOf<Pair<Int, Int>, com.example.animetv.core.tmdb.TmdbEpisode>()
                for (sNum in seasons) {
                    val eps = TmdbMetadataRepository.getSeasonEpisodes(this@DetailActivity, tmdb.tmdbId, sNum)
                    for (ep in eps) {
                        tmdbEpisodesMap[Pair(ep.seasonNumber, ep.episodeNumber)] = ep
                    }
                }

                if (tmdbEpisodesMap.isNotEmpty()) {
                    rawEpisodes = rawEpisodes.map { ep ->
                        val tEp = tmdbEpisodesMap[Pair(ep.seasonNumber, ep.episodeNumber)]
                            ?: tmdbEpisodesMap[Pair(1, ep.episodeNumber)]
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
        txtEpisodesHeader.text = "Temporada $selectedSeason (${episodeAdapter?.itemCount ?: rawEpisodes.size} Episodios)"
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
                    txtEpisodesHeader.text = "Temporada $season (Sin episodios disponibles)"
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
                        }
                    )
                    episodeAdapter = adapter
                    recyclerEpisodes.adapter = adapter
                    if (stubs.isNotEmpty()) bindFocusedEpisode(stubs[0])
                }

                txtEpisodesHeader.text = "Temporada $season (${stubs.size} Episodios • TMDB)"
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
                val stream = CatalogRepository.resolveStream(this@DetailActivity, episode.episodeUrl, detail.source)
                val bestPoster = CoverUtils.pickBestCover(detail.posterUrl, currentCard?.posterUrl)
                progressBar.visibility = View.GONE

                if (stream != null && stream.videoUrl.isNotEmpty()) {
                    PlayerActivity.start(
                        this@DetailActivity,
                        videoUrl = stream.videoUrl,
                        title = "${detail.title} - ${episode.title}",
                        isHls = stream.isHls,
                        isEmbed = stream.isEmbed,
                        referer = stream.headers["Referer"] ?: "",
                        animeDetailUrl = detail.detailUrl,
                        animeTitle = detail.title,
                        posterUrl = bestPoster,
                        source = detail.source,
                        episodeUrl = episode.episodeUrl,
                        episodeTitle = episode.title,
                        episodeNumber = episode.episodeNumber,
                        startOver = startOver
                    )
                } else {
                    // Fallback to clean embedded player, NEVER raw HTML in ExoPlayer
                    PlayerActivity.start(
                        this@DetailActivity,
                        videoUrl = episode.episodeUrl,
                        title = "${detail.title} - ${episode.title}",
                        isHls = false,
                        isEmbed = true,
                        referer = detail.detailUrl,
                        animeDetailUrl = detail.detailUrl,
                        animeTitle = detail.title,
                        posterUrl = bestPoster,
                        source = detail.source,
                        episodeUrl = episode.episodeUrl,
                        episodeTitle = episode.title,
                        episodeNumber = episode.episodeNumber,
                        startOver = startOver
                    )
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
                        title = title,
                        isHls = stream.isHls,
                        isEmbed = stream.isEmbed,
                        referer = stream.headers["Referer"] ?: "",
                        animeDetailUrl = currentCard?.detailUrl ?: url,
                        animeTitle = currentCard?.title ?: title,
                        posterUrl = bestPoster,
                        source = sourceName,
                        episodeUrl = url,
                        episodeTitle = title,
                        episodeNumber = 1
                    )
                    return@launch
                }
            } catch (e: Exception) {
                progressBar.visibility = View.GONE
                e.printStackTrace()
            }
            PlayerActivity.start(
                this@DetailActivity,
                videoUrl = url,
                title = title,
                isHls = false,
                isEmbed = true,
                referer = "",
                animeDetailUrl = currentCard?.detailUrl ?: url,
                animeTitle = currentCard?.title ?: title,
                posterUrl = bestPoster,
                source = currentCard?.source ?: "",
                episodeUrl = url,
                episodeTitle = title,
                episodeNumber = 1
            )
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
                        isLiveAction = isLiveAction
                    )

                    progressBar.visibility = View.GONE
                    if (results.isEmpty()) {
                        txtEmpty.visibility = View.VISIBLE
                        txtEmpty.text = "No se encontraron torrents para $epCode.\nPuedes verificar los ajustes de Jackett / Prowlarr o reproducir desde Web."
                        recycler.visibility = View.GONE
                    } else {
                        txtEmpty.visibility = View.GONE
                        recycler.visibility = View.VISIBLE
                        adapter.updateList(results)
                        recycler.requestFocus()
                    }
                } catch (e: Exception) {
                    if (e !is kotlinx.coroutines.CancellationException) {
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
        val title = "${card.title} - ${episode?.title ?: item.resolutionBadge}"
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
                    episodeUrl = item.magnetUrl,
                    episodeTitle = item.title,
                    episodeNumber = episode?.episodeNumber ?: 1,
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
                TorrServerClient.launchNovaPlayer(this@DetailActivity, targetMagnet, title)
                return@launch
            }

            // 3. Check if VLC is installed
            if (TorrServerClient.isVlcInstalled(this@DetailActivity)) {
                showVlcOrNovaPromptDialog(item, title, targetMagnet)
                return@launch
            }

            // 4. No torrent player installed: Guide user to install Nova Video Player from Google Play Store
            showInstallNovaPromptDialog()
        }
    }

    private fun showVlcOrNovaPromptDialog(item: TorrentStreamItem, title: String, targetMagnet: String = item.magnetUrl) {
        AlertDialog.Builder(this)
            .setTitle("🎬 Reproductor de Torrents")
            .setMessage("Se detectó VLC en este dispositivo.\n\nPara la mejor experiencia con motor BitTorrent integrado en la memoria, recomendamos Nova Video Player (gratuito y de código abierto).\n\n¿Cómo deseas reproducir?")
            .setPositiveButton("▶ Abrir en VLC") { _, _ ->
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

}
