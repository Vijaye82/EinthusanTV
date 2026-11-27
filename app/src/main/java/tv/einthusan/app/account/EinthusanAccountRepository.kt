package tv.einthusan.app.account

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
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
    private val baseUrl: String = "https://einthusan.tv"
) : AccountRepository {
    companion object {
        private val cookies = CookieManager(null, CookiePolicy.ACCEPT_ALL)
        @Volatile private var signedInName: String? = null
        @Volatile private var signedIn = false
    }

    override suspend fun currentState(): AccountState = withContext(Dispatchers.IO) {
        if (signedIn) AccountState.SignedIn(signedInName) else AccountState.SignedOut
    }

    override suspend fun signIn(signInRequest: SignInRequest): AccountState = withContext(Dispatchers.IO) {
        val email = signInRequest.usernameOrEmail.trim()
        val password = String(signInRequest.password)
        signInRequest.password.fill('\u0000')
        if (email.isBlank() || password.isBlank()) return@withContext AccountState.Error("Enter your email and password.")
        try {
            val language = "tamil"
            val loginPage = request("GET", "/login/?lang=$language", referer = "$baseUrl/")
            val csrf = Regex("data-pageid=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
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
                .find(accountPage)?.groupValues?.getOrNull(1)?.replace(Regex("<[^>]+>"), "")?.trim()
            signedIn = true
            AccountState.SignedIn(signedInName)
        } catch (error: Exception) {
            AccountState.Error(error.message ?: "Unable to sign in.")
        } finally {
            signInRequest.password.fill('\u0000')
        }
    }


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
                val ejp = Regex("data-ejpingables=[\\"'](.*?)[\\"']", RegexOption.IGNORE_CASE)
                    .find(page)?.groupValues?.getOrNull(1)
                    ?.takeIf { it.isNotBlank() }
                    ?: return@withContext PlaybackResult.Unavailable("Einthusan did not provide a playback token for this title.")
                val csrf = Regex("data-pageid=[\\"'](.*?)[\\"']", RegexOption.IGNORE_CASE)
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
                val response = request("POST", "/ajax$watchPath", "$baseUrl$watchPath", form)
                val encoded = JSONObject(response).optJSONObject("Data")?.optString("EJLinks")
                    ?.takeIf { it.isNotBlank() }
                    ?: return@withContext PlaybackResult.Unavailable("Einthusan did not return a playable stream. Check your account access and try again.")
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
        Unit
    }

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
            val stream = if (status in 200..299 || status in 300..399) connection.inputStream else connection.errorStream
            return stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        } finally {
            connection.disconnect()
        }
    }
}
