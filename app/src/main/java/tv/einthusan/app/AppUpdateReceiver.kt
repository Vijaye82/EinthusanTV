package tv.einthusan.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.io.File

class AppUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = context.getSharedPreferences("app_updates", 0)
        prefs.getString("pending_apk_path", null)?.let { File(it).delete() }
        prefs.edit().clear().apply()
    }
}
