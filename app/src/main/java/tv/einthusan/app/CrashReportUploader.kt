package tv.einthusan.app

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object CrashReportUploader {
    suspend fun submit(report: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val endpoint = BuildConfig.CRASH_REPORT_URL
            require(endpoint.startsWith("https://")) { "Crash report service is not configured yet." }
            val payload = JSONObject().apply {
                put("report", report.take(24000))
                put("appVersion", BuildConfig.VERSION_NAME)
                put("buildNumber", BuildConfig.VERSION_CODE)
                put("device", Build.MANUFACTURER + " " + Build.MODEL)
                put("androidVersion", Build.VERSION.RELEASE ?: "unknown")
                put("sdkInt", Build.VERSION.SDK_INT)
            }
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 12000
                readTimeout = 20000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "EinthusanTV-Android")
            }
            try {
                connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                require(code in 200..299) {
                    runCatching { JSONObject(response).optString("error") }.getOrNull()
                        ?.takeIf { it.isNotBlank() } ?: "Report service returned HTTP $code."
                }
                JSONObject(response).optString("issueUrl").ifBlank { "Report received by GitHub." }
            } finally {
                connection.disconnect()
            }
        }
    }
}
