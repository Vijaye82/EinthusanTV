package tv.einthusan.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.media3.common.MimeTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/**
 * Opens Einthusan's normal movie page, lets its own scripts run, and observes
 * media URLs exposed by that page. The WebView is only a resolver; ExoPlayer
 * remains the visible playback surface.
 */
internal class WebViewPlaybackResolver(private val context: Context) : PlaybackResolver {
    override suspend fun resolve(movie: CatalogMovie): PlaybackResolution =
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                val webView = WebView(context)
                val handler = Handler(Looper.getMainLooper())
                val capturedMediaUrl = AtomicReference<String?>(null)
                var attempts = 0
                var finished = false

                fun mimeFor(url: String): String? {
                    val path = url.substringBefore('?').lowercase()
                    return when {
                        path.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
                        path.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
                        path.endsWith(".mp4") || path.endsWith(".m4v") -> MimeTypes.VIDEO_MP4
                        path.endsWith(".webm") -> MimeTypes.VIDEO_WEBM
                        else -> null
                    }
                }

                fun finish(result: PlaybackResolution) {
                    if (finished) return
                    finished = true
                    handler.removeCallbacksAndMessages(null)
                    webView.stopLoading()
                    webView.destroy()
                    if (continuation.isActive) continuation.resume(result)
                }

                fun completeWithUrl(url: String) {
                    if (!url.startsWith("https://") && !url.startsWith("http://")) return
                    val cookie = CookieManager.getInstance().getCookie(url).orEmpty()
                    val headers = buildMap {
                        put("Referer", "https://einthusan.tv/movie/watch/${movie.id}/")
                        put("User-Agent", USER_AGENT)
                        if (cookie.isNotBlank()) put("Cookie", cookie)
                    }
                    finish(PlaybackResolution(source = PlaybackSource(
                        url = url,
                        mimeType = mimeFor(url),
                        requestHeaders = headers
                    )))
                }

                fun inspectPage() {
                    if (finished) return
                    attempts++
                    webView.evaluateJavascript(
                        """(function() {
                            var video = document.querySelector('video');
                            var source = video && (video.currentSrc || video.src);
                            if (!source && video) {
                                var child = video.querySelector('source[src]');
                                if (child) source = child.src;
                            }
                            var player = document.querySelector('#UIVideoPlayer');
                            var raw = player && player.getAttribute('data-mp4-link');
                            var button = document.querySelector('#download-content, a[download]');
                            return source || (button && button.href) || raw || '';
                        })();"""
                    ) { raw ->
                        if (finished) return@evaluateJavascript
                        val domUrl = raw?.trim()?.removeSurrounding("\"")
                            ?.replace("\\u0026", "&")
                        val discovered = domUrl?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
                            ?: capturedMediaUrl.get()
                        val url = discovered?.let(::toCdnUrl)
                        if (!url.isNullOrBlank()) {
                            completeWithUrl(url)
                        } else if (attempts >= MAX_ATTEMPTS) {
                            finish(PlaybackResolution(message =
                                "Einthusan's movie page loaded, but no direct media URL was exposed. Check that you are signed in if this title requires a premium account."
                            ))
                        } else {
                            handler.postDelayed({ inspectPage() }, POLL_INTERVAL_MS)
                        }
                    }
                }

                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                webView.settings.loadsImagesAutomatically = false
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        inspectPage()
                    }

                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest
                    ): android.webkit.WebResourceResponse? {
                        val url = request.url.toString()
                        val response = super.shouldInterceptRequest(view, request)
                        val responseMime = response?.mimeType.orEmpty().lowercase()
                        val looksLikeMedia = mimeFor(url) != null ||
                            responseMime.startsWith("video/") ||
                            responseMime.contains("mpegurl") ||
                            responseMime.contains("dash+xml")
                        if (looksLikeMedia && capturedMediaUrl.get() == null) {
                            capturedMediaUrl.compareAndSet(null, url)
                            handler.post { if (!finished) inspectPage() }
                        }
                        return response
                    }
                }

                continuation.invokeOnCancellation {
                    handler.post {
                        handler.removeCallbacksAndMessages(null)
                        webView.stopLoading()
                        webView.destroy()
                    }
                }
                webView.loadUrl("https://einthusan.tv/movie/watch/${movie.id}/")
                handler.postDelayed({
                    finish(PlaybackResolution(message =
                        "The Einthusan movie page took too long to expose its video URL. Please try again."
                    ))
                }, PAGE_TIMEOUT_MS)
            }
        }

    private fun toCdnUrl(raw: String): String {
        return runCatching {
            val uri = android.net.Uri.parse(raw)
            val host = uri.host.orEmpty()
            if (host.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}")) &&
                uri.path.orEmpty().contains("/etv/content/")) {
                val path = uri.path.orEmpty().let { if (it.startsWith("/d/")) it else "/d$it" }
                val query = uri.encodedQuery.orEmpty().let { if (it.contains("p=priority")) it else listOf(it, "p=priority").filter(String::isNotBlank).joinToString("&") }
                android.net.Uri.parse("https://cdn2.einthusan.io$path").buildUpon()
                    .encodedQuery(query).build().toString()
            } else raw
        }.getOrDefault(raw)
    }

    private companion object {
        const val MAX_ATTEMPTS = 50
        const val POLL_INTERVAL_MS = 600L
        const val PAGE_TIMEOUT_MS = 45000L
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; Android TV) AppleWebKit/537.36 Chrome/120.0 Safari/537.36"
    }
}
