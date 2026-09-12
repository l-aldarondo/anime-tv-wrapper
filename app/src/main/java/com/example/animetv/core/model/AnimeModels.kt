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
    val synopsis: String = ""
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
    val episodes: List<AnimeEpisode> = emptyList()
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
    val synopsis: String = ""
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
