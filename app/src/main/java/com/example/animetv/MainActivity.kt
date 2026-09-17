package com.example.animetv

import android.app.Dialog
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.example.animetv.core.CatalogRepository
import com.example.animetv.core.HomeCatalogData
import com.example.animetv.core.history.PlaybackHistoryStore
import com.example.animetv.core.history.PlaybackRecord
import com.example.animetv.core.model.AnimeCard
import com.example.animetv.core.model.CatalogRow
import com.example.animetv.core.model.CatalogRowType
import com.example.animetv.core.util.CoverUtils
import com.example.animetv.ui.DetailActivity
import com.example.animetv.ui.adapter.AnimeCardAdapter
import com.example.animetv.ui.adapter.ContinueWatchingCardAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 100% Native Android TV application entry point (Nuvio/Stremio-style Home). A large hero banner
 * always reflects whichever card currently has D-pad focus, while every catalog row scrolls
 * continuously beneath it in a single page — matching real Nuvio's layout, not the earlier
 * one-row-at-a-time "board" experiment.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var scrollMain: NestedScrollView
    private lateinit var layoutCatalogRows: LinearLayout
    private lateinit var progressBarHome: ProgressBar

    // Info Panel Views
    private lateinit var imgHeroBackdrop: ImageView
    private lateinit var viewHeroBottomGradient: com.example.animetv.ui.GradientOverlayView
    private lateinit var txtHeroBadge: TextView
    private lateinit var txtHeroTitle: TextView
    private lateinit var txtHeroMeta: TextView
    private lateinit var txtHeroSynopsis: TextView
    private lateinit var btnHeroPlay: Button
    private lateinit var btnHeroFavorite: Button

    // Top Header Navigation Buttons
    private lateinit var btnNavCatalog: Button
    private lateinit var btnNavSearch: Button
    private lateinit var btnNavRefresh: Button
    private lateinit var btnNavSettings: Button

    // Icon-only/focus-reveal-label wrappers around the buttons above (same order as the bar)
    private lateinit var navCatalog: com.example.animetv.ui.IconDrawableRevealButton
    private lateinit var navSearch: com.example.animetv.ui.IconDrawableRevealButton
    private lateinit var navRefresh: com.example.animetv.ui.IconRevealButton
    private lateinit var navSettings: com.example.animetv.ui.IconRevealButton

    private var lastLoadedCatalog: HomeCatalogData? = null

    // Whichever card currently has D-pad focus in the row list — the hero above always reflects
    // this, replacing the old auto-rotating "featured suggestions" hero.
    private var currentFocusedCard: AnimeCard? = null
    private var heroMetaJob: kotlinx.coroutines.Job? = null

    // True once the hero/initial D-pad focus has been seeded from the first loaded row, so a
    // later background refresh (silent revalidation, pull-to-refresh) never yanks focus or the
    // hero away from whatever the user is actually looking at.
    private var hasBoundInitialFocus = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        initViews()
        setupListeners()
        loadInitialCatalog()
    }

    override fun onResume() {
        super.onResume()
        // Refresh Continuar Viendo & Mi Lista rows dynamically
        refreshRowsWithFavorites()
        currentFocusedCard?.let { updateHeroFavoriteButton(it) }
    }

    private fun initViews() {
        scrollMain = findViewById(R.id.scrollMain)
        layoutCatalogRows = findViewById(R.id.layoutCatalogRows)
        progressBarHome = findViewById(R.id.progressBarHome)

        imgHeroBackdrop = findViewById(R.id.imgHeroBackdrop)
        viewHeroBottomGradient = findViewById(R.id.viewHeroBottomGradient)
        setupHeroGradients()
        txtHeroBadge = findViewById(R.id.txtHeroBadge)
        txtHeroTitle = findViewById(R.id.txtHeroTitle)
        txtHeroMeta = findViewById(R.id.txtHeroMeta)
        txtHeroSynopsis = findViewById(R.id.txtHeroSynopsis)
        btnHeroPlay = findViewById(R.id.btnHeroPlay)
        btnHeroFavorite = findViewById(R.id.btnHeroFavorite)

        btnNavCatalog = findViewById(R.id.btnNavCatalog)
        btnNavSearch = findViewById(R.id.btnNavSearch)
        btnNavRefresh = findViewById(R.id.btnNavRefresh)
        btnNavSettings = findViewById(R.id.btnNavSettings)

        navCatalog = com.example.animetv.ui.IconDrawableRevealButton(btnNavCatalog, R.drawable.ic_home, "Home")
        navSearch = com.example.animetv.ui.IconDrawableRevealButton(btnNavSearch, R.drawable.ic_search, "Buscar")
        navRefresh = com.example.animetv.ui.IconRevealButton(btnNavRefresh, "↻", "Actualizar")
        navSettings = com.example.animetv.ui.IconRevealButton(btnNavSettings, "⚙", "Ajustes")
    }

    /**
     * Configures the hero's single gradient overlay: a bottom-only fade into the canvas color so
     * the title block (and the row list beneath the hero) never clash with the artwork, without
     * dimming the rest of the image the way a flat scrim would.
     */
    private fun setupHeroGradients() {
        val canvas = androidx.core.content.ContextCompat.getColor(this, R.color.primary_canvas)
        fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

        viewHeroBottomGradient.setGradient(
            com.example.animetv.ui.GradientOverlayView.Direction.BOTTOM_TO_TOP,
            intArrayOf(
                withAlpha(canvas, 255),
                withAlpha(canvas, 235),
                withAlpha(canvas, 120),
                withAlpha(canvas, 0)
            ),
            floatArrayOf(0f, 0.22f, 0.5f, 1f)
        )
    }

    /** Inflates one row (title + horizontal card list) for [row], wiring focus back to the hero. */
    private fun buildRowView(row: CatalogRow): View {
        val rowView = layoutInflater.inflate(R.layout.item_home_row, layoutCatalogRows, false)
        val txtTitle = rowView.findViewById<TextView>(R.id.txtRowTitle)
        val recycler = rowView.findViewById<RecyclerView>(R.id.recyclerRowCards)
        txtTitle.text = row.title
        recycler.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)

        val onCardLongClick: (AnimeCard) -> Unit = if (row.type == CatalogRowType.CONTINUE_WATCHING) {
            { card -> showRemoveFromHistoryDialog(card) }
        } else {
            { card -> toggleCardFavorite(card) }
        }
        val onCardFocus: (AnimeCard) -> Unit = { card -> updateInfoPanel(card) }
        val onCardClick: (AnimeCard) -> Unit = { card -> DetailActivity.start(this, card) }

        recycler.adapter = if (row.type == CatalogRowType.CONTINUE_WATCHING) {
            ContinueWatchingCardAdapter(row.cards.toMutableList(), onCardClick, onCardLongClick, onCardFocus)
        } else {
            AnimeCardAdapter(row.cards.toMutableList(), onCardClick, onCardLongClick, onCardFocus)
        }
        return rowView
    }

    private fun showRemoveFromHistoryDialog(card: AnimeCard) {
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
            focusFirstCard()
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

    /** Moves D-pad focus to the first card of the first non-empty row, if any is on screen. */
    private fun focusFirstCard() {
        val firstRow = layoutCatalogRows.getChildAt(0) ?: return
        val recycler = firstRow.findViewById<RecyclerView>(R.id.recyclerRowCards) ?: return
        recycler.post {
            recycler.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
        }
    }

    private fun loadInitialCatalog() {
        // 1. Zero-latency instant cache load for the main screen
        val cached = com.example.animetv.core.HomeCatalogCache.load(this)
        if (cached != null && (cached.latinoTrending.isNotEmpty() || cached.recentEpisodes.isNotEmpty())) {
            lastLoadedCatalog = cached
            progressBarHome.visibility = View.GONE
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

    private val heroFocalCropListener = object : RequestListener<Drawable> {
        override fun onLoadFailed(
            e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean
        ): Boolean = false

        override fun onResourceReady(
            resource: Drawable, model: Any, target: Target<Drawable>?, dataSource: DataSource, isFirstResource: Boolean
        ): Boolean {
            imgHeroBackdrop.post { applyHeroFocalCrop(resource) }
            return false
        }
    }

    /**
     * Android's centerCrop always anchors on the image's center — same gap as CSS
     * `object-fit: cover` without `object-position`. This replicates the CSS
     * `object-position: 75% 20%` formula by hand via an ImageView matrix: scale to cover the
     * view exactly like centerCrop, but bias the crop toward the upper-right instead of the
     * middle, so character art positioned there doesn't get chopped off. There's no per-title
     * focal-point data available from TMDB/the scraper, so this is a fixed default rather than a
     * per-movie value.
     */
    private fun applyHeroFocalCrop(drawable: Drawable, focalX: Float = 0.75f, focalY: Float = 0.20f) {
        val vw = imgHeroBackdrop.width.toFloat()
        val vh = imgHeroBackdrop.height.toFloat()
        val bw = drawable.intrinsicWidth.toFloat()
        val bh = drawable.intrinsicHeight.toFloat()
        if (vw <= 0f || vh <= 0f || bw <= 0f || bh <= 0f) return

        val scale = maxOf(vw / bw, vh / bh)
        val dx = (vw - bw * scale) * focalX
        val dy = (vh - bh * scale) * focalY

        val matrix = Matrix()
        matrix.setScale(scale, scale)
        matrix.postTranslate(dx, dy)
        imgHeroBackdrop.scaleType = ImageView.ScaleType.MATRIX
        imgHeroBackdrop.imageMatrix = matrix
    }

    /**
     * Updates the hero to reflect [card] — called whenever a card in any row gains D-pad focus.
     * Replaces the old auto-rotating "featured suggestions" hero: it's now always a live
     * reflection of user navigation, not a timer.
     */
    private fun updateInfoPanel(card: AnimeCard) {
        currentFocusedCard = card
        txtHeroTitle.text = card.title
        txtHeroSynopsis.text = card.synopsis.ifEmpty { "Contenido disponible en SoloLatino en alta definición y audio latino." }
        txtHeroBadge.text = "★ DESTACADO DE LA SEMANA"
        loadHeroMeta(card)

        val imageToLoad = card.backdropUrl.ifEmpty { card.posterUrl }
        if (imageToLoad.isNotEmpty()) {
            Glide.with(this)
                .load(imageToLoad)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .override(Target.SIZE_ORIGINAL)
                .listener(heroFocalCropListener)
                .transition(DrawableTransitionOptions.withCrossFade(250))
                .into(imgHeroBackdrop)
        }

        btnHeroPlay.setOnClickListener {
            DetailActivity.start(this, card)
        }

        updateHeroFavoriteButton(card)
        btnHeroFavorite.setOnClickListener {
            toggleCardFavorite(card)
            updateHeroFavoriteButton(card)
        }
    }

    /**
     * Quick-scan metadata line (year • rating • runtime/seasons) for the currently focused card.
     * Resolved lazily per-card as focus moves, and the row stays hidden until a confident TMDB
     * match comes back — silently doing nothing on failure is safer than showing a wrong or
     * franchise-mismatched year/rating.
     */
    private fun loadHeroMeta(card: AnimeCard) {
        txtHeroMeta.visibility = View.GONE
        heroMetaJob?.cancel()
        heroMetaJob = lifecycleScope.launch {
            val meta = try {
                com.example.animetv.core.tmdb.TmdbMetadataRepository.searchMetadata(this@MainActivity, card.title)
            } catch (e: Exception) {
                null
            }
            if (meta == null || currentFocusedCard?.detailUrl != card.detailUrl) return@launch

            val parts = mutableListOf<String>()
            if (meta.releaseYear.isNotEmpty()) parts.add(meta.releaseYear)
            if (meta.certification.isNotEmpty()) parts.add(meta.certification)
            if (meta.mediaType == "movie" && meta.runtimeMinutes > 0) {
                val h = meta.runtimeMinutes / 60
                val m = meta.runtimeMinutes % 60
                parts.add(if (h > 0) "${h}h ${m}min" else "${m}min")
            } else if (meta.mediaType == "tv" && meta.numberOfSeasons > 0) {
                parts.add(if (meta.numberOfSeasons == 1) "1 Temporada" else "${meta.numberOfSeasons} Temporadas")
            }

            if (parts.isNotEmpty()) {
                txtHeroMeta.text = parts.joinToString("   •   ")
                txtHeroMeta.visibility = View.VISIBLE
            }
            // The scraper's own listing cards never carry a synopsis (only the detail page
            // does), so the panel always fell back to a generic placeholder line. TMDB's real
            // overview is now available here from the same lookup — use it once it resolves.
            if (meta.overview.isNotEmpty()) {
                txtHeroSynopsis.text = meta.overview
            }
            // The scraper's listing cards only carry a tiny w185 poster thumbnail (~185px wide,
            // meant for small grid tiles) — stretched across the full-width panel it looks soft.
            // Swap in TMDB's real w1280 backdrop once it resolves, cross-fading over the
            // low-res placeholder that's already on screen.
            val hdBackdrop = meta.backdropUrl.ifEmpty { meta.posterUrl }
            if (hdBackdrop.isNotEmpty()) {
                Glide.with(this@MainActivity)
                    .load(hdBackdrop)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .override(Target.SIZE_ORIGINAL)
                    .listener(heroFocalCropListener)
                    .transition(DrawableTransitionOptions.withCrossFade(400))
                    .into(imgHeroBackdrop)
            }
        }
    }

    private fun updateHeroFavoriteButton(card: AnimeCard) {
        val isFav = FavoritesStore.isFavorite(this, card.detailUrl)
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
                    episodeBadge = badge,
                    progressPercent = progressPct
                )
            }.distinctBy { it.detailUrl }

            if (continueCards.isNotEmpty()) {
                allRows.add(
                    CatalogRow(
                        title = "▶ Continuar Viendo",
                        cards = continueCards,
                        type = CatalogRowType.CONTINUE_WATCHING
                    )
                )
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

        layoutCatalogRows.removeAllViews()
        for (row in allRows) {
            if (row.cards.isEmpty()) continue
            layoutCatalogRows.addView(buildRowView(row))
        }

        // Seed the hero + initial D-pad focus from the very first card once, on first load only —
        // a later silent/background refresh must never yank focus or the hero away from whatever
        // the user is currently looking at.
        if (!hasBoundInitialFocus) {
            val firstCard = allRows.firstOrNull { it.cards.isNotEmpty() }?.cards?.firstOrNull()
            if (firstCard != null) {
                hasBoundInitialFocus = true
                updateInfoPanel(firstCard)
                focusFirstCard()
            }
        }
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
