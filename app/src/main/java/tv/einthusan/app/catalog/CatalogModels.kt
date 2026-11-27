package tv.einthusan.app.catalog

/**
 * UI-facing catalogue model. Source-specific Kodi/Stremio payloads are mapped into this type
 * so the Compose UI never depends on either add-on's wire format.
 */
data class Movie(
    val id: String,
    val title: String,
    val einthusanId: String? = null,
    val language: String,
    val year: Int?,
    val genres: List<String> = emptyList(),
    val synopsis: String? = null,
    val posterUrl: String? = null,
    val backgroundUrl: String? = null,
    val cast: List<String> = emptyList(),
    val director: String? = null,
    val rating: Double? = null,
    val runtimeMinutes: Int? = null
)

data class CatalogSection(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val movies: List<Movie>
)

data class CatalogSnapshot(
    val sections: List<CatalogSection>,
    val languages: List<String>,
    val genres: List<String>,
    val years: List<Int>,
    val updatedAtEpochMs: Long? = null
)

data class CatalogQuery(
    val language: String? = null,
    val genre: String? = null,
    val year: Int? = null,
    val search: String? = null,
    val sort: Sort = Sort.RECENT
) {
    enum class Sort { RECENT, TITLE_ASC, YEAR_DESC, RATING_DESC }
}

sealed interface CatalogResult {
    data class Success(val snapshot: CatalogSnapshot) : CatalogResult
    data class Failure(val message: String, val cause: Throwable? = null) : CatalogResult
}

/**
 * Implementations adapt the authorised Einthusan catalogue source. Keep HTTP/auth details
 * outside the UI and map source responses to Movie/CatalogSnapshot here.
 */
interface CatalogRepository {
    suspend fun loadHome(): CatalogResult
    suspend fun search(query: CatalogQuery): CatalogResult
}
