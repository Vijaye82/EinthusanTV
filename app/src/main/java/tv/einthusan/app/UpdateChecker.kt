package tv.einthusan.app

import android.content.Intent
import android.net.Uri
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private data class AppUpdate(val versionCode: Long, val versionName: String, val apkUrl: String)

@Composable
fun AppUpdateChecker() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var update by remember { mutableStateOf<AppUpdate?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var downloading by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        update = runCatching {
            withContext(Dispatchers.IO) {
                findLatestUpdate(context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode)
            }
        }.getOrNull()
    }

    update?.let { available ->
        AlertDialog(
            onDismissRequest = { update = null },
            title = { Text("EinthusanTV update available") },
            text = { Text("Version ${available.versionName} is available. Download and install it? Android keeps app data when the update is signed with the same key as the installed version.") },
            confirmButton = {
                TextButton(enabled = !downloading, onClick = {
                    downloading = true
                    message = null
                    scope.launch {
                        try {
                            val apk = withContext(Dispatchers.IO) { downloadApk(available.apkUrl, context.cacheDir) }
                            val prefs = context.getSharedPreferences("app_updates", 0)
                            prefs.edit().putString("pending_apk_path", apk.absolutePath)
                                .putLong("pending_version_code", available.versionCode)
                                .putBoolean("installer_launched", false).apply()
                            launchApkInstaller(context, apk)
                            update = null
                        } catch (error: Exception) {
                            message = "Could not download the update: ${error.message ?: "unknown error"}"
                        } finally {
                            downloading = false
                        }
                    }
                }) { Text(if (downloading) "Downloading…" else "Update") }
            },
            dismissButton = { TextButton(onClick = { update = null }) { Text("Cancel") } }
        )
    }

    message?.let {
        AlertDialog(onDismissRequest = { message = null }, title = { Text("Update unavailable") },
            text = { Text(it) }, confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } })
    }
}

private fun findLatestUpdate(installedVersion: Long): AppUpdate? {
    val connection = (URL("https://api.github.com/repos/Vijaye82/EinthusanTV/releases?per_page=1").openConnection() as HttpURLConnection).apply {
        connectTimeout = 12000
        readTimeout = 12000
        setRequestProperty("Accept", "application/vnd.github+json")
        setRequestProperty("User-Agent", "EinthusanTV-Android")
    }
    try {
        if (connection.responseCode !in 200..299) return null
        val releases = JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
        if (releases.length() == 0) return null
        val release = releases.getJSONObject(0)
        val tag = release.optString("tag_name")
        val versionCode = Regex("preview-(\\d+)").find(tag)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        if (versionCode <= installedVersion) return null
        val assets = release.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            if (asset.optString("name") == "EinthusanTV-preview.apk") {
                return AppUpdate(versionCode, "0.1.$versionCode", asset.optString("browser_download_url"))
            }
        }
        return null
    } finally {
        connection.disconnect()
    }
}

private fun downloadApk(url: String, cacheDir: File): File {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15000
        readTimeout = 30000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "EinthusanTV-Android")
    }
    try {
        if (connection.responseCode !in 200..299) error("GitHub returned HTTP ${connection.responseCode}")
        val target = File(cacheDir, "einthusantv-update.apk")
        connection.inputStream.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
        if (target.length() < 1024) error("The downloaded APK is incomplete")
        return target
    } finally {
        connection.disconnect()
    }
}

private fun launchApkInstaller(context: android.content.Context, apk: File) {
    if (android.os.Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
        val settings = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(settings)
        return
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
    val install = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    context.getSharedPreferences("app_updates", 0).edit().putBoolean("installer_launched", true).apply()
    context.startActivity(install)
}

fun resumePendingApkInstall(activity: android.app.Activity) {
    val prefs = activity.getSharedPreferences("app_updates", 0)
    val path = prefs.getString("pending_apk_path", null) ?: return
    val apk = File(path)
    if (!apk.exists() || prefs.getBoolean("installer_launched", false)) return
    if (android.os.Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) return
    launchApkInstaller(activity, apk)
}
