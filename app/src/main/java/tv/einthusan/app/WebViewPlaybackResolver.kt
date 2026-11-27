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
                val capturedRequestHeaders = AtomicReference<Map<String, String>>(emptyMap())
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

                fun toCdnUrl(sourceUrl: String): String {
                    return runCatching {
                        val uri = android.net.Uri.parse(sourceUrl)
                        val path = uri.path.orEmpty()
                        if (!path.contains("/etv/content/") || uri.host.orEmpty().contains("einthusan.io")) {
                            sourceUrl
                        } else {
                            val cdnPath = if (path.startsWith("/d/")) path else "/d" + path
                            val query = uri.encodedQuery.orEmpty()
                            val separator = if (query.isBlank()) "?" else "&"
                            "https://cdn2.einthusan.io" + cdnPath +
                                (if (query.isBlank()) "" else "?" + query) +
                                (if (query.contains("p=priority")) "" else separator + "p=priority")
                        }
                    }.getOrDefault(sourceUrl)
                }

                fun completeWithUrl(discoveredUrl: String) {
                    if (!discoveredUrl.startsWith("https://") && !discoveredUrl.startsWith("http://")) return
                    val url = toCdnUrl(discoveredUrl)
                    val cookie = (CookieManager.getInstance().getCookie(url)
                        ?: CookieManager.getInstance().getCookie(discoveredUrl)).orEmpty()
                    val headers = buildMap {
                        putAll(capturedRequestHeaders.get())
                        put("Referer", "https://einthusan.tv/movie/watch/" + movie.id + "/")
                        put("Origin", "https://einthusan.tv")
                        put("User-Agent", capturedRequestHeaders.get()["User-Agent"] ?: USER_AGENT)
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
                            var player = document.querySelector('#UIVideoPlayer');
                            var raw = player && player.getAttribute('data-mp4-link');
                            var video = document.querySelector('video');
                            var source = video && (video.currentSrc || video.src);
                            if (!source && video) {
                                var child = video.querySelector('source[src]');
                                if (child) source = child.src;
                            }
                            var button = document.querySelector('#download-content, a[download]');
                            var download = button && (button.href || button.getAttribute('href'));
                            var value = raw || source || download || '';
                            return value ? new URL(value, document.baseURI).href : '';
                        })();"""
                    ) { raw ->
                        if (finished) return@evaluateJavascript
                        val domUrl = raw?.trim()?.removeSurrounding("\"")
                            ?.replace("\\u0026", "&")
                        val discovered = domUrl?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
                            ?: capturedMediaUrl.get()
                        val url = discovered
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
                webView.settings.mediaPlaybackRequiresUserGesture = false
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
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
                            capturedRequestHeaders.compareAndSet(emptyMap(), request.requestHeaders)
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

    private companion object {
        const val MAX_ATTEMPTS = 50
        const val POLL_INTERVAL_MS = 600L
        const val PAGE_TIMEOUT_MS = 45000L
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; Android TV) AppleWebKit/537.36 Chrome/120.0 Safari/537.36"
    }
}
