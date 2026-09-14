package com.example.animetv.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
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
    private lateinit var txtFocusedEpisodeHeader: TextView
    private lateinit var txtFocusedEpisodeTitle: TextView
    private lateinit var txtFocusedEpisodeSynopsis: TextView

    private var currentCard: AnimeCard? = null
    private var currentDetail: AnimeDetail? = null
    private var currentTmdbMeta: TmdbMetadata? = null
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
        btnTrailer = findViewById(R.id.btnTrailer)
        btnToggleFavorite = findViewById(R.id.btnToggleFavorite)
        btnBack = findViewById(R.id.btnBack)

        btnPlayTorrent.setOnClickListener {
            showTorrentSelectorDialog()
        }
        txtEpisodesHeader = findViewById(R.id.txtEpisodesHeader)
        btnSortOrder = findViewById(R.id.btnSortOrder)
        btnJumpStart = findViewById(R.id.btnJumpStart)
        btnJumpEnd = findViewById(R.id.btnJumpEnd)
        recyclerEpisodes = findViewById(R.id.recyclerEpisodes)
        progressBar = findViewById(R.id.progressBarDetail)
        layoutFocusedEpisodeInfo = findViewById(R.id.layoutFocusedEpisodeInfo)
        txtFocusedEpisodeHeader = findViewById(R.id.txtFocusedEpisodeHeader)
        txtFocusedEpisodeTitle = findViewById(R.id.txtFocusedEpisodeTitle)
        txtFocusedEpisodeSynopsis = findViewById(R.id.txtFocusedEpisodeSynopsis)

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
            val timeStr = if (record.positionMs > 5000) " (${formatTime(record.positionMs)})" else ""
            btnPlayFirst.text = "▶  Continuar: ${record.episodeTitle}$timeStr"
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
            btnPlayFirst.text = "▶  ${firstEp.title}"
            btnPlayFirst.setOnLongClickListener(null)
            btnPlayFirst.setOnClickListener {
                currentDetail?.let { playEpisode(it, firstEp, startOver = false) }
            }
            btnRestartEpisode.visibility = View.GONE
        } else {
            btnRestartEpisode.visibility = View.GONE
        }

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
                if (detail.trailerUrl.isNotEmpty()) {
                    btnTrailer.visibility = View.VISIBLE
                    btnTrailer.setOnClickListener {
                        PlayerActivity.start(
                            this@DetailActivity,
                            videoUrl = detail.trailerUrl,
                            title = "Tráiler: ${detail.title}",
                            isHls = false,
                            isEmbed = true,
                            referer = if (card.detailUrl.isNotEmpty()) card.detailUrl else "https://sololatino.net"
                        )
                    }
                    btnTrailer.setOnLongClickListener {
                        val videoId = if (detail.trailerUrl.contains("/embed/")) {
                            detail.trailerUrl.substringAfter("/embed/").substringBefore("?").substringBefore("/")
                        } else {
                            Regex("""(?:v=|youtu\.be/)([\w-]+)""").find(detail.trailerUrl)?.groupValues?.get(1) ?: ""
                        }
                        if (videoId.isNotEmpty()) {
                            try {
                                val ytUri = Uri.parse("https://www.youtube.com/watch?v=$videoId")
                                startActivity(Intent(Intent.ACTION_VIEW, ytUri))
                                Toast.makeText(this@DetailActivity, "Abriendo en YouTube...", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                Toast.makeText(this@DetailActivity, "No se pudo abrir YouTube", Toast.LENGTH_SHORT).show()
                            }
                        }
                        true
                    }
                } else {
                    btnTrailer.visibility = View.GONE
                }

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
                            playEpisode(detail, ep)
                        }
                    )
                    episodeAdapter = adapter
                    recyclerEpisodes.adapter = adapter
                    bindFocusedEpisode(sortedList[0])

                    refreshPlaybackState()
                    btnPlayFirst.requestFocus()
                } else {
                    btnPlayFirst.text = if (detail.detailUrl.contains("/pelicula/")) "▶  Reproducir Película" else "▶  Ver en Web"
                    btnPlayFirst.setOnClickListener {
                        playDirectUrl(detail.detailUrl, detail.title)
                    }
                }
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

    private fun showTorrentSelectorDialog(targetEpisode: AnimeEpisode? = null) {
        val card = currentCard ?: return
        val detail = currentDetail

        val isMovie = card.detailUrl.contains("/pelicula/") || (detail != null && detail.episodes.isEmpty())
        val selectedEp = targetEpisode ?: run {
            val record = com.example.animetv.core.history.PlaybackHistoryStore.getRecordForAnime(this, card.detailUrl)
            if (record != null) {
                rawEpisodes.firstOrNull { it.episodeUrl == record.episodeUrl || it.episodeNumber == record.episodeNumber }
            } else if (rawEpisodes.isNotEmpty()) {
                rawEpisodes.first()
            } else null
        }

        val epNumber = selectedEp?.episodeNumber ?: 1
        val seasonNumber = selectedEp?.seasonNumber ?: 1

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

        val titleDisplay = card.title
        val epDisplay = if (isMovie) "(Película)" else "Temporada $seasonNumber • Episodio $epNumber"
        txtHeader.text = "⚡ Torrents (1080p): $titleDisplay"
        txtSubtitle.text = "$epDisplay | Filtro: Máximo 1080p (4K bloqueado) | Prioridad: Español/Latino > Dual Audio > Seeds"

        btnClose.setOnClickListener { dialog.dismiss() }
        btnSettings.setOnClickListener {
            showTorrentSettingsDialog {
                dialog.dismiss()
                showTorrentSelectorDialog(targetEpisode)
            }
        }

        recycler.layoutManager = LinearLayoutManager(this)
        val adapter = TorrentItemAdapter(emptyList()) { item ->
            handleTorrentSelection(dialog, item, card, detail, selectedEp)
        }
        recycler.adapter = adapter

        progressBar.visibility = View.VISIBLE
        txtEmpty.visibility = View.VISIBLE
        txtEmpty.text = "Buscando fuentes torrent en alta definición (Torrentio, Nyaa, Jackett)..."
        recycler.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val origTitle = currentTmdbMeta?.titleOriginal ?: ""
                val imdbId = currentTmdbMeta?.imdbId ?: ""

                val results = TorrentSearchRepository.searchAndFilter(
                    context = this@DetailActivity,
                    query = titleDisplay,
                    originalQuery = origTitle,
                    imdbId = imdbId,
                    seasonNumber = seasonNumber,
                    episodeNumber = epNumber,
                    isMovie = isMovie
                )

                progressBar.visibility = View.GONE
                if (results.isEmpty()) {
                    txtEmpty.visibility = View.VISIBLE
                    txtEmpty.text = "No se encontraron torrents en 1080p/HD para esta búsqueda.\nPuedes verificar los ajustes de Jackett o reproducir desde la fuente Web estándar."
                    recycler.visibility = View.GONE
                } else {
                    txtEmpty.visibility = View.GONE
                    recycler.visibility = View.VISIBLE
                    adapter.updateList(results)
                    recycler.requestFocus()
                }
            } catch (e: Exception) {
                progressBar.visibility = View.GONE
                txtEmpty.visibility = View.VISIBLE
                txtEmpty.text = "Error al buscar torrents: ${e.message}"
            }
        }

        dialog.show()
    }

    private fun handleTorrentSelection(
        dialog: AlertDialog,
        item: TorrentStreamItem,
        card: AnimeCard,
        detail: AnimeDetail?,
        episode: AnimeEpisode?
    ) {
        val torrServerUrl = TorrentSettingsStore.getTorrServerUrl(this)
        Toast.makeText(this, "Conectando con motor TorrServer...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            val isAlive = TorrServerClient.isServerAlive(torrServerUrl)
            if (isAlive) {
                val title = "${card.title} - ${episode?.title ?: item.resolutionBadge}"
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
            } else {
                showTorrServerOfflineDialog(item, torrServerUrl)
            }
        }
    }

    private fun showTorrServerOfflineDialog(item: TorrentStreamItem, serverUrl: String) {
        AlertDialog.Builder(this)
            .setTitle("⚡ Motor TorrServer no detectado")
            .setMessage("No se pudo conectar al servidor de streaming en:\n$serverUrl\n\nPara reproducir torrents directamente en la app con ExoPlayer, ejecuta TorrServer en tu red local o Android TV.\n\nTambién puedes abrir este enlace magnet directamente en VLC o Nova Video Player.")
            .setPositiveButton("▶ Abrir en VLC / Nova") { _, _ ->
                TorrServerClient.openWithExternalPlayer(this, item.magnetUrl, item.title)
            }
            .setNeutralButton("⚙ Ajustes") { _, _ ->
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

        editTorrServer.setText(TorrentSettingsStore.getTorrServerUrl(this))
        editJackett.setText(TorrentSettingsStore.getJackettUrl(this))
        editJackettKey.setText(TorrentSettingsStore.getJackettApiKey(this))

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnSave.setOnClickListener {
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
