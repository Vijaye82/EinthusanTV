package tv.einthusan.app.catalog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

/**
 * Adapter for the Stremio-compatible Einthusan addon catalogue routes.
 * It consumes only the addon's published JSON catalog API; credentials and streams are not
 * requested by this catalogue client.
 */
class EinthusanCatalogRepository(
    private val baseUrl: String,
    private val languages: List<String> = listOf(
        "tamil", "telugu", "hindi", "malayalam", "kannada", "bengali", "punjabi", "marathi"
    )
) : CatalogRepository {
    override suspend fun loadHome(): CatalogResult = withContext(Dispatchers.IO) {
        try {
            val movies = languages.flatMap { language ->
                requestMetas("/$language/catalog/movie/${language}_board.json")
                    .mapNotNull { it.toMovie(language) }
            }.distinctBy { it.id }.take(500)
            if (movies.isEmpty()) CatalogResult.Failure(
                "The catalogue service returned no titles. Check its availability and language configuration."
            ) else CatalogResult.Success(movies.toSnapshot())
        } catch (error: Exception) {
            CatalogResult.Failure("Could not load the Einthusan catalogue: ${error.message ?: "network error"}", error)
        }
    }

    override suspend fun search(query: CatalogQuery): CatalogResult = withContext(Dispatchers.IO) {
        try {
            val selectedLanguages = query.language?.let { listOf(it.lowercase()) } ?: languages
            val searchTerm = query.search?.trim().orEmpty()
            val movies = selectedLanguages.flatMap { language ->
                val encoded = URLEncoder.encode(searchTerm, "UTF-8")
                val suffix = if (searchTerm.isNotBlank()) "?search=$encoded" else ""
                requestMetas("/$language/catalog/movie/$language.json$suffix")
                    .mapNotNull { it.toMovie(language) }
            }.distinctBy { it.id }.filter { movie ->
                (query.genre == null || movie.genres.any { it.equals(query.genre, true) }) &&
                    (query.year == null || movie.year == query.year)
            }.let { list ->
                when (query.sort) {
                    CatalogQuery.Sort.TITLE_ASC -> list.sortedBy { it.title.lowercase() }
                    CatalogQuery.Sort.YEAR_DESC -> list.sortedByDescending { it.year ?: 0 }
                    CatalogQuery.Sort.RATING_DESC -> list.sortedByDescending { it.rating ?: 0.0 }
                    CatalogQuery.Sort.RECENT -> list
                }
            }
            CatalogResult.Success(movies.toSnapshot())
        } catch (error: Exception) {
            CatalogResult.Failure("Catalogue search failed: ${error.message ?: "network error"}", error)
        }
    }

    private fun requestMetas(path: String): List<JSONObject> {
        val connection = (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "EinthusanTV-Android/1.0")
        }
        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw IllegalStateException("Catalogue HTTP $status")
            val metas = JSONObject(body).optJSONArray("metas") ?: return emptyList()
            return (0 until metas.length()).mapNotNull { metas.optJSONObject(it) }
        } finally {
            connection.disconnect()
        }
    }

    private fun JSONObject.toMovie(language: String): Movie? {
        val movieId = optString("id").takeIf { it.isNotBlank() } ?: return null
        val movieTitle = optString("name").takeIf { it.isNotBlank() } ?: return null
        val yearText = optString("releaseInfo").takeIf { it.isNotBlank() } ?: optString("year")
        val year = Regex("(19|20)\\d{2}").find(yearText)?.value?.toIntOrNull()
        val genresJson = optJSONArray("genres")
        val genres = if (genresJson == null) emptyList() else
            (0 until genresJson.length()).map { genresJson.optString(it) }.filter { it.isNotBlank() }
        val castJson = optJSONArray("cast")
        val cast = if (castJson == null) emptyList() else
            (0 until castJson.length()).map { castJson.optString(it) }.filter { it.isNotBlank() }
        val rating = optString("imdbRating").toDoubleOrNull()
        val runtime = Regex("(\\d+)").find(optString("runtime"))?.value?.toIntOrNull()
        return Movie(
            id = movieId,
            title = movieTitle,
            einthusanId = optString("EinthusanID").takeIf { it.isNotBlank() } ?: movieId.removePrefix("einthusan_").takeIf { movieId.startsWith("einthusan_") },
            language = language.replaceFirstChar { it.uppercase() },
            year = year,
            genres = genres,
            synopsis = optString("description").takeIf { it.isNotBlank() },
            posterUrl = optString("poster").takeIf { it.startsWith("https://") || it.startsWith("http://") },
            backgroundUrl = optString("background").takeIf { it.startsWith("https://") || it.startsWith("http://") },
            cast = cast,
            director = optString("director").takeIf { it.isNotBlank() },
            rating = rating,
            runtimeMinutes = runtime
        )
    }

    private fun List<Movie>.toSnapshot() = CatalogSnapshot(
        sections = listOf(CatalogSection("all", "All Movies", movies = this)),
        languages = map { it.language }.distinct().sorted(),
        genres = flatMap { it.genres }.distinct().sorted(),
        years = mapNotNull { it.year }.distinct().sortedDescending(),
        updatedAtEpochMs = System.currentTimeMillis()
    )
}
