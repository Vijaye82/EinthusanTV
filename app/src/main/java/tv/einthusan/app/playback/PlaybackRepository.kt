package tv.einthusan.app.playback

/**
 * A resolved, authorised playback choice. The URI is short-lived where required and must not
 * be persisted as a durable content link.
 */
data class PlaybackSource(
    val url: String,
    val label: String,
    val mimeType: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val subtitles: List<SubtitleTrack> = emptyList()
)

data class SubtitleTrack(val url: String, val language: String, val mimeType: String? = null)

data class PlaybackRequest(
    val movieId: String,
    val language: String = "tamil",
    val preferredQuality: Quality = Quality.AUTO
) {
    enum class Quality { AUTO, HD, UHD }
}

sealed interface PlaybackResult {
    data class Ready(val sources: List<PlaybackSource>) : PlaybackResult
    data class Unavailable(val message: String) : PlaybackResult
    data class Failure(val message: String, val cause: Throwable? = null) : PlaybackResult
}

/**
 * Resolves streams only through the authorised Einthusan integration and the signed-in
 * account's entitlement. AUTO should choose the best available entitled quality.
 */
interface PlaybackRepository {
    suspend fun resolve(request: PlaybackRequest): PlaybackResult
}
