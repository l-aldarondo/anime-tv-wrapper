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
    private lateinit var recyclerEpisodes: RecyclerView
    private lateinit var progressBar: ProgressBar

    private var currentCard: AnimeCard? = null
    private var currentDetail: AnimeDetail? = null

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
        recyclerEpisodes = findViewById(R.id.recyclerEpisodes)
        progressBar = findViewById(R.id.progressBarDetail)

        recyclerEpisodes.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)

        btnBack.setOnClickListener { finish() }

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
                progressBar.visibility = View.GONE

                txtTitle.text = detail.title
                val genresStr = if (detail.genres.isNotEmpty()) detail.genres.take(3).joinToString(", ") else "Anime"
                txtMeta.text = "${detail.source}  •  $genresStr"
                txtSynopsis.text = detail.synopsis.ifEmpty { "Sin sinopsis disponible." }

                if (detail.episodes.isNotEmpty()) {
                    val adapter = EpisodeAdapter(detail.episodes) { ep ->
                        playEpisode(detail, ep)
                    }
                    recyclerEpisodes.adapter = adapter

                    btnPlayFirst.text = "▶  ${detail.episodes.first().title}"
                    btnPlayFirst.setOnClickListener {
                        playEpisode(detail, detail.episodes.first())
                    }
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
                        referer = stream.headers["Referer"] ?: ""
                    )
                } else {
                    // Fallback to clean embedded player, NEVER raw HTML in ExoPlayer
                    PlayerActivity.start(
                        this@DetailActivity,
                        videoUrl = episode.episodeUrl,
                        title = "${detail.title} - ${episode.title}",
                        isHls = false,
                        isEmbed = true,
                        referer = detail.detailUrl
                    )
                }
            } catch (e: Exception) {
                progressBar.visibility = View.GONE
                Toast.makeText(this@DetailActivity, "Error al conectar con reproductor: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun playDirectUrl(url: String, title: String) {
        PlayerActivity.start(
            this,
            videoUrl = url,
            title = title,
            isHls = false,
            isEmbed = true,
            referer = ""
        )
    }
}
