package tv.einthusan.app

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** Reads Einthusan's public browse-page metadata and artwork. */
internal class EinthusanCatalogRepository(private val baseUrl: String = "https://einthusan.tv") {
    fun featured(language: String): List<CatalogMovie> {
        val document = Jsoup.connect("$baseUrl/movie/browse/?lang=$language")
            .userAgent(USER_AGENT)
            .referrer(baseUrl)
            .timeout(25000)
            .get()

        return document.select("a[href*=/movie/watch/]").mapNotNull { link ->
            val href = link.absUrl("href").ifBlank { link.attr("href") }
            val id = Regex("/movie/watch/([^/?#]+)").find(href)?.groupValues?.getOrNull(1)
                ?: return@mapNotNull null
            val card = findMovieCard(link)
            val title = sequenceOf(
                link.text().trim(), link.attr("title").trim(), link.attr("aria-label").trim(),
                card.selectFirst("h1, h2, h3, h4")?.text()?.trim().orEmpty()
            ).firstOrNull { it.isNotBlank() } ?: return@mapNotNull null

            val text = card.text()
            val year = Regex("\\b(?:19|20)\\d{2}\\b").find(text)?.value.orEmpty()
            val genre = listOf("Action", "Drama", "Comedy", "Romance", "Thriller", "Family", "Adventure")
                .firstOrNull { card.text().contains(it, ignoreCase = true) }.orEmpty()
            val description = card.selectFirst(
                ".synopsis, [class*=synopsis], [class*=description], [itemprop=description], meta[name=description]"
            )?.let { it.attr("content").ifBlank { it.text() }.trim() }.orEmpty()

            CatalogMovie(
                title = title,
                language = language.replaceFirstChar { it.uppercase() },
                year = year,
                genre = genre,
                leadActor = findRoleName(card, "Lead"),
                director = findRoleName(card, "Director"),
                description = description,
                id = id,
                posterUrl = findPoster(card, link)
            )
        }.distinctBy { it.id }
    }

    private fun findMovieCard(link: Element): Element {
        link.parents().firstOrNull { it.hasClass("block1") }?.let { return it }
        var current: Element? = link
        var best: Element = link.parent() ?: link
        repeat(10) {
            val candidate = current ?: return@repeat
            if (candidate.selectFirst("img, picture, [style*=background], [data-bg], [data-background]") != null) {
                best = candidate
            }
            if (candidate.select("a[href*=/movie/watch/]").size > 1) return best
            current = candidate.parent()
        }
        return best
    }

    private fun findPoster(card: Element, link: Element): String {
        val elements = (sequenceOf(card, link) + card.select("*").asSequence()).distinct()
        val imageAttributes = listOf(
            "data-original", "data-src", "data-lazy-src", "data-image",
            "data-poster", "data-bg", "data-background", "poster", "src", "data-srcset", "srcset"
        )
        for (element in elements) {
            for (attribute in imageAttributes) {
                val raw = element.attr(attribute).trim()
                val candidate = if (attribute.endsWith("srcset")) {
                    raw.split(",").firstOrNull()?.trim()?.substringBefore(" ") ?: ""
                } else raw
                val absolute = element.absUrl(attribute).ifBlank { candidate }
                if (absolute.startsWith("https://") || absolute.startsWith("http://")) return absolute
            }
            val style = element.attr("style")
            val cssUrl = Regex("""url\(\s*['"]?(https?://[^)'"]+)""", RegexOption.IGNORE_CASE)
                .find(style)?.groupValues?.getOrNull(1)
            if (!cssUrl.isNullOrBlank()) return cssUrl
        }
        return ""
    }

    private fun findRoleName(card: Element, role: String): String {
        val marker = card.select("*").firstOrNull { it.childrenSize() == 0 && it.text().trim().equals(role, true) }
            ?: return ""
        val previous = marker.previousElementSibling()
        if (previous != null && previous.text().isNotBlank()) return previous.text().trim()
        return marker.parent()?.previousElementSibling()?.text()?.trim().orEmpty()
    }

    private companion object {
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; Android TV) AppleWebKit/537.36 Chrome/120.0 Safari/537.36"
    }
}
