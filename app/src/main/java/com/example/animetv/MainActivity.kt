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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.example.animetv.core.CatalogRepository
import com.example.animetv.core.HomeCatalogData
import com.example.animetv.core.model.AnimeCard
import com.example.animetv.ui.DetailActivity
import com.example.animetv.ui.adapter.AnimeCardAdapter
import com.example.animetv.ui.adapter.CatalogRow
import com.example.animetv.ui.adapter.CatalogRowAdapter
import kotlinx.coroutines.launch

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
    private lateinit var btnNavMyList: Button
    private lateinit var btnNavSearch: Button
    private lateinit var btnNavRefresh: Button

    private lateinit var catalogRowAdapter: CatalogRowAdapter
    private var featuredAnime: AnimeCard? = null
    private var lastLoadedCatalog: HomeCatalogData? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        initViews()
        setupRecyclerView()
        setupListeners()
        loadCatalog(forceRefresh = false)
    }

    override fun onResume() {
        super.onResume()
        // Refresh Favorites row when returning to MainActivity
        refreshRowsWithFavorites()
        featuredAnime?.let { updateHeroFavoriteButton(it) }
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
        btnNavMyList = findViewById(R.id.btnNavMyList)
        btnNavSearch = findViewById(R.id.btnNavSearch)
        btnNavRefresh = findViewById(R.id.btnNavRefresh)
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
            }
        )
        recyclerCatalogRows.adapter = catalogRowAdapter
    }

    private fun setupListeners() {
        btnNavCatalog.setBackgroundResource(R.drawable.bg_topbar_active_source)
        btnNavCatalog.requestFocus()

        btnNavCatalog.setOnClickListener {
            scrollMain.smoothScrollTo(0, 0)
            btnHeroPlay.requestFocus()
        }

        btnNavMyList.setOnClickListener {
            // Scroll down to catalog rows
            scrollMain.smoothScrollTo(0, 400)
            recyclerCatalogRows.requestFocus()
        }

        btnNavSearch.setOnClickListener {
            showSearchDialog()
        }

        btnNavRefresh.setOnClickListener {
            loadCatalog(forceRefresh = true)
        }
    }

    private fun loadCatalog(forceRefresh: Boolean) {
        progressBarHome.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val data = CatalogRepository.loadHomeContent(forceRefresh = forceRefresh)
                lastLoadedCatalog = data
                progressBarHome.visibility = View.GONE

                // Set Hero Billboard
                val hero = data.latinoTrending.firstOrNull() ?: data.recentEpisodes.firstOrNull()
                if (hero != null) {
                    bindHero(hero)
                }

                refreshRowsWithFavorites()
            } catch (e: Exception) {
                progressBarHome.visibility = View.GONE
                e.printStackTrace()
                Toast.makeText(this@MainActivity, "Error al cargar catálogo: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun bindHero(anime: AnimeCard) {
        featuredAnime = anime
        txtHeroTitle.text = anime.title
        txtHeroSynopsis.text = anime.synopsis.ifEmpty { "Serie anime disponible en alta definición y audio latino." }

        val imageToLoad = anime.backdropUrl.ifEmpty { anime.posterUrl }
        if (imageToLoad.isNotEmpty()) {
            Glide.with(this)
                .load(imageToLoad)
                .centerCrop()
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .into(imgHeroBackdrop)
        }

        btnHeroPlay.setOnClickListener {
            DetailActivity.start(this, anime)
        }

        updateHeroFavoriteButton(anime)
        btnHeroFavorite.setOnClickListener {
            toggleCardFavorite(anime)
            updateHeroFavoriteButton(anime)
        }

        // Give initial TV remote focus to the Hero Play button
        btnHeroPlay.requestFocus()
    }

    private fun updateHeroFavoriteButton(anime: AnimeCard) {
        val isFav = FavoritesStore.isFavorite(this, anime.detailUrl)
        if (isFav) {
            btnHeroFavorite.text = "✓  En Mi Lista"
            btnHeroFavorite.setBackgroundResource(R.drawable.bg_topbar_active_source)
        } else {
            btnHeroFavorite.text = "⭐  Mi Lista"
            btnHeroFavorite.setBackgroundResource(R.drawable.bg_topbar_item)
        }
    }

    private fun refreshRowsWithFavorites() {
        val data = lastLoadedCatalog ?: return
        val allRows = mutableListOf<CatalogRow>()

        // 1. Favorites / Continue Watching Row
        val storedFavorites = FavoritesStore.getFavorites(this)
        if (storedFavorites.isNotEmpty()) {
            val favCards = storedFavorites.map { fav ->
                AnimeCard(
                    id = fav.url,
                    title = fav.title,
                    posterUrl = fav.poster,
                    detailUrl = fav.url,
                    source = if (fav.source.isNotEmpty()) fav.source else "Favoritos",
                    episodeBadge = "Guardado"
                )
            }
            allRows.add(CatalogRow(title = "⭐ Mi Lista / Continuar Viendo", cards = favCards))
        }

        // 2. SoloAnime Trending (Audio Latino)
        if (data.latinoTrending.isNotEmpty()) {
            allRows.add(CatalogRow(title = "🔥 SoloAnime (Audio Latino)", cards = data.latinoTrending))
        }

        // 3. SoloStream (Películas y Series Populares)
        if (data.soloStreamTrending.isNotEmpty()) {
            allRows.add(CatalogRow(title = "🎬 SoloStream (Películas y Series Populares)", cards = data.soloStreamTrending))
        }

        // 4. 9Anime (Catálogo Global HD)
        if (data.nineAnimeTrending.isNotEmpty()) {
            allRows.add(CatalogRow(title = "● 9Anime (Catálogo Global en HD)", cards = data.nineAnimeTrending))
        }

        // 5. JKAnime Recent Episodes (Estrenos)
        if (data.recentEpisodes.isNotEmpty()) {
            allRows.add(CatalogRow(title = "⚡ Últimos Capítulos Estrenados (JKAnime)", cards = data.recentEpisodes))
        }

        // 6. AnimeYT (Anime en Español y Temporadas)
        if (data.animeYtTrending.isNotEmpty()) {
            allRows.add(CatalogRow(title = "🎌 Anime en Español (AnimeYT)", cards = data.animeYtTrending))
        }

        // 7. GogoAnime (Catálogo Global Subtitulado)
        if (data.gogoTrending.isNotEmpty()) {
            allRows.add(CatalogRow(title = "🌐 Catálogo Global Subtitulado (GogoAnime)", cards = data.gogoTrending))
        }

        // 8. Populares / Recomendados
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
}
