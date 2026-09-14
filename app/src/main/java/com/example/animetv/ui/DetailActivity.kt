package com.example.animetv.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
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
import com.example.animetv.ui.adapter.TorrentItemAdapter
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
    private lateinit var btnPlayFirst: Button
    private lateinit var btnRestartEpisode: Button
    private lateinit var btnPlayTorrent: Button
    private lateinit var btnTorrentSettings: Button
    private lateinit var btnTrailer: Button
    private lateinit var btnToggleFavorite: Button
    private lateinit var btnBack: Button
    private lateinit var txtEpisodesHeader: TextView
    private lateinit var btnSortOrder: Button
    private lateinit var btnJumpStart: Button
    private lateinit var btnJumpEnd: Button
    private lateinit var recyclerEpisodes: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var layoutFocusedEpisodeInfo: View
    private lateinit var frameFocusedStill: View
    private lateinit var imgFocusedEpisodeStill: ImageView
    private lateinit var txtFocusedEpisodeHeader: TextView
    private lateinit var txtFocusedEpisodeTitle: TextView
    private lateinit var txtFocusedEpisodeSynopsis: TextView
    private lateinit var btnSpotlightPlayWeb: Button
    private lateinit var btnSpotlightPlayTorrent: Button

    private var currentCard: AnimeCard? = null
    private var currentDetail: AnimeDetail? = null
    private var currentTmdbMeta: TmdbMetadata? = null
    private var currentlyFocusedEpisode: AnimeEpisode? = null
    private var isAscendingOrder: Boolean = true
    private var episodeAdapter: EpisodeAdapter? = null
    private var rawEpisodes: List<AnimeEpisode> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detail)

        imgBackdrop = findViewById(R.id.imgDetailBackdrop)
        imgPoster = findViewById(R.id.imgDetailPoster)
        txtTitle = findViewById(R.id.txtDetailTitle)
        txtMeta = findViewById(R.id.txtDetailMeta)
        txtSynopsis = findViewById(R.id.txtDetailSynopsis)
        btnPlayFirst = findViewById(R.id.btnPlayFirst)
        btnRestartEpisode = findViewById(R.id.btnRestartEpisode)
        btnPlayTorrent = findViewById(R.id.btnPlayTorrent)
        btnTorrentSettings = findViewById(R.id.btnTorrentSettings)
        btnTrailer = findViewById(R.id.btnTrailer)
        btnToggleFavorite = findViewById(R.id.btnToggleFavorite)
        btnBack = findViewById(R.id.btnBack)

        btnPlayTorrent.setOnClickListener {
            showTorrentSelectorDialog()
        }
        btnTorrentSettings.setOnClickListener {
            showTorrentSettingsDialog()
        }
        txtEpisodesHeader = findViewById(R.id.txtEpisodesHeader)
        btnSortOrder = findViewById(R.id.btnSortOrder)
        btnJumpStart = findViewById(R.id.btnJumpStart)
        btnJumpEnd = findViewById(R.id.btnJumpEnd)
        recyclerEpisodes = findViewById(R.id.recyclerEpisodes)
        progressBar = findViewById(R.id.progressBarDetail)
        layoutFocusedEpisodeInfo = findViewById(R.id.layoutFocusedEpisodeInfo)
        frameFocusedStill = findViewById(R.id.frameFocusedStill)
        imgFocusedEpisodeStill = findViewById(R.id.imgFocusedEpisodeStill)
        txtFocusedEpisodeHeader = findViewById(R.id.txtFocusedEpisodeHeader)
        txtFocusedEpisodeTitle = findViewById(R.id.txtFocusedEpisodeTitle)
        txtFocusedEpisodeSynopsis = findViewById(R.id.txtFocusedEpisodeSynopsis)
        btnSpotlightPlayWeb = findViewById(R.id.btnSpotlightPlayWeb)
        btnSpotlightPlayTorrent = findViewById(R.id.btnSpotlightPlayTorrent)

        btnSpotlightPlayWeb.setOnClickListener {
            val ep = currentlyFocusedEpisode ?: rawEpisodes.firstOrNull()
            val detail = currentDetail
            if (ep != null && detail != null) {
                playEpisode(detail, ep)
            }
        }

        btnSpotlightPlayTorrent.setOnClickListener {
            val ep = currentlyFocusedEpisode ?: rawEpisodes.firstOrNull()
            if (ep != null) {
                showTorrentSelectorDialog(ep)
            }
        }

        recyclerEpisodes.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)

        btnBack.setOnClickListener { finish() }

        btnSortOrder.setOnClickListener {
            toggleSortOrder()
        }

        btnJumpStart.setOnClickListener {
            if (rawEpisodes.isNotEmpty()) {
                recyclerEpisodes.smoothScrollToPosition(0)
            }
        }

        btnJumpEnd.setOnClickListener {
            if (rawEpisodes.isNotEmpty()) {
                recyclerEpisodes.smoothScrollToPosition(rawEpisodes.size - 1)
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
            btnPlayFirst.text = "▶  WEB (E${record.episodeNumber})"
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
                btnRestartEpisode.text = "↺  Reiniciar Ep"
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
            btnPlayFirst.text = "▶  WEB"
            btnPlayFirst.setOnLongClickListener(null)
            btnPlayFirst.setOnClickListener {
                currentDetail?.let { playEpisode(it, firstEp, startOver = false) }
            }
            btnRestartEpisode.visibility = View.GONE
        } else {
            btnPlayFirst.text = "▶  WEB"
            btnRestartEpisode.visibility = View.GONE
        }

        btnPlayTorrent.text = "⚡  TOR"

        episodeAdapter?.let { adapter ->
            val list = getSortedEpisodes(rawEpisodes, isAscendingOrder)
            adapter.updateList(list, record)
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

    private fun bindFocusedEpisode(ep: AnimeEpisode) {
        currentlyFocusedEpisode = ep
        layoutFocusedEpisodeInfo.visibility = View.VISIBLE
        val baseHeader = if (ep.seasonNumber > 1) {
            "TEMPORADA ${ep.seasonNumber} • EPISODIO ${ep.episodeNumber}"
        } else {
            "EPISODIO ${ep.episodeNumber}"
        }
        txtFocusedEpisodeHeader.text = if (ep.releaseDate.isNotEmpty()) "$baseHeader  •  ${ep.releaseDate}" else baseHeader
        txtFocusedEpisodeTitle.text = ep.title.ifEmpty { "Episodio ${ep.episodeNumber}" }

        val synopsisText = if (ep.synopsis.isNotEmpty()) {
            ep.synopsis
        } else {
            val showTitle = currentDetail?.title ?: "esta serie"
            "Episodio ${ep.episodeNumber} de $showTitle.\n(Esta fuente no incluye sinopsis individual para cada capítulo)."
        }
        txtFocusedEpisodeSynopsis.text = synopsisText

        if (ep.stillUrl.isNotEmpty()) {
            frameFocusedStill.visibility = View.VISIBLE
            Glide.with(this)
                .load(ep.stillUrl)
                .centerCrop()
                .placeholder(R.drawable.bg_card_poster_placeholder)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .into(imgFocusedEpisodeStill)
        } else {
            frameFocusedStill.visibility = View.GONE
        }

        btnSpotlightPlayWeb.text = "▶  WEB (Ep. ${ep.episodeNumber})"
        btnSpotlightPlayTorrent.text = "⚡  TOR (Ep. ${ep.episodeNumber})"
    }

    private fun toggleSortOrder() {
        isAscendingOrder = !isAscendingOrder
        btnSortOrder.text = if (isAscendingOrder) "⇄ Orden: 1 ➔ N" else "⇄ Orden: N ➔ 1"
        val sorted = getSortedEpisodes(rawEpisodes, isAscendingOrder)
        val record = currentCard?.let { com.example.animetv.core.history.PlaybackHistoryStore.getRecordForAnime(this, it.detailUrl) }
        episodeAdapter?.updateList(sorted, record)
        if (sorted.isNotEmpty()) {
            bindFocusedEpisode(sorted[0])
        }
        recyclerEpisodes.scrollToPosition(0)
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
            btnToggleFavorite.text = "✓  En Mi Lista"
        } else {
            btnToggleFavorite.text = "+  Mi Lista"
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

        // Asynchronously fetch TMDB metadata & high-resolution artwork
        lifecycleScope.launch {
            try {
                val tmdb = TmdbMetadataRepository.searchMetadata(this@DetailActivity, card.title)
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
                    if (tmdb.overview.isNotEmpty() && (txtSynopsis.text.isNullOrEmpty() || txtSynopsis.text.length < 50 || txtSynopsis.text.contains("Cargando"))) {
                        txtSynopsis.text = tmdb.overview
                    }
                    if (tmdb.ratingText.isNotEmpty() && !txtMeta.text.contains("★")) {
                        txtMeta.text = "${txtMeta.text}  •  ${tmdb.ratingText}"
                    }
                    enrichEpisodesWithTmdb(tmdb)
                    if (tmdb.trailerUrl.isNotEmpty()) {
                        setupTrailerButton(tmdb.trailerUrl, currentDetail?.title ?: card.title)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        lifecycleScope.launch {
            try {
                val detail = CatalogRepository.getAnimeDetail(card)
                currentDetail = detail
                rawEpisodes = detail.episodes
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

                if (detail.episodes.isNotEmpty()) {
                    val record = com.example.animetv.core.history.PlaybackHistoryStore.getRecordForAnime(this@DetailActivity, card.detailUrl)
                    val sortedList = getSortedEpisodes(detail.episodes, isAscendingOrder)
                    val adapter = EpisodeAdapter(
                        episodes = sortedList,
                        lastWatchedRecord = record,
                        onEpisodeFocus = { ep ->
                            bindFocusedEpisode(ep)
                        },
                        onEpisodeLongClick = { ep ->
                            showTorrentSelectorDialog(ep)
                        },
                        onEpisodeClick = { ep ->
                            val action = TorrentSettingsStore.getEpisodeClickAction(this@DetailActivity)
                            when (action) {
                                "torrent" -> showTorrentSelectorDialog(ep)
                                "ask" -> showEpisodeChoiceDialog(detail, ep)
                                else -> playEpisode(detail, ep)
                            }
                        }
                    )
                    episodeAdapter = adapter
                    recyclerEpisodes.adapter = adapter
                    bindFocusedEpisode(sortedList[0])

                    refreshPlaybackState()
                    btnPlayFirst.requestFocus()
                } else {
                    btnPlayFirst.text = if (detail.detailUrl.contains("/pelicula/")) "▶  WEB (Película)" else "▶  WEB"
                    btnPlayFirst.setOnClickListener {
                        playDirectUrl(detail.detailUrl, detail.title)
                    }
                }

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

                    val record = currentCard?.let { com.example.animetv.core.history.PlaybackHistoryStore.getRecordForAnime(this@DetailActivity, it.detailUrl) }
                    val sortedList = getSortedEpisodes(rawEpisodes, isAscendingOrder)
                    episodeAdapter?.updateList(sortedList, record)
                    currentlyFocusedEpisode?.let { focused ->
                        val updated = sortedList.firstOrNull { it.episodeUrl == focused.episodeUrl || it.episodeNumber == focused.episodeNumber }
                            ?: sortedList.firstOrNull()
                        if (updated != null) {
                            bindFocusedEpisode(updated)
                        }
                    }
                }
            } catch (e: Exception) {
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
        val titleText = if (episode.seasonNumber > 1) {
            "Temporada ${episode.seasonNumber} • Episodio ${episode.episodeNumber}"
        } else {
            "Episodio ${episode.episodeNumber}"
        }

        AlertDialog.Builder(this)
            .setTitle(titleText)
            .setMessage(episode.title.ifEmpty { "¿Cómo deseas reproducir este capítulo?" })
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
        val sortedList = getSortedEpisodes(rawEpisodes, isAscendingOrder)

        var currentEpIndex = if (targetEpisode != null) {
            sortedList.indexOfFirst {
                it.episodeUrl == targetEpisode.episodeUrl ||
                        (it.seasonNumber == targetEpisode.seasonNumber && it.episodeNumber == targetEpisode.episodeNumber)
            }
        } else {
            val focused = currentlyFocusedEpisode
            if (focused != null) {
                sortedList.indexOfFirst {
                    it.episodeUrl == focused.episodeUrl ||
                            (it.seasonNumber == focused.seasonNumber && it.episodeNumber == focused.episodeNumber)
                }
            } else {
                val record = com.example.animetv.core.history.PlaybackHistoryStore.getRecordForAnime(this, card.detailUrl)
                if (record != null) {
                    sortedList.indexOfFirst { it.episodeUrl == record.episodeUrl || it.episodeNumber == record.episodeNumber }
                } else 0
            }
        }
        if (currentEpIndex < 0) currentEpIndex = 0

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

        val titleDisplay = card.title

        btnClose.setOnClickListener { dialog.dismiss() }

        recycler.layoutManager = LinearLayoutManager(this)
        val adapter = TorrentItemAdapter(emptyList()) { item ->
            handleTorrentSelection(dialog, item, card, detail, currentEp)
        }
        recycler.adapter = adapter

        var searchJob: kotlinx.coroutines.Job? = null

        fun searchEpisode(ep: AnimeEpisode?) {
            searchJob?.cancel()
            val seasonNum = ep?.seasonNumber ?: 1
            val epNum = ep?.episodeNumber ?: 1

            txtHeader.text = "⚡ Torrents: $titleDisplay"
            val qualityBadge = TorrentSettingsStore.getQualityFilter(this)
            val langFilter = TorrentSettingsStore.getLanguageFilter(this)
            val langText = when (langFilter) {
                "spanish_only" -> "Solo Español"
                "dual_audio" -> "Dual Audio"
                "sub_only" -> "Subtitulado"
                else -> "Español > Dual > Seeds"
            }
            txtSubtitle.text = if (isMovie) "(Película) | $qualityBadge | $langText" else "T$seasonNum • Ep. $epNum | $qualityBadge | $langText"

            if (sortedList.isNotEmpty() && !isMovie) {
                layoutEpisodeNavigator.visibility = View.VISIBLE
                txtTorrentCurrentEp.text = "T$seasonNum • Ep. $epNum (${currentEpIndex + 1}/${sortedList.size})"
                btnTorrentEpFirst.isEnabled = currentEpIndex > 0
                btnTorrentEpPrev.isEnabled = currentEpIndex > 0
                btnTorrentEpNext.isEnabled = currentEpIndex < sortedList.size - 1
                btnTorrentEpLast.isEnabled = currentEpIndex < sortedList.size - 1
            } else {
                layoutEpisodeNavigator.visibility = View.GONE
            }

            progressBar.visibility = View.VISIBLE
            txtEmpty.visibility = View.VISIBLE
            txtEmpty.text = "Buscando torrents para ${if (isMovie) "película" else "Episodio $epNum"}..."
            recycler.visibility = View.GONE

            searchJob = lifecycleScope.launch {
                try {
                    val origTitle = currentTmdbMeta?.titleOriginal ?: ""
                    val imdbId = currentTmdbMeta?.imdbId ?: ""

                    val results = TorrentSearchRepository.searchAndFilter(
                        context = this@DetailActivity,
                        query = titleDisplay,
                        originalQuery = origTitle,
                        imdbId = imdbId,
                        seasonNumber = seasonNum,
                        episodeNumber = epNum,
                        isMovie = isMovie
                    )

                    progressBar.visibility = View.GONE
                    if (results.isEmpty()) {
                        txtEmpty.visibility = View.VISIBLE
                        txtEmpty.text = "No se encontraron torrents para T$seasonNum • Ep. $epNum.\nPuedes verificar los ajustes de Jackett / Prowlarr o reproducir desde Web."
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
            showTorrentSettingsDialog {
                searchEpisode(currentEp)
            }
        }

        searchEpisode(currentEp)
        dialog.show()
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
                val streamUrl = TorrServerClient.getStreamUrl(torrServerUrl, item.magnetUrl, title)
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

            // 2. Zero-Server / Zero-PC mode: check if Nova Video Player is installed
            if (TorrServerClient.isNovaPlayerInstalled(this@DetailActivity)) {
                Toast.makeText(this@DetailActivity, "Iniciando streaming en Nova Video Player...", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                TorrServerClient.launchNovaPlayer(this@DetailActivity, item.magnetUrl, title)
                return@launch
            }

            // 3. Check if VLC is installed
            if (TorrServerClient.isVlcInstalled(this@DetailActivity)) {
                showVlcOrNovaPromptDialog(item, title)
                return@launch
            }

            // 4. No torrent player installed: Guide user to install Nova Video Player from Google Play Store
            showInstallNovaPromptDialog()
        }
    }

    private fun showVlcOrNovaPromptDialog(item: TorrentStreamItem, title: String) {
        AlertDialog.Builder(this)
            .setTitle("🎬 Reproductor de Torrents")
            .setMessage("Se detectó VLC en este dispositivo.\n\nPara la mejor experiencia con motor BitTorrent integrado en la memoria, recomendamos Nova Video Player (gratuito y de código abierto).\n\n¿Cómo deseas reproducir?")
            .setPositiveButton("▶ Abrir en VLC") { _, _ ->
                TorrServerClient.launchVlc(this, item.magnetUrl, title)
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
                showTorrentSettingsDialog()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showTorrentSettingsDialog(onSaved: (() -> Unit)? = null) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_torrent_settings, null)
        val editTorrServer = dialogView.findViewById<EditText>(R.id.editTorrServerUrl)
        val editJackett = dialogView.findViewById<EditText>(R.id.editJackettUrl)
        val editJackettKey = dialogView.findViewById<EditText>(R.id.editJackettApiKey)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancelTorrentSettings)
        val btnSave = dialogView.findViewById<Button>(R.id.btnSaveTorrentSettings)

        val rgQuality = dialogView.findViewById<RadioGroup>(R.id.rgQuality)
        val rbQuality1080p = dialogView.findViewById<RadioButton>(R.id.rbQuality1080p)
        val rbQuality720p = dialogView.findViewById<RadioButton>(R.id.rbQuality720p)
        val rbQualityAll = dialogView.findViewById<RadioButton>(R.id.rbQualityAll)
        val chkAllow4k = dialogView.findViewById<CheckBox>(R.id.chkAllow4k)
        val txtQualitySummary = dialogView.findViewById<TextView>(R.id.txtQualitySummary)

        val rgLanguage = dialogView.findViewById<RadioGroup>(R.id.rgLanguage)
        val rbLangSpanish = dialogView.findViewById<RadioButton>(R.id.rbLangSpanish)
        val rbLangDual = dialogView.findViewById<RadioButton>(R.id.rbLangDual)
        val rbLangSub = dialogView.findViewById<RadioButton>(R.id.rbLangSub)
        val rbLangAll = dialogView.findViewById<RadioButton>(R.id.rbLangAll)
        val txtLanguageSummary = dialogView.findViewById<TextView>(R.id.txtLanguageSummary)

        val rgEpisodeAction = dialogView.findViewById<RadioGroup>(R.id.rgEpisodeAction)
        val rbActionWeb = dialogView.findViewById<RadioButton>(R.id.rbActionWeb)
        val rbActionTorrent = dialogView.findViewById<RadioButton>(R.id.rbActionTorrent)
        val rbActionAsk = dialogView.findViewById<RadioButton>(R.id.rbActionAsk)
        val txtActionSummary = dialogView.findViewById<TextView>(R.id.txtActionSummary)

        fun updateQualitySummary(id: Int) {
            txtQualitySummary.text = when (id) {
                R.id.rbQuality720p -> "✔ Activo: 720p"
                R.id.rbQualityAll -> "✔ Activo: Cualquiera (HD)"
                else -> "✔ Activo: 1080p (Óptimo)"
            }
        }

        fun updateLanguageSummary(id: Int) {
            txtLanguageSummary.text = when (id) {
                R.id.rbLangSpanish -> "✔ Activo: 🇪🇸 Solo Español"
                R.id.rbLangDual -> "✔ Activo: 🌐 Dual Audio"
                R.id.rbLangSub -> "✔ Activo: 💬 Sub / Orig"
                else -> "✔ Activo: 🌍 Todos"
            }
        }

        fun updateActionSummary(id: Int) {
            txtActionSummary.text = when (id) {
                R.id.rbActionTorrent -> "✔ Activo: ⚡ TOR"
                R.id.rbActionAsk -> "✔ Activo: ❓ Preguntar"
                else -> "✔ Activo: ▶ WEB"
            }
        }

        // Initialize values from store
        when (TorrentSettingsStore.getQualityFilter(this)) {
            "720p" -> rgQuality.check(R.id.rbQuality720p)
            "all" -> rgQuality.check(R.id.rbQualityAll)
            else -> rgQuality.check(R.id.rbQuality1080p)
        }
        chkAllow4k.isChecked = !TorrentSettingsStore.isDisallow4k(this)

        when (TorrentSettingsStore.getLanguageFilter(this)) {
            "spanish_only" -> rgLanguage.check(R.id.rbLangSpanish)
            "dual_audio" -> rgLanguage.check(R.id.rbLangDual)
            "sub_only" -> rgLanguage.check(R.id.rbLangSub)
            else -> rgLanguage.check(R.id.rbLangAll)
        }

        when (TorrentSettingsStore.getEpisodeClickAction(this)) {
            "torrent" -> rgEpisodeAction.check(R.id.rbActionTorrent)
            "ask" -> rgEpisodeAction.check(R.id.rbActionAsk)
            else -> rgEpisodeAction.check(R.id.rbActionWeb)
        }

        updateQualitySummary(rgQuality.checkedRadioButtonId)
        updateLanguageSummary(rgLanguage.checkedRadioButtonId)
        updateActionSummary(rgEpisodeAction.checkedRadioButtonId)

        rgQuality.setOnCheckedChangeListener { _, id -> updateQualitySummary(id) }
        rgLanguage.setOnCheckedChangeListener { _, id -> updateLanguageSummary(id) }
        rgEpisodeAction.setOnCheckedChangeListener { _, id -> updateActionSummary(id) }

        editTorrServer.setText(TorrentSettingsStore.getTorrServerUrl(this))
        editJackett.setText(TorrentSettingsStore.getJackettUrl(this))
        editJackettKey.setText(TorrentSettingsStore.getJackettApiKey(this))

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnSave.setOnClickListener {
            val qualityChoice = when (rgQuality.checkedRadioButtonId) {
                R.id.rbQuality720p -> "720p"
                R.id.rbQualityAll -> "all"
                else -> "1080p"
            }
            TorrentSettingsStore.setQualityFilter(this, qualityChoice)
            TorrentSettingsStore.setDisallow4k(this, !chkAllow4k.isChecked)

            val langChoice = when (rgLanguage.checkedRadioButtonId) {
                R.id.rbLangSpanish -> "spanish_only"
                R.id.rbLangDual -> "dual_audio"
                R.id.rbLangSub -> "sub_only"
                else -> "all"
            }
            TorrentSettingsStore.setLanguageFilter(this, langChoice)

            val actionChoice = when (rgEpisodeAction.checkedRadioButtonId) {
                R.id.rbActionTorrent -> "torrent"
                R.id.rbActionAsk -> "ask"
                else -> "web"
            }
            TorrentSettingsStore.setEpisodeClickAction(this, actionChoice)

            val tsUrl = editTorrServer.text.toString().trim()
            val jUrl = editJackett.text.toString().trim()
            val jKey = editJackettKey.text.toString().trim()

            TorrentSettingsStore.setTorrServerUrl(this, tsUrl)
            TorrentSettingsStore.setJackettUrl(this, jUrl)
            TorrentSettingsStore.setJackettApiKey(this, jKey)

            Toast.makeText(this, "Ajustes de Torrents guardados", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
            onSaved?.invoke()
        }

        dialog.show()
    }
}
