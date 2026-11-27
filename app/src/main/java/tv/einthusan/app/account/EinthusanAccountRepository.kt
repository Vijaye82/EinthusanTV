package tv.einthusan.app.account

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

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

    override suspend fun signIn(request: SignInRequest): AccountState = withContext(Dispatchers.IO) {
        val email = request.usernameOrEmail.trim()
        val password = String(request.password)
        request.password.fill('\u0000')
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
            request.password.fill('\u0000')
        }
    }

    override suspend fun signOut() = withContext(Dispatchers.IO) {
        signedIn = false
        signedInName = null
        cookies.cookieStore.removeAll()
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
            cookies.put(url.toURI(), connection.headerFields)
            val status = connection.responseCode
            val stream = if (status in 200..299 || status in 300..399) connection.inputStream else connection.errorStream
            return stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        } finally {
            connection.disconnect()
        }
    }
}
