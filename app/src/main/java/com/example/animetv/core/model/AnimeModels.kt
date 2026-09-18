package com.example.animetv.core.model

import java.io.Serializable

/**
 * Clean data model representing an Anime card in the Netflix/Stremio TV catalog rows.
 */
data class AnimeCard(
    val id: String,
    val title: String,
    val posterUrl: String,
    val backdropUrl: String = "",
    val detailUrl: String,
    val source: String,
    val episodeBadge: String = "",
    val rating: String = "",
    val synopsis: String = "",
    // 0-100 watch progress, used by the "Continuar Viendo" row's landscape cards to draw a
    // progress bar on the thumbnail. Unused (0) for any other row.
    val progressPercent: Int = 0,
    val subtitle: String = "",
    val logoUrl: String = ""
) : Serializable

/**
 * Detailed information for an Anime series or movie.
 */
data class AnimeDetail(
    val title: String,
    val posterUrl: String,
    val backdropUrl: String = "",
    val synopsis: String,
    val genres: List<String> = emptyList(),
    val source: String,
    val detailUrl: String,
    val trailerUrl: String = "",
    val episodes: List<AnimeEpisode> = emptyList(),
    // Exact TMDB id scraped from the page's own JSON-LD ("sameAs" schema.org link), when
    // present. Lets metadata be fetched by id instead of fuzzy title search, which is what
    // caused franchise entries with similar names (One Piece vs. its live-action adaptation,
    // Dragon Ball vs. Z vs. GT, etc.) to occasionally resolve to the wrong title's data.
    val tmdbId: Int = 0,
    val tmdbMediaType: String = ""
) : Serializable

/**
 * The three visually distinct Home-screen row treatments: a dense poster grid for browsing the
 * library, and landscape cards with a progress bar for resuming something already in progress.
 */
enum class CatalogRowType { LIBRARY, CONTINUE_WATCHING }

/**
 * Clean data model representing a row of anime cards in the catalog.
 */
data class CatalogRow(
    val title: String,
    val cards: List<AnimeCard>,
    val type: CatalogRowType = CatalogRowType.LIBRARY
) : Serializable

/**
 * Represents an episode in a series.
 */
data class AnimeEpisode(
    val episodeNumber: Int,
    val seasonNumber: Int = 1,
    val title: String,
    val episodeUrl: String,
    val releaseDate: String = "",
    val synopsis: String = "",
    val stillUrl: String = ""
) : Serializable

/**
 * Represents a resolved stream link ready for ExoPlayer playback.
 */
data class StreamResult(
    val videoUrl: String,
    val isHls: Boolean = true,
    val isEmbed: Boolean = false,
    val serverName: String = "Principal",
    val headers: Map<String, String> = emptyMap()
) : Serializable
