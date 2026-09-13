package com.example.animetv.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
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
import com.example.animetv.core.util.CoverUtils
import com.example.animetv.ui.adapter.EpisodeAdapter
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
        btnTrailer = findViewById(R.id.btnTrailer)
        btnToggleFavorite = findViewById(R.id.btnToggleFavorite)
        btnBack = findViewById(R.id.btnBack)
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
        lifecycleScope.launch {
            try {
                val detail = CatalogRepository.getAnimeDetail(card)
                currentDetail = detail
                rawEpisodes = detail.episodes
                progressBar.visibility = View.GONE

                val bestPoster = CoverUtils.pickBestCover(detail.posterUrl, card.posterUrl)
                if (bestPoster.isNotEmpty()) {
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
                txtMeta.text = "${detail.source}  •  $genresStr"
                txtSynopsis.text = detail.synopsis.ifEmpty { "Sin sinopsis disponible." }
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
}
