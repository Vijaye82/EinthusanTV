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
        }.distinctBy { it.id }.mapIndexed { index, movie ->
            // Preserve catalogue artwork when present; consult the watch page only when missing.
            if (movie.posterUrl.isBlank() || index == 0 && movie.backdropUrl.isBlank()) {
                runCatching { artworkFallback(movie) }.getOrDefault(movie)
            } else movie
        }
    }

    private fun artworkFallback(movie: CatalogMovie): CatalogMovie {
        val doc = Jsoup.connect(baseUrl + "/movie/watch/" + movie.id + "/")
            .userAgent(USER_AGENT).referrer(baseUrl + "/").timeout(10000).get()
        val socialImage = meta(doc, "og:image", "twitter:image", "twitter:image:src")
        val image = socialImage.ifBlank {
            doc.selectFirst("link[rel=image_src]")?.let { it.absUrl("href").ifBlank { it.attr("href") } }.orEmpty()
        }.ifBlank { findPoster(doc.body(), doc.body()) }
        return movie.copy(
            posterUrl = movie.posterUrl.ifBlank { image },
            backdropUrl = socialImage.ifBlank { image }.ifBlank { movie.backdropUrl }
        )
    }

    /** Fetch richer public metadata only when a viewer opens a title. */
    fun details(movie: CatalogMovie): CatalogMovie {
        val doc = Jsoup.connect("$baseUrl/movie/watch/" + movie.id + "/").userAgent(USER_AGENT).referrer(baseUrl).timeout(25000).get()
        val image = meta(doc, "og:image", "twitter:image", "twitter:image:src")
            .ifBlank { doc.selectFirst("link[rel=image_src]")?.let { it.absUrl("href").ifBlank { it.attr("href") } }.orEmpty() }
            .ifBlank { findPoster(doc.body(), doc.body()) }
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
            posterUrl = image.ifBlank { movie.posterUrl },
            backdropUrl = meta(doc, "og:image", "twitter:image").ifBlank { movie.backdropUrl }.ifBlank { image }
        )
    }

    /** Only accepts a direct media URL exposed in the ordinary watch-page markup. */
    fun playableUrl(movie: CatalogMovie): String {
        val doc = Jsoup.connect("$baseUrl/movie/watch/" + movie.id + "/")
            .userAgent(USER_AGENT).referrer(baseUrl).timeout(25000).get()
        val direct = doc.selectFirst("video source[src], video[src]")?.let {
            it.absUrl("src").ifBlank { it.attr("src") }
        }.orEmpty()
        if (direct.startsWith("https://") || direct.startsWith("http://")) return direct
        return meta(doc, "og:video", "og:video:url")
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

        fun add(rawValue: String, baseScore: Int) {
            val raw = rawValue.trim().substringBefore(" ")
            if (raw.isBlank() || raw.startsWith("data:")) return
            val url = when {
                raw.startsWith("//") -> "https:$raw"
                raw.startsWith("/") -> baseUrl + raw
                raw.startsWith("http://") || raw.startsWith("https://") -> raw
                else -> ""
            }
            if (url.isBlank()) return
            val lower = url.lowercase()
            // Einthusan's cast headshots use /prof/; exclude those while allowing
            // poster artwork that sits beside, rather than inside, the Watch link.
            if (lower.contains("/prof/") || lower.contains("/profile/") ||
                lower.contains("avatar") || lower.contains("logo")) return
            val imageLike = lower.matches(Regex(".*\\.(jpg|jpeg|png|webp|avif|gif)(\\?.*)?$")) ||
                lower.contains("image") || lower.contains("poster") || lower.contains("thumb") ||
                lower.contains("cover") || lower.contains("uploads")
            if (!imageLike) return
            candidates += baseScore to url
        }

        fun collect(element: Element, score: Int) {
            for (attr in listOf("data-poster", "data-image", "data-original", "data-src",
                "data-lazy-src", "data-srcset", "data-bg", "data-background", "poster", "src")) {
                val raw = element.attr(attr).trim()
                if (raw.isNotBlank()) {
                    val absolute = element.absUrl(attr).ifBlank { raw }
                    add(absolute, score)
                }
            }
            for (attr in listOf("srcset", "data-srcset")) {
                val set = element.attr(attr)
                if (set.isNotBlank()) {
                    val best = set.split(",").map { it.trim().substringBefore(" ") }.lastOrNull().orEmpty()
                    add(element.absUrl(attr).ifBlank { best }, score + 5)
                }
            }
            val css = Regex("""url\(\s*['"]?([^)'"]+)""", RegexOption.IGNORE_CASE)
                .find(element.attr("style"))?.groupValues?.getOrNull(1)
            if (!css.isNullOrBlank()) add(css, score)
            val srcset = element.attr("srcset").ifBlank { element.attr("data-srcset") }
            if (srcset.isNotBlank()) {
                srcset.split(",").map { it.trim().substringBefore(" ").trim() }
                    .forEach { add(it, score + 8) }
            }
        }

        // The watch link and explicitly labelled artwork are the strongest candidates.
        collect(link, 400)
        link.select("img, picture, source, [style*=background], [data-bg], [data-background]")
            .forEach { collect(it, 390) }
        card.select("[class*=poster], [id*=poster], [class*=cover], [id*=cover]")
            .forEach { container ->
                collect(container, 340)
                container.select("img, picture, source, [style*=background], [data-bg], [data-background]")
                    .forEach { collect(it, 330) }
            }

        // Fallback: inspect the rest of this movie card, excluding Einthusan /prof/ portraits.
        card.select("img, picture, source, [style*=background], [data-bg], [data-background]")
            .forEach { collect(it, 200) }

        return candidates.maxByOrNull { it.first }?.second.orEmpty()
    }

    private companion object {
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; Android TV) AppleWebKit/537.36 Chrome/120.0 Safari/537.36"
    }
}
