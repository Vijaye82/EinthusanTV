package tv.einthusan.app

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** Loads public Einthusan catalogue cards only; login and playback are separate stages. */
internal class EinthusanCatalogRepository(private val baseUrl: String = "https://einthusan.tv") {
    fun featured(language: String): List<CatalogMovie> {
        val document = Jsoup.connect("$baseUrl/movie/browse/?lang=$language")
            .userAgent("Mozilla/5.0 (Linux; Android 10; Android TV) AppleWebKit/537.36 Chrome/120.0 Safari/537.36")
            .referrer(baseUrl)
            .timeout(25000)
            .get()

        // The browse page has several unrelated image links. Start with the actual
        // movie watch links instead of assuming a particular CSS class is the card.
        return document.select("a[href*=/movie/watch/]").mapNotNull { watchLink ->
            val card = findMovieCard(watchLink)
            val title = sequenceOf(
                watchLink.text().trim(),
                watchLink.attr("title").trim(),
                watchLink.attr("aria-label").trim(),
                card.selectFirst("h1, h2, h3, h4")?.text()?.trim().orEmpty()
            ).firstOrNull { it.isNotBlank() } ?: return@mapNotNull null

            val href = watchLink.absUrl("href").ifBlank { watchLink.attr("href") }
            val movieId = Regex("/movie/watch/([^/?#]+)").find(href)?.groupValues?.getOrNull(1)
                ?: href.substringAfterLast("/").ifBlank { title }

            val image = card.selectFirst("img") ?: watchLink.selectFirst("img")
            val poster = image?.let { img ->
                listOf("src", "data-src", "data-original", "data-lazy-src", "data-srcset")
                    .asSequence()
                    .map { key -> img.absUrl(key).ifBlank { img.attr(key).substringBefore(",").substringBefore(" ") } }
                    .firstOrNull { it.startsWith("https://") || it.startsWith("http://") }
            }.orEmpty()

            val cardText = card.text()
            val year = Regex("\\b(?:19|20)\\d{2}\\b").find(cardText)?.value.orEmpty()
            val description = card.selectFirst(".synopsis, [class*=synopsis], [class*=description]")?.text()?.trim().orEmpty()

            CatalogMovie(
                title,
                language.replaceFirstChar { it.uppercase() },
                year,
                "",
                "",
                "",
                description,
                movieId,
                poster
            )
        }.distinctBy { it.id }
    }

    private fun findMovieCard(link: Element): Element {
        var current: Element? = link
        repeat(7) {
            val candidate = current ?: return@repeat
            if (candidate.selectFirst("img") != null) return candidate
            current = candidate.parent()
        }
        return link.parent() ?: link
    }
}
