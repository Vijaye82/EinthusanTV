package tv.einthusan.app.playback

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Resolves Stremio addon stream responses for an authorised movie ID.
 * The upstream addon remains responsible for its own access checks and stream resolution.
 */
class EinthusanPlaybackRepository(
    private val addonBaseUrl: String,
    private val languages: List<String> = listOf("tamil", "telugu", "hindi", "malayalam", "kannada", "bengali", "punjabi", "marathi")
) : PlaybackRepository {
    override suspend fun resolve(request: PlaybackRequest): PlaybackResult = withContext(Dispatchers.IO) {
        try {
            val movieId = request.movieId.trim()
            if (movieId.isBlank()) return@withContext PlaybackResult.Unavailable("This title has no playable Einthusan ID.")
            val sources = languages.flatMap { language ->
                val path = "/$language/stream/movie/${URLEncoder.encode(movieId, "UTF-8")}.json"
                val connection = (URL(addonBaseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 12_000
                    readTimeout = 30_000
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("User-Agent", "EinthusanTV-Android/1.0")
                }
                try {
                    val status = connection.responseCode
                    val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                    val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    if (status !in 200..299) throw IllegalStateException("Stream service HTTP $status")
                    val array = JSONObject(body).optJSONArray("streams")
                    if (array == null) emptyList() else (0 until array.length()).mapNotNull { index ->
                        val item = array.optJSONObject(index) ?: return@mapNotNull null
                        val url = item.optString("url").takeIf { it.startsWith("https://") || it.startsWith("http://") }
                            ?: return@mapNotNull null
                        PlaybackSource(
                            url = url,
                            label = item.optString("title").takeIf { it.isNotBlank() }
                                ?: item.optString("name").takeIf { it.isNotBlank() }
                                ?: language.replaceFirstChar { it.uppercase() }
                        )
                    }
                } finally {
                    connection.disconnect()
                }
            }.distinctBy { it.url }
            if (sources.isEmpty()) PlaybackResult.Unavailable(
                "No stream was returned for this title. It may not be available in your selected languages or may require an eligible Einthusan account."
            ) else PlaybackResult.Ready(sources)
        } catch (error: Exception) {
            PlaybackResult.Failure(error.message ?: "Unable to resolve a stream.", error)
        }
    }
}
