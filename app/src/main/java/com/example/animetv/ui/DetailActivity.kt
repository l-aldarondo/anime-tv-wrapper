package com.example.animetv.ui

import android.content.Context
import android.content.Intent
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
    private lateinit var btnToggleFavorite: Button
    private lateinit var btnBack: Button
    private lateinit var txtEpisodesHeader: TextView
    private lateinit var btnSortOrder: Button
    private lateinit var btnJumpStart: Button
    private lateinit var btnJumpEnd: Button
    private lateinit var recyclerEpisodes: RecyclerView
    private lateinit var progressBar: ProgressBar

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
        btnToggleFavorite = findViewById(R.id.btnToggleFavorite)
        btnBack = findViewById(R.id.btnBack)
        txtEpisodesHeader = findViewById(R.id.txtEpisodesHeader)
        btnSortOrder = findViewById(R.id.btnSortOrder)
        btnJumpStart = findViewById(R.id.btnJumpStart)
        btnJumpEnd = findViewById(R.id.btnJumpEnd)
        recyclerEpisodes = findViewById(R.id.recyclerEpisodes)
        progressBar = findViewById(R.id.progressBarDetail)

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
                // Play last watched episode
                val detail = currentDetail
                val ep = rawEpisodes.firstOrNull { it.episodeUrl == record.episodeUrl || it.episodeNumber == record.episodeNumber }
                    ?: AnimeEpisode(record.episodeNumber, 1, record.episodeTitle, record.episodeUrl)
                if (detail != null) {
                    playEpisode(detail, ep)
                } else {
                    playEpisodeDirect(card, ep)
                }
            }
        } else if (rawEpisodes.isNotEmpty()) {
            val firstEp = rawEpisodes.first()
            btnPlayFirst.text = "▶  ${firstEp.title}"
            btnPlayFirst.setOnClickListener {
                currentDetail?.let { playEpisode(it, firstEp) }
            }
        }

        episodeAdapter?.let { adapter ->
            val list = if (isAscendingOrder) rawEpisodes.sortedBy { it.episodeNumber } else rawEpisodes.sortedByDescending { it.episodeNumber }
            adapter.updateList(list, record)
        }
    }

    private fun toggleSortOrder() {
        isAscendingOrder = !isAscendingOrder
        btnSortOrder.text = if (isAscendingOrder) "⇄ Orden: 1 ➔ N" else "⇄ Orden: N ➔ 1"
        val sorted = if (isAscendingOrder) rawEpisodes.sortedBy { it.episodeNumber } else rawEpisodes.sortedByDescending { it.episodeNumber }
        val record = currentCard?.let { com.example.animetv.core.history.PlaybackHistoryStore.getRecordForAnime(this, it.detailUrl) }
        episodeAdapter?.updateList(sorted, record)
        recyclerEpisodes.scrollToPosition(0)
    }

    private fun bindInitialCard(card: AnimeCard) {
        txtTitle.text = card.title
        txtMeta.text = "${card.source}  •  ${card.episodeBadge.ifEmpty { "Serie" }}"
        txtSynopsis.text = card.synopsis.ifEmpty { "Cargando sinopsis y capítulos..." }

        if (card.posterUrl.isNotEmpty()) {
            Glide.with(this)
                .load(card.posterUrl)
                .centerCrop()
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .into(imgPoster)

            Glide.with(this)
                .load(card.posterUrl)
                .centerCrop()
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .into(imgBackdrop)
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
            btnToggleFavorite.setBackgroundResource(R.drawable.bg_topbar_active_source)
        } else {
            btnToggleFavorite.text = "⭐  Añadir a Mi Lista"
            btnToggleFavorite.setBackgroundResource(R.drawable.bg_topbar_item)
        }
    }

    private fun toggleFavorite(card: AnimeCard) {
        val isFav = FavoritesStore.isFavorite(this, card.detailUrl)
        if (isFav) {
            FavoritesStore.remove(this, card.detailUrl)
            Toast.makeText(this, "Eliminado de Mi Lista", Toast.LENGTH_SHORT).show()
        } else {
            val fav = FavoriteItem(
                url = card.detailUrl,
                title = card.title,
                poster = card.posterUrl,
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

                txtTitle.text = detail.title
                val genresStr = if (detail.genres.isNotEmpty()) detail.genres.take(3).joinToString(", ") else "Anime"
                txtMeta.text = "${detail.source}  •  $genresStr"
                txtSynopsis.text = detail.synopsis.ifEmpty { "Sin sinopsis disponible." }
                txtEpisodesHeader.text = "Episodios Disponibles (${detail.episodes.size})"

                if (detail.episodes.isNotEmpty()) {
                    val record = com.example.animetv.core.history.PlaybackHistoryStore.getRecordForAnime(this@DetailActivity, card.detailUrl)
                    val sortedList = if (isAscendingOrder) detail.episodes.sortedBy { it.episodeNumber } else detail.episodes.sortedByDescending { it.episodeNumber }
                    val adapter = EpisodeAdapter(sortedList, record) { ep ->
                        playEpisode(detail, ep)
                    }
                    episodeAdapter = adapter
                    recyclerEpisodes.adapter = adapter

                    refreshPlaybackState()
                    btnPlayFirst.requestFocus()
                } else {
                    btnPlayFirst.text = "▶  Ver en Web"
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

    private fun playEpisode(detail: AnimeDetail, episode: AnimeEpisode) {
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                Toast.makeText(this@DetailActivity, "Conectando al reproductor...", Toast.LENGTH_SHORT).show()
                val stream = CatalogRepository.resolveStream(this@DetailActivity, episode.episodeUrl, detail.source)
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
                        posterUrl = detail.posterUrl,
                        source = detail.source,
                        episodeUrl = episode.episodeUrl,
                        episodeTitle = episode.title,
                        episodeNumber = episode.episodeNumber
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
                        posterUrl = detail.posterUrl,
                        source = detail.source,
                        episodeUrl = episode.episodeUrl,
                        episodeTitle = episode.title,
                        episodeNumber = episode.episodeNumber
                    )
                }
            } catch (e: Exception) {
                progressBar.visibility = View.GONE
                Toast.makeText(this@DetailActivity, "Error al conectar con reproductor: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun playEpisodeDirect(card: AnimeCard, episode: AnimeEpisode) {
        val dummyDetail = AnimeDetail(
            title = card.title,
            posterUrl = card.posterUrl,
            synopsis = card.synopsis,
            source = card.source,
            detailUrl = card.detailUrl,
            episodes = rawEpisodes
        )
        playEpisode(dummyDetail, episode)
    }

    private fun playDirectUrl(url: String, title: String) {
        PlayerActivity.start(
            this,
            videoUrl = url,
            title = title,
            isHls = false,
            isEmbed = true,
            referer = "",
            animeDetailUrl = currentCard?.detailUrl ?: "",
            animeTitle = currentCard?.title ?: title,
            posterUrl = currentCard?.posterUrl ?: "",
            source = currentCard?.source ?: "",
            episodeUrl = url,
            episodeTitle = title,
            episodeNumber = 1
        )
    }

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        val m = (totalSec / 60) % 60
        val s = totalSec % 60
        val h = totalSec / 3600
        return if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s) else String.format(java.util.Locale.US, "%02d:%02d", m, s)
    }
}
