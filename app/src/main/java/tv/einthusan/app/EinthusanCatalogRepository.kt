package tv.einthusan.app

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** Reads Einthusan's public catalogue and per-title metadata. */
internal class EinthusanCatalogRepository(private val baseUrl: String = "https://einthusan.tv") {
    fun featured(language: String): List<CatalogMovie> {
        val document = Jsoup.connect("$baseUrl/movie/browse/?lang=$language").userAgent(USER_AGENT).referrer(baseUrl).timeout(25000).get()
        return document.select("a[href*=/movie/watch/]").mapNotNull { link ->
            val href = link.absUrl("href").ifBlank { link.attr("href") }
            val id = Regex("/movie/watch/([^/?#]+)").find(href)?.groupValues?.getOrNull(1) ?: return@mapNotNull null
            val card = findMovieCard(link)
            val title = sequenceOf(link.text().trim(), link.attr("title").trim(), link.attr("aria-label").trim(),
                card.selectFirst("h1, h2, h3, h4")?.text()?.trim().orEmpty()).firstOrNull { it.isNotBlank() } ?: return@mapNotNull null
            val text = card.text()
            CatalogMovie(
                title = title, language = language.replaceFirstChar { it.uppercase() },
                year = Regex("\\b(?:19|20)\\d{2}\\b").find(text)?.value.orEmpty(),
                genre = listOf("Action", "Drama", "Comedy", "Romance", "Thriller", "Family", "Adventure").firstOrNull { text.contains(it, true) }.orEmpty(),
                leadActor = roleNames(card, "Lead").firstOrNull().orEmpty(),
                director = roleNames(card, "Director").firstOrNull().orEmpty(),
                description = descriptionFrom(card), id = id, posterUrl = findPoster(card, link)
            )
        }.distinctBy { it.id }
    }

    /** Fetch richer public metadata only when a viewer opens a title. */
    fun details(movie: CatalogMovie): CatalogMovie {
        val doc = Jsoup.connect("$baseUrl/movie/watch/" + movie.id + "/").userAgent(USER_AGENT).referrer(baseUrl).timeout(25000).get()
        val image = meta(doc, "og:image", "twitter:image")
        val description = meta(doc, "og:description", "description")
            .ifBlank { doc.selectFirst("[itemprop=description], .synopsis, [class*=synopsis]")?.text()?.trim().orEmpty() }
        val title = meta(doc, "og:title").ifBlank { movie.title }.substringBefore(" - Einthusan").trim()
        val year = Regex("\\b(?:19|20)\\d{2}\\b").find(doc.text())?.value ?: movie.year
        return movie.copy(
            title = title, year = year,
            genre = meta(doc, "genre").ifBlank { movie.genre },
            leadActor = labelledValue(doc, "Lead").ifBlank { movie.leadActor },
            director = labelledValue(doc, "Director").ifBlank { movie.director },
            description = description.ifBlank { movie.description },
            posterUrl = image.ifBlank { findPoster(doc.body(), doc.body()) }.ifBlank { movie.posterUrl }
        )
    }

    private fun meta(doc: org.jsoup.nodes.Document, vararg keys: String): String {
        for (key in keys) {
            val value = doc.selectFirst("meta[property=$key], meta[name=$key]")?.attr("content")?.trim()
            if (!value.isNullOrBlank()) return value
        }
        return ""
    }

    private fun labelledValue(doc: org.jsoup.nodes.Document, label: String): String {
        val element = doc.select("*").firstOrNull { it.childrenSize() == 0 && it.text().trim().equals(label, true) } ?: return ""
        return element.previousElementSibling()?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: element.parent()?.text()?.removePrefix(label)?.trim().orEmpty()
    }

    private fun descriptionFrom(card: Element): String =
        card.selectFirst("[itemprop=description], .synopsis, [class*=synopsis], [class*=description]")
            ?.let { it.attr("content").ifBlank { it.text() }.trim() }.orEmpty()

    private fun roleNames(card: Element, role: String): List<String> =
        card.select("*").filter { it.childrenSize() == 0 && it.text().trim().equals(role, true) }
            .mapNotNull { marker -> marker.previousElementSibling()?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: marker.parent()?.previousElementSibling()?.text()?.trim()?.takeIf { it.isNotBlank() } }.distinct()

    private fun findMovieCard(link: Element): Element {
        link.parents().firstOrNull { it.hasClass("block1") }?.let { return it }
        var current: Element? = link
        var best: Element = link.parent() ?: link
        repeat(10) {
            val candidate = current ?: return@repeat
            if (candidate.selectFirst("img, picture, [style*=background], [data-bg], [data-background]") != null) best = candidate
            if (candidate.select("a[href*=/movie/watch/]").size > 1) return best
            current = candidate.parent()
        }
        return best
    }

    private fun findPoster(card: Element, link: Element): String {
        val candidates = mutableListOf<Pair<Int, String>>()
        val elements = (sequenceOf(card, link) + card.select("*").asSequence()).distinct()
        for (element in elements) {
            val classes = (element.className() + " " + element.id()).lowercase()
            val cast = classes.contains("cast") || classes.contains("actor") || classes.contains("artist")
            for (attr in listOf("data-poster", "data-image", "data-original", "data-src", "data-lazy-src",
                "data-bg", "data-background", "poster", "src", "data-srcset", "srcset")) {
                val raw = element.attr(attr).trim()
                val candidate = if (attr.endsWith("srcset")) raw.split(",").firstOrNull()?.trim()?.substringBefore(" ") ?: "" else raw
                val absolute = element.absUrl(attr).ifBlank { candidate }
                if (absolute.startsWith("http")) {
                    val score = when {
                        cast -> 0
                        classes.contains("poster") || classes.contains("cover") || classes.contains("thumb") -> 110
                        attr.contains("poster") || attr.contains("background") || attr == "data-bg" -> 100
                        element.tagName() == "img" && element.parent() == card -> 90
                        element.tagName() == "img" -> 60
                        else -> 20
                    }
                    candidates += score to absolute
                }
            }
            val css = Regex("""url\(\s*['"]?(https?://[^)'"]+)""", RegexOption.IGNORE_CASE).find(element.attr("style"))?.groupValues?.getOrNull(1)
            if (!css.isNullOrBlank()) candidates += (if (classes.contains("poster") || classes.contains("cover")) 120 else 70) to css
        }
        return candidates.maxByOrNull { it.first }?.second.orEmpty()
    }

    private companion object {
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; Android TV) AppleWebKit/537.36 Chrome/120.0 Safari/537.36"
    }
}
