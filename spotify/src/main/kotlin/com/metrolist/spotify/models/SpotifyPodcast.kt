package com.metrolist.spotify.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Podcast show + episode models from Spotify's REST Web API
 * (`/search?type=show,episode`, `/shows/{id}/episodes`, `/me/episodes`, `/me/shows`).
 */
@Serializable
data class SpotifyShow(
    val id: String = "",
    val name: String = "",
    val publisher: String? = null,
    val description: String? = null,
    @SerialName("html_description") val htmlDescription: String? = null,
    val images: List<SpotifyImage> = emptyList(),
    @SerialName("total_episodes") val totalEpisodes: Int = 0,
    val uri: String? = null,
)

@Serializable
data class SpotifyEpisode(
    val id: String = "",
    val name: String = "",
    val description: String? = null,
    @SerialName("html_description") val htmlDescription: String? = null,
    val images: List<SpotifyImage> = emptyList(),
    @SerialName("duration_ms") val durationMs: Int = 0,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("audio_preview_url") val audioPreviewUrl: String? = null,
    val uri: String? = null,
    // Present on /shows/{id}/episodes items via a nested show; often null on search.
    val show: SpotifyShow? = null,
)

@Serializable
data class SpotifySavedEpisode(
    @SerialName("added_at") val addedAt: String? = null,
    val episode: SpotifyEpisode? = null,
)

@Serializable
data class SpotifySavedShow(
    @SerialName("added_at") val addedAt: String? = null,
    val show: SpotifyShow? = null,
)

@Serializable
data class SpotifyPodcastSearchResult(
    val shows: SpotifyPaging<SpotifyShow>? = null,
    val episodes: SpotifyPaging<SpotifyEpisode>? = null,
)

