package com.example.animetv

import android.app.Dialog
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
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
import androidx.appcompat.app.AppCompatActivity
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
 * 100% Native Android TV application entry point (Nuvio/Stremio "board" architecture).
 * The upper info panel always reflects whichever card currently has D-pad focus, and only one
 * catalog row is on screen at a time — DOWN/UP page between rows instead of the whole page
 * scrolling. Features hardware D-Pad focus navigation, background HTML scrapers, and native
 * ExoPlayer streaming.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var recyclerCurrentRow: RecyclerView
    private lateinit var txtCurrentRowTitle: TextView
    private lateinit var progressBarHome: ProgressBar

    // Info Panel Views
    private lateinit var imgHeroBackdrop: ImageView
    private lateinit var viewHeroTextGradient: com.example.animetv.ui.GradientOverlayView
    private lateinit var viewHeroRowGradient: com.example.animetv.ui.GradientOverlayView
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

    // The full ordered list of rows, and which one is currently the single visible row.
    private var displayedRows: List<CatalogRow> = emptyList()
    private var currentRowIndex = 0

    // Whichever card currently has D-pad focus in the visible row — the info panel above always
    // reflects this, replacing the old auto-rotating "featured suggestions" hero.
    private var currentFocusedCard: AnimeCard? = null
    private var heroMetaJob: kotlinx.coroutines.Job? = null

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
        currentFocusedCard?.let { updateHeroFavoriteButton(it) }
    }

    private fun initViews() {
        recyclerCurrentRow = findViewById(R.id.recyclerCurrentRow)
        txtCurrentRowTitle = findViewById(R.id.txtCurrentRowTitle)
        progressBarHome = findViewById(R.id.progressBarHome)

        imgHeroBackdrop = findViewById(R.id.imgHeroBackdrop)
        viewHeroTextGradient = findViewById(R.id.viewHeroTextGradient)
        viewHeroRowGradient = findViewById(R.id.viewHeroRowGradient)
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
     * Configures the info panel's two targeted gradient overlays. Instead of a flat dim over
     * the whole backdrop (which looks muddy and hides artwork uniformly), this keeps the image
     * at full brightness and only darkens: (1) a left-side pocket wide enough for the title/
     * synopsis/buttons to stay legible, dissolving away by the right edge, and (2) a thin strip
     * along the bottom so the artwork doesn't clash with the row title beneath it.
     */
    private fun setupHeroGradients() {
        val canvas = androidx.core.content.ContextCompat.getColor(this, R.color.primary_canvas)
        fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

        viewHeroTextGradient.setGradient(
            com.example.animetv.ui.GradientOverlayView.Direction.LEFT_TO_RIGHT,
            intArrayOf(
                withAlpha(canvas, 255),
                withAlpha(canvas, 255),
                withAlpha(canvas, 204),
                withAlpha(canvas, 0)
            ),
            floatArrayOf(0f, 0.35f, 0.55f, 1f)
        )

        viewHeroRowGradient.setGradient(
            com.example.animetv.ui.GradientOverlayView.Direction.BOTTOM_TO_TOP,
            intArrayOf(
                withAlpha(canvas, 255),
                withAlpha(canvas, 153),
                withAlpha(canvas, 0)
            ),
            floatArrayOf(0f, 0.25f, 0.6f)
        )
    }

    private fun setupRecyclerView() {
        recyclerCurrentRow.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
    }

    /**
     * Only one row is ever inflated, so there's no sibling row for the platform's default
     * focus-search to find below/above the visible one. Android calls an Activity's onKeyDown
     * specifically when the whole view hierarchy left a key unconsumed (Activity.dispatchKeyEvent
     * tries the focused view chain first via the window, and only falls back to this method if
     * nothing handled it) — exactly the "DOWN/UP dead-ended, nothing to focus in that direction"
     * case here, so this is where paging between rows belongs. UP from row 0 never reaches this:
     * the info panel's buttons sit directly above in the layout, so default focus-search already
     * finds them first.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (currentRowIndex < displayedRows.size - 1) {
                    showRow(currentRowIndex + 1)
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (currentRowIndex > 0) {
                    showRow(currentRowIndex - 1)
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    /** Binds row [index] as the single visible row, focusing card [focusIndex] within it. */
    private fun showRow(index: Int, focusIndex: Int = 0) {
        if (displayedRows.isEmpty()) return
        val clampedIndex = index.coerceIn(0, displayedRows.size - 1)
        currentRowIndex = clampedIndex
        val row = displayedRows[clampedIndex]
        txtCurrentRowTitle.text = row.title

        val onCardLongClick: (AnimeCard) -> Unit = if (row.title.contains("Continuar Viendo", ignoreCase = true)) {
            { card -> showRemoveFromHistoryDialog(card) }
        } else {
            { card -> toggleCardFavorite(card) }
        }
        val onCardFocus: (AnimeCard) -> Unit = { card -> updateInfoPanel(card) }
        val onCardClick: (AnimeCard) -> Unit = { card -> DetailActivity.start(this, card) }

        recyclerCurrentRow.adapter = if (row.type == CatalogRowType.CONTINUE_WATCHING) {
            ContinueWatchingCardAdapter(row.cards.toMutableList(), onCardClick, onCardLongClick, onCardFocus)
        } else {
            AnimeCardAdapter(row.cards.toMutableList(), onCardClick, onCardLongClick, onCardFocus)
        }

        val clampedFocus = focusIndex.coerceIn(0, row.cards.size - 1)
        recyclerCurrentRow.post {
            val holder = recyclerCurrentRow.findViewHolderForAdapterPosition(clampedFocus)
            holder?.itemView?.requestFocus()
        }
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
            showRow(0)
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
     * `object-fit: cover` without `object-position`. Since the panel's text sits on the left
     * (see the text-protection gradient), centering the crop chops off whatever character art
     * sits on the right half of a wide backdrop. This replicates the CSS
     * `object-position: 75% 20%` formula by hand via an ImageView matrix: scale to cover the
     * view exactly like centerCrop, but bias the crop toward the upper-right instead of the
     * middle. There's no per-title focal-point data available from TMDB/the scraper, so this is
     * a fixed default rather than a per-movie value.
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
     * Updates the info panel to reflect [card] — called whenever a card in the visible row gains
     * D-pad focus. Replaces the old auto-rotating "featured suggestions" hero: the panel is now
     * always a live reflection of user navigation, not a timer.
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

        val isFirstLoad = displayedRows.isEmpty()
        displayedRows = allRows
        if (allRows.isNotEmpty()) {
            showRow(if (isFirstLoad) 0 else currentRowIndex)
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
