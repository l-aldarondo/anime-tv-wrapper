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
import java.util.Locale

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
    private lateinit var viewHeroLeftGradient: com.example.animetv.ui.GradientOverlayView
    private lateinit var viewHeroBottomGradient: com.example.animetv.ui.GradientOverlayView
    private lateinit var imgHeroLogo: ImageView
    private lateinit var txtHeroTitle: TextView
    private lateinit var txtHeroMeta: TextView
    private lateinit var layoutHeroBadges: LinearLayout
    private lateinit var txtHeroNextUpBadge: TextView
    private lateinit var layoutHeroImdb: LinearLayout
    private lateinit var txtHeroImdbScore: TextView
    private lateinit var txtHeroSynopsis: TextView

    // Top Header Navigation Buttons
    private lateinit var btnNavCatalog: Button
    private lateinit var btnNavMyList: Button
    private lateinit var btnNavSearch: Button
    private lateinit var btnNavRefresh: Button
    private lateinit var btnNavSettings: Button

    // Icon-only/focus-reveal-label wrappers around the buttons above (same order as the bar)
    private lateinit var navCatalog: com.example.animetv.ui.IconDrawableRevealButton
    private lateinit var navMyList: com.example.animetv.ui.IconDrawableRevealButton
    private lateinit var navSearch: com.example.animetv.ui.IconDrawableRevealButton
    private lateinit var navRefresh: com.example.animetv.ui.IconDrawableRevealButton
    private lateinit var navSettings: com.example.animetv.ui.IconDrawableRevealButton

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
    }

    private fun initViews() {
        scrollMain = findViewById(R.id.scrollMain)
        layoutCatalogRows = findViewById(R.id.layoutCatalogRows)
        progressBarHome = findViewById(R.id.progressBarHome)

        imgHeroBackdrop = findViewById(R.id.imgHeroBackdrop)
        viewHeroLeftGradient = findViewById(R.id.viewHeroLeftGradient)
        viewHeroBottomGradient = findViewById(R.id.viewHeroBottomGradient)
        setupHeroGradients()
        imgHeroLogo = findViewById(R.id.imgHeroLogo)
        txtHeroTitle = findViewById(R.id.txtHeroTitle)
        txtHeroMeta = findViewById(R.id.txtHeroMeta)
        layoutHeroBadges = findViewById(R.id.layoutHeroBadges)
        txtHeroNextUpBadge = findViewById(R.id.txtHeroNextUpBadge)
        layoutHeroImdb = findViewById(R.id.layoutHeroImdb)
        txtHeroImdbScore = findViewById(R.id.txtHeroImdbScore)
        txtHeroSynopsis = findViewById(R.id.txtHeroSynopsis)

        btnNavCatalog = findViewById(R.id.btnNavCatalog)
        btnNavMyList = findViewById(R.id.btnNavMyList)
        btnNavSearch = findViewById(R.id.btnNavSearch)
        btnNavRefresh = findViewById(R.id.btnNavRefresh)
        btnNavSettings = findViewById(R.id.btnNavSettings)

        navCatalog = com.example.animetv.ui.IconDrawableRevealButton(btnNavCatalog, R.drawable.ic_home, "Home")
        navMyList = com.example.animetv.ui.IconDrawableRevealButton(btnNavMyList, R.drawable.ic_star, "Mi Lista")
        navSearch = com.example.animetv.ui.IconDrawableRevealButton(btnNavSearch, R.drawable.ic_search, "Buscar")
        navRefresh = com.example.animetv.ui.IconDrawableRevealButton(btnNavRefresh, R.drawable.ic_refresh, "Actualizar")
        navSettings = com.example.animetv.ui.IconDrawableRevealButton(btnNavSettings, R.drawable.ic_settings, "Ajustes")
    }

    /**
     * Configures the hero's two gradient overlays:
     * 1. Left-to-right fade so title, metadata, and synopsis are crisp over the backdrop art,
     *    fading out quickly before the right side so artwork is 100% bright and vibrant (Nuvio style).
     * 2. Low bottom fade that cleanly transitions into the rows without darkening the artwork above.
     */
    private fun setupHeroGradients() {
        val canvas = androidx.core.content.ContextCompat.getColor(this, R.color.primary_canvas)
        fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

        // Left-to-right fade: protects title & synopsis on the left without darkening the center/right art
        viewHeroLeftGradient.setGradient(
            com.example.animetv.ui.GradientOverlayView.Direction.LEFT_TO_RIGHT,
            intArrayOf(
                withAlpha(canvas, 230),
                withAlpha(canvas, 175),
                withAlpha(canvas, 80),
                withAlpha(canvas, 15),
                withAlpha(canvas, 0)
            ),
            floatArrayOf(0f, 0.22f, 0.42f, 0.65f, 0.85f)
        )

        // Bottom-to-top fade: keeps the backdrop clearly visible behind the first row ("Continuar Viendo")
        // and only deepens to canvas color on the lower rows
        viewHeroBottomGradient.setGradient(
            com.example.animetv.ui.GradientOverlayView.Direction.BOTTOM_TO_TOP,
            intArrayOf(
                withAlpha(canvas, 245),
                withAlpha(canvas, 150),
                withAlpha(canvas, 50),
                withAlpha(canvas, 0)
            ),
            floatArrayOf(0f, 0.25f, 0.55f, 0.85f)
        )
    }

    /** Inflates one row (title + horizontal card list) for [row], wiring focus back to the hero. */
    private fun buildRowView(row: CatalogRow): View {
        val rowView = layoutInflater.inflate(R.layout.item_home_row, layoutCatalogRows, false)
        val txtTitle = rowView.findViewById<TextView>(R.id.txtRowTitle)
        val recycler = rowView.findViewById<RecyclerView>(R.id.recyclerRowCards)
        txtTitle.text = row.title
        recycler.layoutManager = com.example.animetv.ui.TvRowLayoutManager(this)

        val onCardLongClick: (AnimeCard) -> Unit = if (row.type == CatalogRowType.CONTINUE_WATCHING) {
            { card -> showRemoveFromHistoryDialog(card) }
        } else {
            { card -> toggleCardFavorite(card) }
        }
        val onCardFocus: (AnimeCard) -> Unit = { card ->
            updateInfoPanel(card)
            rowView.post {
                val rowTop = rowView.top
                val rowBottom = rowView.bottom
                val scrollY = scrollMain.scrollY
                val vHeight = scrollMain.height
                if (vHeight > 0 && (rowBottom > scrollY + vHeight || rowTop < scrollY)) {
                    val targetY = (rowTop - 20).coerceAtLeast(0)
                    scrollMain.smoothScrollTo(0, targetY)
                }
            }
        }
        val onCardClick: (AnimeCard) -> Unit = { card ->
            if (card.detailUrl.isNotEmpty()) {
                DetailActivity.start(this, card)
            } else {
                Toast.makeText(this, "Mantén presionado cualquier póster para añadirlo a Mi Lista", Toast.LENGTH_SHORT).show()
            }
        }

        recycler.adapter = if (row.type == CatalogRowType.CONTINUE_WATCHING) {
            ContinueWatchingCardAdapter(row.cards.toMutableList(), onCardClick, onCardLongClick, onCardFocus)
        } else {
            AnimeCardAdapter(onCardClick, onCardLongClick, onCardFocus).also { it.submitList(row.cards) }
        }
        return rowView
    }

    private fun showRemoveFromHistoryDialog(card: AnimeCard) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Quitar de Continuar Viendo")
            .setMessage("¿Deseas quitar \"${card.title}\" del historial de visualización?")
            .setPositiveButton("Quitar") { _, _ ->
                com.example.animetv.core.history.PlaybackHistoryStore.removeRecord(this, card.detailUrl)
                refreshRowsWithFavorites()
                Toast.makeText(this, "Eliminado de Continuar Viendo", Toast.LENGTH_SHORT).show()
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

        btnNavMyList.setOnClickListener {
            scrollToMyListRow()
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

    private fun scrollToMyListRow() {
        for (i in 0 until layoutCatalogRows.childCount) {
            val rowView = layoutCatalogRows.getChildAt(i)
            val title = rowView.findViewById<TextView>(R.id.txtRowTitle)?.text?.toString() ?: ""
            if (title.contains("Mi Lista", ignoreCase = true)) {
                val recycler = rowView.findViewById<RecyclerView>(R.id.recyclerRowCards)
                scrollMain.smoothScrollTo(0, rowView.top)
                recycler?.post {
                    recycler.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                }
                return
            }
        }
        Toast.makeText(this, "Mantén presionado cualquier póster para guardarlo en Mi Lista", Toast.LENGTH_SHORT).show()
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

        // ClearArt / Official Logo Handling (Nuvio signature)
        if (card.logoUrl.isNotEmpty()) {
            Glide.with(this)
                .load(card.logoUrl)
                .override(Target.SIZE_ORIGINAL)
                .listener(object : RequestListener<Drawable> {
                    override fun onLoadFailed(
                        e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean
                    ): Boolean {
                        if (currentFocusedCard?.detailUrl == card.detailUrl) {
                            imgHeroLogo.visibility = View.GONE
                            txtHeroTitle.visibility = View.VISIBLE
                            txtHeroTitle.text = card.title
                        }
                        return false
                    }

                    override fun onResourceReady(
                        resource: Drawable, model: Any, target: Target<Drawable>?, dataSource: DataSource, isFirstResource: Boolean
                    ): Boolean {
                        if (currentFocusedCard?.detailUrl == card.detailUrl) {
                            imgHeroLogo.visibility = View.VISIBLE
                            txtHeroTitle.visibility = View.GONE
                        }
                        return false
                    }
                })
                .fitCenter()
                .transition(DrawableTransitionOptions.withCrossFade(200))
                .into(imgHeroLogo)
        } else {
            imgHeroLogo.visibility = View.GONE
            txtHeroTitle.visibility = View.VISIBLE
            txtHeroTitle.text = card.title
        }

        txtHeroSynopsis.text = if (card.synopsis.isNotEmpty()) card.synopsis else "Cargando información..."
        layoutHeroBadges.visibility = View.GONE
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
    }

    /**
     * Quick-scan metadata line (year • rating • runtime/seasons) for the currently focused card.
     * Resolved lazily per-card as focus moves, and the row stays hidden until a confident TMDB
     * match comes back — silently doing nothing on failure is safer than showing a wrong or
     * franchise-mismatched year/rating.
     */
    private fun loadHeroMeta(card: AnimeCard) {
        txtHeroMeta.visibility = View.GONE
        layoutHeroBadges.visibility = View.GONE
        heroMetaJob?.cancel()
        heroMetaJob = lifecycleScope.launch {
            val isMovie = card.detailUrl.contains("/pelicula/") || card.episodeBadge.equals("Película", ignoreCase = true)
            val isLiveAction = card.source.contains("SoloLatino", ignoreCase = true) && !card.detailUrl.contains("/animes")
            val meta = try {
                com.example.animetv.core.tmdb.TmdbMetadataRepository.searchMetadata(
                    this@MainActivity,
                    card.title,
                    isMovie = isMovie,
                    isLiveAction = isLiveAction
                )
            } catch (e: Exception) {
                null
            }
            if (currentFocusedCard?.detailUrl != card.detailUrl) return@launch

            if (meta != null) {
                // If official logo found via TMDB/Metahub, promote it to hero logo
                if (meta.logoUrl.isNotEmpty()) {
                    Glide.with(this@MainActivity)
                        .load(meta.logoUrl)
                        .override(Target.SIZE_ORIGINAL)
                        .listener(object : RequestListener<Drawable> {
                            override fun onLoadFailed(
                                e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean
                            ): Boolean {
                                if (currentFocusedCard?.detailUrl == card.detailUrl) {
                                    imgHeroLogo.visibility = View.GONE
                                    txtHeroTitle.visibility = View.VISIBLE
                                    txtHeroTitle.text = card.title
                                }
                                return false
                            }

                            override fun onResourceReady(
                                resource: Drawable, model: Any, target: Target<Drawable>?, dataSource: DataSource, isFirstResource: Boolean
                            ): Boolean {
                                if (currentFocusedCard?.detailUrl == card.detailUrl) {
                                    imgHeroLogo.visibility = View.VISIBLE
                                    txtHeroTitle.visibility = View.GONE
                                }
                                return false
                            }
                        })
                        .fitCenter()
                        .transition(DrawableTransitionOptions.withCrossFade(200))
                        .into(imgHeroLogo)
                } else {
                    imgHeroLogo.visibility = View.GONE
                    txtHeroTitle.visibility = View.VISIBLE
                    txtHeroTitle.text = card.title
                }

                val parts = mutableListOf<String>()
                if (card.subtitle.isNotEmpty()) {
                    parts.add(card.subtitle)
                }
                if (meta.genres.isNotEmpty()) {
                    parts.add(meta.genres.take(2).joinToString(" / "))
                } else if (meta.isAnimation) {
                    parts.add("Animación")
                }
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

                // Show NEXT UP and IMDb rating badges (Nuvio style)
                val isContinueOrNext = card.source.contains("Continuar", ignoreCase = true) ||
                        card.episodeBadge.contains("left", ignoreCase = true) ||
                        card.episodeBadge.contains("Next", ignoreCase = true) ||
                        card.episodeBadge.contains("Ep", ignoreCase = true)
                txtHeroNextUpBadge.visibility = if (isContinueOrNext) View.VISIBLE else View.GONE

                val imdbScore = when {
                    meta.voteAverage > 0 -> String.format(Locale.US, "%.1f", meta.voteAverage)
                    card.rating.isNotEmpty() -> card.rating.replace(Regex("""[^\d.]"""), "")
                    else -> ""
                }
                if (imdbScore.isNotEmpty() && imdbScore != "0.0") {
                    txtHeroImdbScore.text = imdbScore
                    layoutHeroImdb.visibility = View.VISIBLE
                } else {
                    layoutHeroImdb.visibility = View.GONE
                }
                layoutHeroBadges.visibility = View.VISIBLE

                if (meta.overview.isNotEmpty()) {
                    txtHeroSynopsis.text = meta.overview
                }
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
            } else {
                // If TMDB didn't match and card synopsis is empty, try fetching Scraper detail
                if (card.synopsis.isEmpty()) {
                    try {
                        val detail = CatalogRepository.getAnimeDetail(card)
                        if (detail.synopsis.isNotEmpty() && currentFocusedCard?.detailUrl == card.detailUrl) {
                            txtHeroSynopsis.text = detail.synopsis
                        }
                    } catch (e: Exception) {
                        if (currentFocusedCard?.detailUrl == card.detailUrl && txtHeroSynopsis.text == "Cargando información...") {
                            txtHeroSynopsis.text = "Disfruta de ${card.title} en alta definición con audio latino."
                        }
                    }
                }
            }
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

                val cleanDetailUrl = when {
                    rec.animeDetailUrl.isNotEmpty() && !rec.animeDetailUrl.contains("/temporada-") -> rec.animeDetailUrl
                    rec.animeDetailUrl.contains("/temporada-") -> rec.animeDetailUrl.substringBefore("/temporada-")
                    rec.episodeUrl.contains("/temporada-") -> rec.episodeUrl.substringBefore("/temporada-")
                    else -> rec.animeDetailUrl.ifEmpty { rec.episodeUrl }
                }

                // Look up matching card in current catalog to recover proper show title, synopsis, and posters
                val catalogCard = findCardInCatalog(data, cleanDetailUrl, rec.animeTitle)

                var resolvedTitle = when {
                    rec.animeTitle.isNotEmpty() && rec.animeTitle != "Película Completa" && !rec.animeTitle.matches(Regex("""^\d+\.\s.*""")) -> rec.animeTitle
                    catalogCard != null && catalogCard.title.isNotEmpty() -> catalogCard.title
                    rec.episodeTitle.isNotEmpty() && rec.episodeTitle != "Película Completa" && !rec.episodeTitle.matches(Regex("""^\d+\.\s.*""")) -> rec.episodeTitle
                    else -> ""
                }

                // Fallback: extract clean title from URL slug if still generic or blank
                if (resolvedTitle.isEmpty() || resolvedTitle == "Película Completa") {
                    val slug = cleanDetailUrl.trimEnd('/').substringAfterLast('/')
                    if (slug.isNotEmpty()) {
                        resolvedTitle = slug.replace("-", " ").split(" ")
                            .filter { it.isNotEmpty() }
                            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
                    }
                }
                if (resolvedTitle.isEmpty()) {
                    resolvedTitle = rec.animeTitle.ifEmpty { rec.episodeTitle }
                }

                val isMovie = cleanDetailUrl.contains("/pelicula/") || rec.episodeTitle.contains("Película", ignoreCase = true)
                val remainingMs = (rec.durationMs - rec.positionMs).coerceAtLeast(0)
                val remainingMinutes = (remainingMs / (1000 * 60)).toInt()

                // Nuvio-style smart badges: "21m left", "Next Up", or "2h 10m left"
                val badge = when {
                    progressPct in 90..100 -> "Next Up"
                    remainingMinutes in 1..90 -> "${remainingMinutes}m left"
                    isMovie && remainingMinutes > 90 -> {
                        val h = remainingMinutes / 60
                        val m = remainingMinutes % 60
                        if (m > 0) "${h}h ${m}m left" else "${h}h left"
                    }
                    progressPct > 0 -> "$progressPct%"
                    rec.episodeNumber > 0 -> "Ep ${rec.episodeNumber}"
                    else -> "Next Up"
                }

                val resolvedSubtitle = when {
                    rec.episodeTitle.isNotEmpty() && rec.episodeTitle != "Película Completa" && rec.episodeTitle != resolvedTitle -> {
                        if (rec.episodeNumber > 0 && !rec.episodeTitle.contains("Ep", true)) {
                            "S1 E${rec.episodeNumber} • ${rec.episodeTitle}"
                        } else {
                            rec.episodeTitle
                        }
                    }
                    rec.episodeNumber > 0 -> "S1 E${rec.episodeNumber}"
                    else -> ""
                }

                var resolvedPoster = if (CoverUtils.isValidCover(rec.posterUrl)) rec.posterUrl.trim() else ""
                if (resolvedPoster.isEmpty()) {
                    val catalogPoster = catalogCard?.posterUrl?.takeIf { CoverUtils.isValidCover(it) }
                        ?: findCoverInCatalog(data, cleanDetailUrl, resolvedTitle)
                    if (catalogPoster.isNotEmpty()) {
                        resolvedPoster = catalogPoster
                        PlaybackHistoryStore.updatePoster(this, rec.animeDetailUrl, rec.episodeUrl, catalogPoster)
                    } else {
                        missingCovers.add(rec)
                    }
                }

                val resolvedSynopsis = rec.synopsis.ifEmpty { catalogCard?.synopsis ?: "" }

                // Auto-repair stored record if it was saved with blank or corrupted title/synopsis
                if ((rec.animeTitle.isEmpty() || rec.animeTitle == "Película Completa" || rec.animeTitle.matches(Regex("""^\d+\.\s.*""")) || rec.synopsis.isEmpty()) &&
                    (resolvedTitle.isNotEmpty() && resolvedTitle != "Película Completa")) {
                    PlaybackHistoryStore.updateRecordMetadata(
                        this,
                        animeDetailUrl = rec.animeDetailUrl,
                        episodeUrl = rec.episodeUrl,
                        newTitle = resolvedTitle,
                        newSynopsis = resolvedSynopsis,
                        newPosterUrl = resolvedPoster
                    )
                }

                AnimeCard(
                    id = cleanDetailUrl,
                    title = resolvedTitle,
                    posterUrl = resolvedPoster,
                    detailUrl = cleanDetailUrl,
                    source = rec.source.ifEmpty { "Continuar" },
                    episodeBadge = badge,
                    synopsis = resolvedSynopsis,
                    backdropUrl = catalogCard?.backdropUrl ?: "",
                    progressPercent = progressPct,
                    subtitle = resolvedSubtitle,
                    logoUrl = catalogCard?.logoUrl ?: ""
                )
            }.distinctBy { it.detailUrl }

            if (continueCards.isNotEmpty()) {
                allRows.add(
                    CatalogRow(
                        title = "Continuar Viendo",
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
        val favCards = if (storedFavorites.isNotEmpty()) {
            storedFavorites.map { fav ->
                AnimeCard(
                    id = fav.url,
                    title = fav.title,
                    posterUrl = fav.poster,
                    detailUrl = fav.url,
                    source = if (fav.source.isNotEmpty()) fav.source else "Mi Lista",
                    episodeBadge = "Guardado"
                )
            }
        } else {
            listOf(
                AnimeCard(
                    id = "empty_favorites_guide",
                    title = "Tu lista está vacía",
                    posterUrl = "",
                    detailUrl = "",
                    source = "Mi Lista",
                    episodeBadge = "+ Añadir",
                    synopsis = "Añade tus series o películas favoritas manteniendo presionado el botón central del control remoto en cualquier póster, o con el botón '+' en la pantalla de Detalles."
                )
            )
        }
        allRows.add(CatalogRow(title = "Mi Lista", cards = favCards))

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
            allRows.add(CatalogRow(title = "SoloAnime (Audio Latino)", cards = data.latinoTrending))
        }

        // 5. Tokyo MX y TV Tokyo (después de Disney+ y SoloAnime)
        for (sec in tokyoSections) {
            if (sec.cards.isNotEmpty()) {
                allRows.add(sec)
            }
        }

        // 6. 9Anime HD
        if (data.nineAnimeTrending.isNotEmpty()) {
            allRows.add(CatalogRow(title = "9Anime HD", cards = data.nineAnimeTrending))
        }

        // 7. JKAnime
        if (data.recentEpisodes.isNotEmpty()) {
            allRows.add(CatalogRow(title = "JKAnime", cards = data.recentEpisodes))
        }

        // 8. GogoAnime
        if (data.gogoTrending.isNotEmpty()) {
            allRows.add(CatalogRow(title = "GogoAnime", cards = data.gogoTrending))
        }

        // 9. Populares / Recomendados
        if (data.latinoTrending.size > 6) {
            allRows.add(CatalogRow(title = "Series Populares", cards = data.latinoTrending.reversed()))
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

        recycler.layoutManager = com.example.animetv.ui.TvRowLayoutManager(this)
        val searchAdapter = AnimeCardAdapter(onCardClick = { card ->
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

    private fun findCardInCatalog(data: HomeCatalogData, detailUrl: String, title: String): AnimeCard? {
        val cleanDetail = detailUrl.substringBefore("/temporada-").trim().trimEnd('/')
        val allCards = sequence {
            yieldAll(data.latinoTrending)
            yieldAll(data.soloLatinoSections.flatMap { it.cards })
            yieldAll(data.recentEpisodes)
            yieldAll(data.soloStreamTrending)
            yieldAll(data.nineAnimeTrending)
            yieldAll(data.animeYtTrending)
            yieldAll(data.gogoTrending)
        }
        return allCards.firstOrNull { card ->
            val cardClean = card.detailUrl.substringBefore("/temporada-").trim().trimEnd('/')
            (cleanDetail.isNotEmpty() && (cardClean.equals(cleanDetail, ignoreCase = true) || card.id.equals(cleanDetail, ignoreCase = true))) ||
            (title.isNotEmpty() && title != "Película Completa" && !title.matches(Regex("""^\d+\.\s.*""")) && card.title.equals(title, ignoreCase = true))
        }
    }

    private fun findCoverInCatalog(data: HomeCatalogData, detailUrl: String, title: String): String {
        val match = findCardInCatalog(data, detailUrl, title)
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
                        val newPoster = if (CoverUtils.isValidCover(detail.posterUrl)) detail.posterUrl.trim() else ""
                        val newTitle = if (detail.title.isNotEmpty() && detail.title != "Película Completa") detail.title else ""
                        val newSynopsis = detail.synopsis
                        if (newPoster.isNotEmpty() || newTitle.isNotEmpty() || newSynopsis.isNotEmpty()) {
                            PlaybackHistoryStore.updateRecordMetadata(
                                this@MainActivity,
                                animeDetailUrl = rec.animeDetailUrl,
                                episodeUrl = rec.episodeUrl,
                                newTitle = newTitle,
                                newSynopsis = newSynopsis,
                                newPosterUrl = newPoster
                            )
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
