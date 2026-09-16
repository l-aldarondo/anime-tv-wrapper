package com.example.animetv

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.example.animetv.core.CatalogRepository
import com.example.animetv.core.HomeCatalogData
import com.example.animetv.core.history.PlaybackHistoryStore
import com.example.animetv.core.history.PlaybackRecord
import com.example.animetv.core.model.AnimeCard
import com.example.animetv.core.model.CatalogRow
import com.example.animetv.core.util.CoverUtils
import com.example.animetv.ui.DetailActivity
import com.example.animetv.ui.adapter.AnimeCardAdapter
import com.example.animetv.ui.adapter.CatalogRowAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 100% Native Android TV application entry point (Netflix / Stremio Architecture).
 * Features hardware D-Pad focus navigation, background HTML scrapers, and native ExoPlayer streaming.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var scrollMain: NestedScrollView
    private lateinit var recyclerCatalogRows: RecyclerView
    private lateinit var progressBarHome: ProgressBar

    // Hero Billboard Views
    private lateinit var imgHeroBackdrop: ImageView
    private lateinit var txtHeroBadge: TextView
    private lateinit var txtHeroTitle: TextView
    private lateinit var txtHeroSynopsis: TextView
    private lateinit var btnHeroPlay: Button
    private lateinit var btnHeroFavorite: Button

    // Top Header Navigation Buttons
    private lateinit var btnNavCatalog: Button
    private lateinit var btnNavSearch: Button
    private lateinit var btnNavRefresh: Button
    private lateinit var btnNavSettings: Button

    // Icon-only/focus-reveal-label wrappers around the buttons above (same order as the bar)
    private lateinit var navCatalog: com.example.animetv.ui.IconRevealButton
    private lateinit var navSearch: com.example.animetv.ui.IconRevealButton
    private lateinit var navRefresh: com.example.animetv.ui.IconRevealButton
    private lateinit var navSettings: com.example.animetv.ui.IconRevealButton

    private lateinit var catalogRowAdapter: CatalogRowAdapter
    private var featuredAnime: AnimeCard? = null
    private var lastLoadedCatalog: HomeCatalogData? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var heroSuggestions = listOf<AnimeCard>()
    private var heroIndex = 0

    private val heroRotateRunnable = object : Runnable {
        override fun run() {
            if (heroSuggestions.isNotEmpty() && !isFinishing && !isDestroyed) {
                heroIndex = (heroIndex + 1) % heroSuggestions.size
                bindHero(heroSuggestions[heroIndex], animate = true)
            }
            mainHandler.postDelayed(this, 12000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        initViews()
        setupRecyclerView()
        setupListeners()
        loadInitialCatalog()
    }

    override fun onResume() {
        super.onResume()
        // Refresh Continuar Viendo & Mi Lista rows dynamically
        refreshRowsWithFavorites()
        featuredAnime?.let { updateHeroFavoriteButton(it) }
        startHeroRotation()
    }

    override fun onPause() {
        super.onPause()
        stopHeroRotation()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopHeroRotation()
    }

    private fun startHeroRotation() {
        mainHandler.removeCallbacks(heroRotateRunnable)
        if (heroSuggestions.size > 1) {
            mainHandler.postDelayed(heroRotateRunnable, 12000L)
        }
    }

    private fun stopHeroRotation() {
        mainHandler.removeCallbacks(heroRotateRunnable)
    }

    private fun initViews() {
        scrollMain = findViewById(R.id.scrollMain)
        recyclerCatalogRows = findViewById(R.id.recyclerCatalogRows)
        progressBarHome = findViewById(R.id.progressBarHome)

        imgHeroBackdrop = findViewById(R.id.imgHeroBackdrop)
        txtHeroBadge = findViewById(R.id.txtHeroBadge)
        txtHeroTitle = findViewById(R.id.txtHeroTitle)
        txtHeroSynopsis = findViewById(R.id.txtHeroSynopsis)
        btnHeroPlay = findViewById(R.id.btnHeroPlay)
        btnHeroFavorite = findViewById(R.id.btnHeroFavorite)

        btnNavCatalog = findViewById(R.id.btnNavCatalog)
        btnNavSearch = findViewById(R.id.btnNavSearch)
        btnNavRefresh = findViewById(R.id.btnNavRefresh)
        btnNavSettings = findViewById(R.id.btnNavSettings)

        navCatalog = com.example.animetv.ui.IconRevealButton(btnNavCatalog, "🏠", "Home")
        navSearch = com.example.animetv.ui.IconRevealButton(btnNavSearch, "🔍", "Buscar")
        navRefresh = com.example.animetv.ui.IconRevealButton(btnNavRefresh, "↻", "Actualizar")
        navSettings = com.example.animetv.ui.IconRevealButton(btnNavSettings, "⚙", "Ajustes")
    }

    private fun setupRecyclerView() {
        recyclerCatalogRows.layoutManager = LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false)
        catalogRowAdapter = CatalogRowAdapter(
            rows = mutableListOf(),
            onCardClick = { card ->
                DetailActivity.start(this, card)
            },
            onCardLongClick = { card ->
                toggleCardFavorite(card)
            },
            rowLongClickOverrides = mapOf(
                "Continuar Viendo" to { card ->
                    showRemoveFromHistoryDialog(card)
                }
            )
        )
        recyclerCatalogRows.adapter = catalogRowAdapter
    }

    private fun showRemoveFromHistoryDialog(card: com.example.animetv.core.model.AnimeCard) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Quitar de Continuar Viendo")
            .setMessage("¿Deseas quitar \"${card.title}\" del historial de visualización?")
            .setPositiveButton("Quitar") { _, _ ->
                PlaybackHistoryStore.removeRecord(this, card.detailUrl)
                refreshRowsWithFavorites()
                Toast.makeText(this, "\"${card.title}\" eliminado del historial", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun setupListeners() {
        // Red highlight dynamically follows focus; default focus on Catálogo
        btnNavCatalog.requestFocus()

        btnNavCatalog.setOnClickListener {
            scrollMain.smoothScrollTo(0, 0)
            btnHeroPlay.requestFocus()
        }

        btnNavSearch.setOnClickListener {
            showSearchDialog()
        }

        btnNavRefresh.setOnClickListener {
            fetchCatalog(forceRefresh = true, isSilent = false)
        }

        btnNavSettings.setOnClickListener {
            com.example.animetv.ui.TorrentSettingsDialog.show(this)
        }
    }

    private fun loadInitialCatalog() {
        // 1. Zero-latency instant cache load for the main screen
        val cached = com.example.animetv.core.HomeCatalogCache.load(this)
        if (cached != null && (cached.latinoTrending.isNotEmpty() || cached.recentEpisodes.isNotEmpty())) {
            lastLoadedCatalog = cached
            progressBarHome.visibility = View.GONE
            updateHeroSuggestions(cached)
            refreshRowsWithFavorites()
            // 2. Silent background revalidation so new episodes update smoothly without blocking the UI
            fetchCatalog(forceRefresh = true, isSilent = true)
        } else {
            // First time app launch without cache: show loading spinner
            fetchCatalog(forceRefresh = false, isSilent = false)
        }
    }

    private fun fetchCatalog(forceRefresh: Boolean, isSilent: Boolean) {
        if (!isSilent) {
            progressBarHome.visibility = View.VISIBLE
        }
        lifecycleScope.launch {
            try {
                val data = CatalogRepository.loadHomeContent(context = this@MainActivity, forceRefresh = forceRefresh)
                lastLoadedCatalog = data
                progressBarHome.visibility = View.GONE

                updateHeroSuggestions(data)
                refreshRowsWithFavorites()
                if (!isSilent && forceRefresh) {
                    Toast.makeText(this@MainActivity, "Catálogo actualizado", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                progressBarHome.visibility = View.GONE
                e.printStackTrace()
                if (!isSilent) {
                    Toast.makeText(this@MainActivity, "Error al cargar catálogo: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun updateHeroSuggestions(data: HomeCatalogData) {
        // Candidate pool from SoloLatino sections + Latino trending
        val pool = mutableListOf<AnimeCard>()
        for (sec in data.soloLatinoSections) {
            pool.addAll(sec.cards)
        }
        pool.addAll(data.latinoTrending)

        val distinct = pool.filter { it.title.isNotEmpty() && (it.backdropUrl.isNotEmpty() || it.posterUrl.isNotEmpty()) }
            .distinctBy { it.detailUrl }

        if (distinct.isNotEmpty()) {
            heroSuggestions = distinct.shuffled().take(20)
            if (featuredAnime == null) {
                heroIndex = 0
                bindHero(heroSuggestions[0], animate = false)
            }
            startHeroRotation()
        }
    }

    private fun bindHero(anime: AnimeCard, animate: Boolean = false) {
        featuredAnime = anime
        txtHeroTitle.text = anime.title
        txtHeroSynopsis.text = anime.synopsis.ifEmpty { "Contenido disponible en SoloLatino en alta definición y audio latino." }
        txtHeroBadge.text = "★ DESTACADO DE LA SEMANA"

        val imageToLoad = anime.backdropUrl.ifEmpty { anime.posterUrl }
        if (imageToLoad.isNotEmpty()) {
            val req = Glide.with(this)
                .load(imageToLoad)
                .centerCrop()
                .diskCacheStrategy(DiskCacheStrategy.ALL)
            if (animate) {
                req.transition(DrawableTransitionOptions.withCrossFade(700))
            }
            req.into(imgHeroBackdrop)
        }

        btnHeroPlay.setOnClickListener {
            DetailActivity.start(this, anime)
        }

        updateHeroFavoriteButton(anime)
        btnHeroFavorite.setOnClickListener {
            toggleCardFavorite(anime)
            updateHeroFavoriteButton(anime)
        }

        if (!animate) {
            // Give initial TV remote focus to the Hero Play button on first load
            btnHeroPlay.requestFocus()
        }
    }

    private fun updateHeroFavoriteButton(anime: AnimeCard) {
        val isFav = FavoritesStore.isFavorite(this, anime.detailUrl)
        if (isFav) {
            btnHeroFavorite.text = "✓  En Mi Lista"
        } else {
            btnHeroFavorite.text = "+  Mi Lista"
        }
    }

    private fun refreshRowsWithFavorites() {
        val data = lastLoadedCatalog ?: return
        val allRows = mutableListOf<CatalogRow>()

        // 1. "Continuar Viendo" Row (Always First, dynamic chronological order of what was watched last)
        val historyRecords = PlaybackHistoryStore.loadAll(this)
        if (historyRecords.isNotEmpty()) {
            val missingCovers = mutableListOf<PlaybackRecord>()
            val continueCards = historyRecords.map { rec ->
                val progressPct = if (rec.durationMs > 0) ((rec.positionMs * 100) / rec.durationMs).toInt() else 0
                val badge = when {
                    rec.episodeNumber > 0 && progressPct > 0 -> "Ep ${rec.episodeNumber} • $progressPct%"
                    rec.episodeNumber > 0 -> "Ep ${rec.episodeNumber}"
                    progressPct > 0 -> "$progressPct%"
                    else -> "Viendo"
                }

                var resolvedPoster = if (CoverUtils.isValidCover(rec.posterUrl)) rec.posterUrl.trim() else ""
                if (resolvedPoster.isEmpty()) {
                    val catalogPoster = findCoverInCatalog(data, rec.animeDetailUrl, rec.animeTitle)
                    if (catalogPoster.isNotEmpty()) {
                        resolvedPoster = catalogPoster
                        PlaybackHistoryStore.updatePoster(this, rec.animeDetailUrl, rec.episodeUrl, catalogPoster)
                    } else {
                        missingCovers.add(rec)
                    }
                }

                val cleanDetailUrl = when {
                    rec.animeDetailUrl.isNotEmpty() && !rec.animeDetailUrl.contains("/temporada-") -> rec.animeDetailUrl
                    rec.animeDetailUrl.contains("/temporada-") -> rec.animeDetailUrl.substringBefore("/temporada-")
                    rec.episodeUrl.contains("/temporada-") -> rec.episodeUrl.substringBefore("/temporada-")
                    else -> rec.animeDetailUrl.ifEmpty { rec.episodeUrl }
                }

                AnimeCard(
                    id = cleanDetailUrl,
                    title = rec.animeTitle.ifEmpty { rec.episodeTitle },
                    posterUrl = resolvedPoster,
                    detailUrl = cleanDetailUrl,
                    source = rec.source.ifEmpty { "Continuar" },
                    episodeBadge = badge
                )
            }.distinctBy { it.detailUrl }

            if (continueCards.isNotEmpty()) {
                allRows.add(CatalogRow(title = "▶ Continuar Viendo", cards = continueCards))
            }

            if (missingCovers.isNotEmpty()) {
                resolveMissingCoversAsync(missingCovers)
            }
        }

        // 2. "Mi Lista" Row (Always Second)
        val storedFavorites = FavoritesStore.getFavorites(this)
        if (storedFavorites.isNotEmpty()) {
            val favCards = storedFavorites.map { fav ->
                AnimeCard(
                    id = fav.url,
                    title = fav.title,
                    posterUrl = fav.poster,
                    detailUrl = fav.url,
                    source = if (fav.source.isNotEmpty()) fav.source else "Mi Lista",
                    episodeBadge = "Guardado"
                )
            }
            allRows.add(CatalogRow(title = "⭐ Mi Lista", cards = favCards))
        }

        // 3. SoloLatino Categorías principales (Películas, Series, Recién Añadidos, Netflix, Prime, Disney+, Apple TV+)
        val tokyoSections = mutableListOf<CatalogRow>()
        for (sec in data.soloLatinoSections) {
            if (sec.title.contains("Tokyo", ignoreCase = true)) {
                tokyoSections.add(sec)
            } else if (sec.cards.isNotEmpty()) {
                allRows.add(sec)
            }
        }

        // 4. SoloAnime (Audio Latino) - colocado justo antes de Tokyo MX
        if (data.latinoTrending.isNotEmpty()) {
            allRows.add(CatalogRow(title = "🔥 SoloAnime (Audio Latino)", cards = data.latinoTrending))
        }

        // 5. Tokyo MX y TV Tokyo (después de Disney+ y SoloAnime)
        for (sec in tokyoSections) {
            if (sec.cards.isNotEmpty()) {
                allRows.add(sec)
            }
        }

        // 6. 9Anime HD (Renombrado según solicitud #5)
        if (data.nineAnimeTrending.isNotEmpty()) {
            allRows.add(CatalogRow(title = "● 9Anime HD", cards = data.nineAnimeTrending))
        }

        // 7. JKAnime (Renombrado según solicitud #6)
        if (data.recentEpisodes.isNotEmpty()) {
            allRows.add(CatalogRow(title = "⚡ JKAnime", cards = data.recentEpisodes))
        }

        // 8. GogoAnime (Renombrado según solicitud #7)
        if (data.gogoTrending.isNotEmpty()) {
            allRows.add(CatalogRow(title = "🌐 GogoAnime", cards = data.gogoTrending))
        }

        // 9. Populares / Recomendados
        if (data.latinoTrending.size > 6) {
            allRows.add(CatalogRow(title = "🌟 Series Populares Recomendadas", cards = data.latinoTrending.reversed()))
        }

        catalogRowAdapter.submitRows(allRows)
    }

    private fun toggleCardFavorite(card: AnimeCard) {
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
        refreshRowsWithFavorites()
    }

    private fun showSearchDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_native_search)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val editInput = dialog.findViewById<EditText>(R.id.editSearchInput)
        val btnSearch = dialog.findViewById<Button>(R.id.btnExecuteSearch)
        val progressBar = dialog.findViewById<ProgressBar>(R.id.searchProgressBar)
        val txtLabel = dialog.findViewById<TextView>(R.id.txtSearchResultsLabel)
        val recycler = dialog.findViewById<RecyclerView>(R.id.recyclerSearchResults)

        recycler.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        val searchAdapter = AnimeCardAdapter(mutableListOf(), onCardClick = { card ->
            dialog.dismiss()
            DetailActivity.start(this, card)
        })
        recycler.adapter = searchAdapter

        fun performSearch() {
            val query = editInput.text.toString().trim()
            if (query.isEmpty()) return

            progressBar.visibility = View.VISIBLE
            txtLabel.visibility = View.GONE
            searchAdapter.submitList(emptyList())

            lifecycleScope.launch {
                try {
                    val results = CatalogRepository.searchAll(query)
                    progressBar.visibility = View.GONE
                    txtLabel.visibility = View.VISIBLE

                    if (results.isNotEmpty()) {
                        txtLabel.text = "Resultados (${results.size}):"
                        searchAdapter.submitList(results)
                        recycler.requestFocus()
                    } else {
                        txtLabel.text = "No se encontraron resultados para '$query'"
                    }
                } catch (e: Exception) {
                    progressBar.visibility = View.GONE
                    txtLabel.visibility = View.VISIBLE
                    txtLabel.text = "Error durante la búsqueda: ${e.message}"
                }
            }
        }

        btnSearch.setOnClickListener { performSearch() }

        editInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performSearch()
                true
            } else false
        }

        dialog.show()
        editInput.requestFocus()
    }

    private fun findCoverInCatalog(data: HomeCatalogData, detailUrl: String, title: String): String {
        val cleanDetail = detailUrl.substringBefore("/temporada-").trim()
        val allCards = sequence {
            yieldAll(heroSuggestions)
            yieldAll(data.latinoTrending)
            yieldAll(data.soloLatinoSections.flatMap { it.cards })
            yieldAll(data.recentEpisodes)
            yieldAll(data.soloStreamTrending)
            yieldAll(data.nineAnimeTrending)
            yieldAll(data.animeYtTrending)
            yieldAll(data.gogoTrending)
        }
        val match = allCards.firstOrNull { card ->
            (cleanDetail.isNotEmpty() && (card.detailUrl.equals(cleanDetail, ignoreCase = true) || card.id.equals(cleanDetail, ignoreCase = true))) ||
            (title.isNotEmpty() && card.title.equals(title, ignoreCase = true))
        }
        return if (match != null && CoverUtils.isValidCover(match.posterUrl)) match.posterUrl.trim() else ""
    }

    private var isResolvingCovers = false
    private fun resolveMissingCoversAsync(records: List<PlaybackRecord>) {
        if (isResolvingCovers) return
        isResolvingCovers = true
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                var anyUpdated = false
                for (rec in records.take(8)) {
                    val targetUrl = when {
                        rec.animeDetailUrl.contains("/temporada-") -> rec.animeDetailUrl.substringBefore("/temporada-")
                        rec.episodeUrl.contains("/temporada-") -> rec.episodeUrl.substringBefore("/temporada-")
                        else -> rec.animeDetailUrl.ifEmpty { rec.episodeUrl }
                    }
                    if (targetUrl.isEmpty()) continue
                    try {
                        val dummyCard = AnimeCard(
                            id = targetUrl,
                            title = rec.animeTitle,
                            posterUrl = "",
                            detailUrl = targetUrl,
                            source = rec.source
                        )
                        val detail = CatalogRepository.getAnimeDetail(dummyCard)
                        if (CoverUtils.isValidCover(detail.posterUrl)) {
                            PlaybackHistoryStore.updatePoster(this@MainActivity, rec.animeDetailUrl, rec.episodeUrl, detail.posterUrl)
                            anyUpdated = true
                        }
                    } catch (e: Exception) {
                        // Ignore individual network resolution errors
                    }
                }
                if (anyUpdated) {
                    withContext(Dispatchers.Main) {
                        refreshRowsWithFavorites()
                    }
                }
            } finally {
                isResolvingCovers = false
            }
        }
    }
}
