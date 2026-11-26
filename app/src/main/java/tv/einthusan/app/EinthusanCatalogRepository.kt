package tv.einthusan.app

import org.jsoup.Jsoup

/** Loads public Einthusan catalogue cards only; login and playback are separate stages. */
internal class EinthusanCatalogRepository(private val baseUrl: String = "https://einthusan.tv") {
    fun featured(language: String): List<CatalogMovie> {
        val document = Jsoup.connect("$baseUrl/movie/browse/?lang=$language")
            .userAgent("Mozilla/5.0 (Linux; Android 10; Android TV) AppleWebKit/537.36 Chrome/120.0 Safari/537.36")
            .referrer(baseUrl)
            .timeout(25000)
            .get()
        val cards = document.select("div.block1")
        val sourceCards = if (cards.isNotEmpty()) cards else document.select("a[href*=/movie/watch/]")
        return sourceCards.mapNotNull { element ->
            val watchLink = if (element.tagName() == "a") element else element.selectFirst("a[href*=/movie/watch/]") ?: return@mapNotNull null
            val card = if (element.tagName() == "a") watchLink.parents().firstOrNull { it.selectFirst("img") != null } ?: watchLink else element
            val title = card.selectFirst("h2, h3")?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: watchLink.text().trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val href = watchLink.absUrl("href").ifBlank { watchLink.attr("href") }
            val movieId = Regex("/movie/watch/([^/?]+)").find(href)?.groupValues?.getOrNull(1) ?: href.substringAfterLast("/").ifBlank { title }
            val image = card.selectFirst("img")
            val poster = image?.let { img ->
                listOf("src", "data-src", "data-original", "data-lazy-src").map { key -> img.absUrl(key).ifBlank { img.attr(key) } }
                    .firstOrNull { it.startsWith("https://") || it.startsWith("http://") }
            }.orEmpty()
            val text = card.text()
            val year = Regex("\\b(?:19|20)\\d{2}\\b").find(text)?.value.orEmpty()
            val description = card.selectFirst("p.synopsis")?.text()?.trim().orEmpty()
            CatalogMovie(title, language.replaceFirstChar { it.uppercase() }, year, "", "", "", description, movieId, poster)
        }.distinctBy { it.id }
    }
}
