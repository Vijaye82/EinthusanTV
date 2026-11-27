package tv.einthusan.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.io.File

class AppUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val prefs = context.getSharedPreferences("app_updates", 0)
        val pendingPath = prefs.getString("pending_apk_path", null)

        // Remove the installed APK and any leftover update-download files.
        pendingPath?.let { File(it).delete() }
        context.cacheDir.listFiles()
            ?.filter { it.name.startsWith("einthusantv-update") }
            ?.forEach { it.delete() }

        prefs.edit().clear().apply()
    }
}
