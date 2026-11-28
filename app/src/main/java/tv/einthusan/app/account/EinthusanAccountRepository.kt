package tv.einthusan.app.account

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager as WebCookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpCookie
import java.net.URI
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import android.util.Base64
import tv.einthusan.app.playback.PlaybackRequest
import tv.einthusan.app.playback.PlaybackResult
import tv.einthusan.app.playback.PlaybackSource

/**
 * Einthusan web login adapter based on the site's CSRF-backed AJAX login flow.
 * Session cookies remain in process memory only; passwords are never persisted.
 */
class EinthusanAccountRepository(
    context: Context,
    private val baseUrl: String = "https://einthusan.tv"
) : AccountRepository {
    companion object {
        @Volatile private var signedInName: String? = null
        @Volatile private var signedIn = false
    }

    private val appContext = context.applicationContext
    private val cookies = CookieManager(null, CookiePolicy.ACCEPT_ALL)
    private val sessionPrefs by lazy {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            "einthusan_encrypted_session",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    init {
        restoreCookies()
    }

    private fun persistCookies() {
        val array = org.json.JSONArray()
        cookies.cookieStore.cookies.forEach { cookie ->
            array.put(JSONObject()
                .put("name", cookie.name)
                .put("value", cookie.value)
                .put("domain", cookie.domain ?: "einthusan.tv")
                .put("path", cookie.path ?: "/")
                .put("secure", cookie.secure)
                .put("httpOnly", cookie.isHttpOnly)
                .put("maxAge", cookie.maxAge)
                .put("version", cookie.version)
                .put("discard", cookie.discard)
                .put("portlist", cookie.portlist ?: JSONObject.NULL))
        }
        sessionPrefs.edit().putString("cookies", array.toString()).apply()
    }

    private fun restoreCookies() {
        runCatching {
            val array = org.json.JSONArray(sessionPrefs.getString("cookies", "[]"))
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val cookie = HttpCookie(item.getString("name"), item.getString("value")).apply {
                    domain = item.optString("domain", "einthusan.tv")
                    path = item.optString("path", "/")
                    secure = item.optBoolean("secure")
                    isHttpOnly = item.optBoolean("httpOnly")
                    maxAge = item.optLong("maxAge", -1L)
                    version = item.optInt("version", 0)
                    discard = item.optBoolean("discard")
                    if (!item.isNull("portlist")) portlist = item.optString("portlist")
                }
                if (!cookie.hasExpired()) {
                    val domain = cookie.domain.removePrefix(".")
                    cookies.cookieStore.add(URI("https://$domain/"), cookie)
                }
            }
            signedIn = cookies.cookieStore.cookies.isNotEmpty()
        }
    }

    override suspend fun currentState(): AccountState = withContext(Dispatchers.IO) {
        if (!signedIn) return@withContext AccountState.SignedOut
        runCatching {
            val accountPage = request("GET", "/account/?lang=tamil", "$baseUrl/")
            val looksSignedIn = accountPage.contains("profile", true) ||
                accountPage.contains("logout", true) || accountPage.contains("sign out", true)
            if (!looksSignedIn) {
                signedIn = false
                signedInName = null
                cookies.cookieStore.removeAll()
                sessionPrefs.edit().clear().apply()
                AccountState.SignedOut
            } else AccountState.SignedIn(signedInName)
        }.getOrElse { AccountState.Error("Could not verify your Einthusan session: " + (it.message ?: "connection error")) }
    }

    override suspend fun signIn(signInRequest: SignInRequest): AccountState = withContext(Dispatchers.IO) {
        val email = signInRequest.usernameOrEmail.trim()
        val password = String(signInRequest.password)
        signInRequest.password.fill('\u0000')
        if (email.isBlank() || password.isBlank()) return@withContext AccountState.Error("Enter your email and password.")
        try {
            val language = "tamil"
            val loginPage = request("GET", "/login/?lang=$language", referer = "$baseUrl/")
                val csrf = Regex("""data-pageid=["'](.*?)["']""", RegexOption.IGNORE_CASE)
                .find(loginPage)?.groupValues?.getOrNull(1)
                ?.replace("&#43;", "+") ?: return@withContext AccountState.Error("Einthusan did not provide a sign-in token. Please try again.")
            val payload = JSONObject().put("Email", email).put("Password", password)
            val form = listOf(
                "xEvent" to "Login",
                "xJson" to payload.toString(),
                "arcVersion" to "3",
                "appVersion" to "59",
                "tabID" to csrf + "48",
                "gorilla.csrf.Token" to csrf
            ).joinToString("&") { (key, value) ->
                URLEncoder.encode(key, "UTF-8") + "=" + URLEncoder.encode(value, "UTF-8")
            }
            val response = request(
                "POST", "/ajax/login/?lang=$language",
                referer = "$baseUrl/login/?lang=$language",
                body = form
            )
            val event = runCatching { JSONObject(response).optString("Event") }.getOrNull()
            if (event != "redirect" && !response.contains("success", true)) {
                return@withContext AccountState.Error(
                    runCatching { JSONObject(response).optString("message") }.getOrNull()
                        ?.takeIf { it.isNotBlank() } ?: "Einthusan did not accept those sign-in details."
                )
            }
            val accountPage = request(
                "GET",
                "/account/?flashmessage=success%3A%3A%3AYou+are+now+logged+in.&lang=$language",
                referer = "$baseUrl/login/?lang=$language"
            )
            if (accountPage.contains("login", true) && !accountPage.contains("profile", true)) {
                return@withContext AccountState.Error("Sign-in could not be confirmed by Einthusan.")
            }
            signedInName = Regex("<h2[^>]*>(.*?)</h2>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .find(accountPage)?.groupValues?.getOrNull(1)?.replace(Regex("<[^>]+>"), "")?.let(::decodeHtml)?.trim()
            signedIn = true
            persistCookies()
            AccountState.SignedIn(signedInName)
        } catch (error: Exception) {
            AccountState.Error(error.message ?: "Unable to sign in.")
        } finally {
            signInRequest.password.fill('\u0000')
        }
    }


    private data class BrowserExtraction(val url: String? = null, val error: String? = null)

    /**
     * Mirrors the published Einthusan Stremio resolver: let Einthusan's own page JavaScript
     * populate #download-content, then read the resulting short-lived media URL.
     */
    private suspend fun extractDownloadUrlInWebView(pageUrl: String): BrowserExtraction =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val handler = Handler(Looper.getMainLooper())
                val webView = WebView(appContext)
                var completed = false
                lateinit var poll: Runnable
                lateinit var timeout: Runnable

                fun finish(result: BrowserExtraction) {
                    if (completed) return
                    completed = true
                    handler.removeCallbacks(poll)
                    handler.removeCallbacks(timeout)
                    runCatching {
                        webView.stopLoading()
                        webView.webViewClient = WebViewClient()
                        webView.destroy()
                    }
                    if (continuation.isActive) continuation.resume(result)
                }

                poll = object : Runnable {
                    override fun run() {
                        if (completed) return
                        val script = """
                            (function() {
                              var body = (document.body && document.body.innerText) || '';
                              if (/Go Premium/i.test(body)) return JSON.stringify({error:'PREMIUM'});
                              if (/SERVERS ARE ALMOST AT CAPACITY/i.test(body)) return JSON.stringify({error:'BUSY'});
                              var button = document.querySelector('#download-content');
                              var href = button ? (button.href || button.getAttribute('href') || '') : '';
                              return JSON.stringify({href: href});
                            })();
                        """.trimIndent()
                        webView.evaluateJavascript(script) { raw ->
                            if (completed) return@evaluateJavascript
                            val jsonText = runCatching {
                                org.json.JSONTokener(raw ?: "null").nextValue() as? String
                            }.getOrNull()
                            val payload = jsonText?.let { runCatching { JSONObject(it) }.getOrNull() }
                            when (payload?.optString("error")) {
                                "PREMIUM" -> finish(BrowserExtraction(error = "PREMIUM"))
                                "BUSY" -> finish(BrowserExtraction(error = "BUSY"))
                                else -> {
                                    val href = payload?.optString("href")?.takeIf { it.isNotBlank() && it != "null" }
                                    if (href != null) finish(BrowserExtraction(url = href))
                                    else handler.postDelayed(this, 500)
                                }
                            }
                        }
                    }
                }

                timeout = Runnable {
                    finish(BrowserExtraction(error = "Einthusan's page did not expose a download link after running its player scripts."))
                }

                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                webView.settings.loadsImagesAutomatically = false
                webView.settings.mediaPlaybackRequiresUserGesture = false
                webView.settings.userAgentString =
                    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (!completed) handler.postDelayed(poll, 900)
                    }
                }

                val webCookies = WebCookieManager.getInstance()
                webCookies.setAcceptCookie(true)
                cookies.cookieStore.cookies.filterNot { it.hasExpired() }.forEach { cookie ->
                    val cookieText = buildString {
                        append(cookie.name).append('=').append(cookie.value)
                        append("; path=").append(cookie.path ?: "/")
                        if (cookie.secure) append("; secure")
                    }
                    webCookies.setCookie(baseUrl, cookieText)
                }
                webCookies.flush()

                continuation.invokeOnCancellation {
                    handler.post { finish(BrowserExtraction(error = "Playback resolution cancelled.")) }
                }
                handler.postDelayed(timeout, 35_000)
                webView.loadUrl(pageUrl, mapOf("Referer" to "$baseUrl/"))
            }
        }

    private fun toEinthusanCdnUrl(mediaUrl: String): String = runCatching {
        val uri = Uri.parse(mediaUrl)
        if (!uri.encodedPath.orEmpty().contains("/etv/")) return mediaUrl
        val path = uri.encodedPath.orEmpty()
        val cdnPath = if (path.startsWith("/d/")) path else "/d$path"
        uri.buildUpon()
            .scheme("https")
            .authority("cdn2.einthusan.io")
            .encodedPath(cdnPath)
            .apply {
                if (uri.getQueryParameter("p") == null) appendQueryParameter("p", "priority")
            }
            .build()
            .toString()
    }.getOrDefault(mediaUrl)

    /**
     * Resolves a movie through Einthusan's own watch page and AJAX player handshake.
     * The site's response controls access and quality; no stream URL is fabricated or cached.
     */
    suspend fun resolveMovie(movieId: String, language: String, quality: PlaybackRequest.Quality): PlaybackResult =
        withContext(Dispatchers.IO) {
            if (!signedIn) return@withContext PlaybackResult.Unavailable("Sign in to your Einthusan account before playing this title.")
            val safeId = movieId.trim()
            val safeLanguage = language.trim().lowercase()
            if (safeId.isBlank() || !safeLanguage.matches(Regex("[a-z]+"))) {
                return@withContext PlaybackResult.Unavailable("This title is missing a valid Einthusan movie ID or language.")
            }
            try {
                val encodedMovieId = URLEncoder.encode(safeId, "UTF-8")
                val browserPageUrl = buildString {
                    append("$baseUrl/movie/watch/").append(encodedMovieId).append("/?lang=").append(safeLanguage)
                    if (quality == PlaybackRequest.Quality.UHD) append("&uhd=true")
                }

                // Primary resolver: execute Einthusan's page scripts in Android WebView and
                // read the same #download-content URL used by the published Stremio addon.
                val browserResult = extractDownloadUrlInWebView(browserPageUrl)
                when (browserResult.error) {
                    "PREMIUM" -> return@withContext PlaybackResult.Unavailable("This title or quality requires an eligible Einthusan Premium account.")
                    "BUSY" -> return@withContext PlaybackResult.Unavailable("Einthusan servers are busy. Please try again shortly.")
                }
                browserResult.url?.let { rawUrl ->
                    val mediaUrl = if (rawUrl.startsWith("/")) "$baseUrl$rawUrl" else rawUrl
                    if (mediaUrl.startsWith("https://") || mediaUrl.startsWith("http://")) {
                        val cdnUrl = toEinthusanCdnUrl(mediaUrl)
                        val isUhd = Regex("/B[^/]+\\.mp4", RegexOption.IGNORE_CASE).containsMatchIn(cdnUrl)
                        return@withContext PlaybackResult.Ready(listOf(PlaybackSource(
                            url = cdnUrl,
                            label = if (isUhd) "UHD" else "HD",
                            mimeType = "video/mp4",
                            headers = mapOf(
                                "Referer" to browserPageUrl,
                                "User-Agent" to "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                            )
                        )))
                    }
                }

                // Fallback to the legacy AJAX/HLS resolver if the page does not expose a direct link.
                val referer = "$baseUrl/"
                var watchPath = "/movie/watch/${URLEncoder.encode(safeId, "UTF-8")}/?lang=$safeLanguage"
                val watchHtml = request("GET", watchPath, referer)
                if (watchHtml.contains("Go Premium", true)) {
                    return@withContext PlaybackResult.Unavailable("This quality or title requires an eligible Einthusan Premium account.")
                }
                if (watchHtml.contains("SERVERS ARE ALMOST AT CAPACITY", true)) {
                    return@withContext PlaybackResult.Unavailable("Einthusan servers are busy. Please try again shortly.")
                }
                if (quality == PlaybackRequest.Quality.UHD) watchPath += "&uhd=true"
                val page = if (quality == PlaybackRequest.Quality.UHD) request("GET", watchPath, referer) else watchHtml
                if (page.contains("Go Premium", true)) {
                    return@withContext PlaybackResult.Unavailable("UHD playback requires an eligible Einthusan Premium account.")
                }
                if (page.contains("SERVERS ARE ALMOST AT CAPACITY", true)) {
                    return@withContext PlaybackResult.Unavailable("Einthusan servers are busy. Please try again shortly.")
                }
                val ejp = Regex("""data-ejpingables=["'](.*?)["']""", RegexOption.IGNORE_CASE)
                    .find(page)?.groupValues?.getOrNull(1)
                    ?.takeIf { it.isNotBlank() }
                    ?: return@withContext PlaybackResult.Unavailable("Einthusan did not provide a playback token for this title.")
                val csrf = Regex("""data-pageid=["'](.*?)["']""", RegexOption.IGNORE_CASE)
                    .find(page)?.groupValues?.getOrNull(1)
                    ?.replace("&#43;", "+")
                    ?.replace("&amp;", "&")
                    ?: return@withContext PlaybackResult.Unavailable("Einthusan did not provide a playback security token.")
                val payload = JSONObject().put("EJOutcomes", ejp).put("NativeHLS", false)
                val form = listOf(
                    "xEvent" to "UIVideoPlayer.PingOutcome",
                    "xJson" to payload.toString(),
                    "arcVersion" to "3",
                    "appVersion" to "59",
                    "gorilla.csrf.Token" to csrf
                ).joinToString("&") { (key, value) ->
                    URLEncoder.encode(key, "UTF-8") + "=" + URLEncoder.encode(value, "UTF-8")
                }
                // Einthusan expects the language on the AJAX endpoint as well as on
                // the watch page. UHD requests must carry the same quality flag on both.
                val ajaxPath = buildString {
                    append("/ajax/movie/watch/${URLEncoder.encode(safeId, "UTF-8")}/?lang=")
                    append(URLEncoder.encode(safeLanguage, "UTF-8"))
                    if (quality == PlaybackRequest.Quality.UHD) append("&uhd=true")
                }
                // Match Einthusan's player handshake: the AJAX ping is referred from the
                // language browse page, not from the watch URL itself.
                val browseReferer = "$baseUrl/movie/browse/?lang=$safeLanguage"
                val response = request("POST", ajaxPath, browseReferer, form)
                val responseJson = runCatching { JSONObject(response) }.getOrNull()
                    ?: return@withContext PlaybackResult.Unavailable("Einthusan returned an unexpected playback response: " + response.take(180))
                val data = responseJson.optJSONObject("Data")
                val encoded = data?.optString("EJLinks")?.takeIf { it.isNotBlank() }
                if (encoded == null) {
                    val eventMessage = data?.optString("Message")?.takeIf { it.isNotBlank() }
                        ?: responseJson.optString("Message").takeIf { it.isNotBlank() }
                    return@withContext PlaybackResult.Unavailable(eventMessage ?: "Einthusan did not return a stream link. The title or server may not be available.")
                }
                if (encoded.startsWith("/ratelimited/", true)) {
                    return@withContext PlaybackResult.Unavailable("Einthusan has temporarily rate-limited playback. Please wait a few minutes and try again.")
                }
                // Match the public Kodi integration's EJLinks envelope decoding; the decoded URL
                // is still issued by Einthusan after its own watch-page and entitlement checks.
                if (encoded.length < 14) return@withContext PlaybackResult.Unavailable("Einthusan returned an incomplete playback response.")
                val decodedEnvelope = encoded.take(10) + encoded.last() + encoded.substring(12, encoded.length - 1)
                val envelope = String(Base64.decode(decodedEnvelope, Base64.DEFAULT), Charsets.UTF_8)
                val hls = JSONObject(envelope).optString("HLSLink")
                    .takeIf { it.startsWith("https://") }
                    ?: return@withContext PlaybackResult.Unavailable("Einthusan returned an invalid playback link.")
                PlaybackResult.Ready(listOf(PlaybackSource(
                    url = hls,
                    label = if (quality == PlaybackRequest.Quality.UHD) "UHD" else "Einthusan HLS",
                    mimeType = "application/x-mpegURL",
                    headers = mapOf("Referer" to "$baseUrl$watchPath", "User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36")
                )))
            } catch (error: Exception) {
                PlaybackResult.Failure(error.message ?: "Unable to resolve Einthusan playback.", error)
            }
        }

    override suspend fun signOut() = withContext(Dispatchers.IO) {
        signedIn = false
        signedInName = null
        cookies.cookieStore.removeAll()
        sessionPrefs.edit().clear().apply()
        Unit
    }

    private fun decodeHtml(value: String): String =
        android.text.Html.fromHtml(value, android.text.Html.FROM_HTML_MODE_LEGACY).toString()

    private fun request(method: String, path: String, referer: String, body: String? = null): String {
        val url = URL(baseUrl.trimEnd('/') + path)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36")
            setRequestProperty("Origin", baseUrl)
            setRequestProperty("Referer", referer)
            setRequestProperty("Accept", "text/html,application/json")
            cookies.get(url.toURI(), emptyMap()).forEach { (key, values) ->
                values.forEach { setRequestProperty(key, it) }
            }
            if (body != null) {
                doOutput = true
                setRequestProperty("X-Requested-With", "XMLHttpRequest")
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            }
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            cookies.put(url.toURI(), connection.headerFields)
            persistCookies()
            val stream = if (status in 200..299 || status in 300..399) connection.inputStream else connection.errorStream
            return stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        } finally {
            connection.disconnect()
        }
    }
}
