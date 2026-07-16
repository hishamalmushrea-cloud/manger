package com.example.managers

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppOpenerManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val appMap = mapOf(
        "واتساب" to "com.whatsapp",
        "whatsapp" to "com.whatsapp",
        "يوتيوب" to "com.google.android.youtube",
        "youtube" to "com.google.android.youtube",
        "تيك توك" to "com.zhiliaoapp.musically",
        "tiktok" to "com.zhiliaoapp.musically",
        "تويتر" to "com.twitter.android",
        "إكس" to "com.twitter.android",
        "x" to "com.twitter.android",
        "انستغرام" to "com.instagram.android",
        "انستقرام" to "com.instagram.android",
        "instagram" to "com.instagram.android",
        "كروم" to "com.android.chrome",
        "جوجل كروم" to "com.android.chrome",
        "chrome" to "com.android.chrome"
    )

    fun openApp(appName: String): Boolean {
        val packageName = getPackageName(appName)
        if (packageName != null) {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return true
            }
        }
        return false
    }

    private fun getPackageName(appName: String): String? {
        val lowerName = appName.lowercase()
        appMap[lowerName]?.let { return it }

        // Fallback: search through installed packages
        val pm = context.packageManager
        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        for (appInfo in packages) {
            val name = pm.getApplicationLabel(appInfo).toString().lowercase()
            if (name.contains(lowerName) || lowerName.contains(name)) {
                return appInfo.packageName
            }
        }
        return null
    }
}
