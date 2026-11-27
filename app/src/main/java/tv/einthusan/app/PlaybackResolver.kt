package tv.einthusan.app

import android.net.Uri
import androidx.media3.common.MimeTypes

/**
 * A replaceable boundary between Einthusan's approved playback integration and ExoPlayer.
 * Do not add private endpoint scraping, credential forwarding, or access-control workarounds here.
 */
internal data class PlaybackSource(
    val url: String,
    val mimeType: String? = null,
    val requestHeaders: Map<String, String> = emptyMap(),
    val expiresAtEpochSeconds: Long? = null
)

internal data class PlaybackResolution(
    val source: PlaybackSource? = null,
    val message: String? = null
)

internal interface PlaybackResolver {
    suspend fun resolve(movie: CatalogMovie): PlaybackResolution
}

/**
 * Safe starter resolver: accepts only a direct media URL already exposed in ordinary public
 * watch-page markup. Replace this implementation with Einthusan's documented/approved resolver.
 */
internal class PublicMarkupPlaybackResolver(
    private val catalogue: EinthusanCatalogRepository = EinthusanCatalogRepository()
) : PlaybackResolver {
    override suspend fun resolve(movie: CatalogMovie): PlaybackResolution {
        val url = catalogue.playableUrl(movie)
        if (url.isBlank()) {
            return PlaybackResolution(
                message = "Einthusan has not exposed a direct video URL in the public movie page. Connect the approved playback resolver when Einthusan provides its integration details."
            )
        }
        val uri = Uri.parse(url)
        if (uri.scheme !in listOf("https", "http")) {
            return PlaybackResolution(message = "The returned video address is not a supported HTTP(S) stream.")
        }
        val path = uri.path.orEmpty().lowercase()
        val mime = when {
            path.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
            path.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
            path.endsWith(".mp4") -> MimeTypes.VIDEO_MP4
            else -> null
        }
        return PlaybackResolution(source = PlaybackSource(url = url, mimeType = mime))
    }
}
