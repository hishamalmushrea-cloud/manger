package com.example.managers

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Opens ANY installed app by its spoken name (Arabic or English).
 *
 * How it finds apps:
 *  1. Well-known aliases (Arabic/English) for popular apps.
 *  2. All visible launcher apps (see <queries> in the manifest) with a
 *     normalization + fuzzy-scoring matcher that tolerates hamza/ya variants,
 *     partial names, and small typos.
 */
@Singleton
class AppOpenerManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    data class ResolvedApp(val packageName: String, val label: String)

    /** Cached snapshot of launchable apps: normalizedLabel -> (packageName, displayLabel). */
    @Volatile
    private var launchableApps: Map<String, ResolvedApp>? = null

    /** Spoken aliases -> package name (checked only if that app is installed & launchable). */
    private val aliasMap: Map<String, String> = buildMap {
        fun putAll(packageName: String, vararg aliases: String) {
            aliases.forEach { put(normalize(it), packageName) }
        }
        putAll("com.whatsapp", "واتساب", "واتس اب", "واتس", "وتساب", "وتس", "whatsapp")
        putAll("com.whatsapp.w4b", "واتساب بيزنس", "واتس بزنس", "whatsapp business")
        putAll("com.google.android.youtube", "يوتيوب", "اليوتيوب", "يو تيوب", "youtube")
        putAll("com.zhiliaoapp.musically", "تيك توك", "تيكتوك", "تك توك", "tiktok")
        putAll("com.twitter.android", "تويتر", "إكس", "اكس", "x", "twitter")
        putAll("com.instagram.android", "انستغرام", "انستقرام", "انستا", "انستاجرم", "انتسقرام", "Instagram", "insta")
        putAll("com.facebook.katana", "فيسبوك", "فيس بوك", "الفيس", "فيس", "facebook")
        putAll("com.facebook.orca", "ماسنجر", "الماسنجر", "ميسنجر", "messenger")
        putAll("com.snapchat.android", "سناب شات", "سنابشات", "سناب", "snapchat")
        putAll("org.telegram.messenger", "تيليجرام", "تليجرام", "تيليغرام", "تيلقرام", "telegram")
        putAll("com.spotify.music", "سبوتيفاي", "سبوتفاي", "spotify")
        putAll("com.netflix.mediaclient", "نتفليكس", "نتفلكس", "netflix")
        putAll("com.google.android.apps.maps", "خرائط", "الخرائط", "قوقل ماب", "جوجل مابس", "خرائط جوجل", "maps", "google maps")
        putAll("com.google.android.gm", "جيميل", "الايميل", "البريد", "ايميل", "gmail", "email")
        putAll("com.android.chrome", "كروم", "جوجل كروم", "قوقل كروم", "المتصفح", "متصفح", "chrome", "browser")
        putAll("com.google.android.apps.photos", "الصور", "صور", "المعرض", "معرض الصور", "بيكسل الصور", "photos", "gallery")
        putAll("com.android.vending", "متجر بلاي", "البلاي ستور", "جوجل بلاي", "بلاي ستور", "المتجر", "play store", "google play")
        putAll("com.google.android.apps.youtube.music", "يوتيوب ميوزك", "يوتيوب موسيقى", "youtube music")
        putAll("com.microsoft.teams", "تيمز", "teams")
        putAll("us.zoom.videomeetings", "زوم", "zoom")
        putAll("com.google.android.apps.meetings", "ميت", "جوجل ميت", "google meet")
        putAll("com.amazon.mShop.android.shopping", "امازون", "amazon")
        putAll("com.google.android.calendar", "التقويم", "تقويم", "calendar")
        putAll("com.google.android.deskclock", "المنبه", "منبه", "الساعة", "clock", "alarm")
        putAll("com.google.android.calculator", "الحاسبة", "حاسبة", "الآلة الحاسبة", "calculator")
        putAll("com.android.settings", "الإعدادات", "الاعدادات", "إعدادات الجهاز", "settings")
        putAll("com.google.android.keep", "الملاحظات", "ملاحظات", "كيب", "notes", "keep")
        putAll("com.android.camera2", "الكاميرا", "كاميرا", "camera")
    }

    /** Open the app matching [spokenName]. Returns the resolved app info, or null if not found. */
    fun openApp(spokenName: String): ResolvedApp? {
        val resolved = resolveApp(spokenName) ?: return null
        return if (launchApp(resolved.packageName)) resolved else null
    }

    /** Resolve a spoken name to an installed app without opening it (used for nicer replies). */
    fun resolveApp(spokenName: String): ResolvedApp? {
        val normalizedInput = normalize(spokenName)
        if (normalizedInput.isEmpty()) return null
        val apps = getLaunchableApps()

        // 1) Alias map
        aliasMap[normalizedInput]?.let { pkg ->
            apps.values.firstOrNull { it.packageName == pkg }?.let { return it }
        }

        // 2) Exact label
        apps[normalizedInput]?.let { return it }

        // 3) Fuzzy scoring over all labels
        var best: ResolvedApp? = null
        var bestScore = 0
        for ((label, app) in apps) {
            val score = matchScore(normalizedInput, label)
            if (score > bestScore) {
                bestScore = score
                best = app
            }
        }
        if (best != null && bestScore >= MIN_SCORE) return best

        // 4) Last resort: package-name match ("com.zhiliaoapp.musically" style)
        apps.values.firstOrNull { normalize(it.packageName).contains(normalizedInput) }?.let { return it }

        Timber.d("No app found for '%s' (normalized '%s')", spokenName, normalizedInput)
        return null
    }

    private fun launchApp(packageName: String): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            } else {
                Timber.w("No launch intent for %s", packageName)
                false
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to launch %s", packageName)
            false
        }
    }

    /** All installed apps with a launcher entry (thanks to the <queries> declaration). */
    private fun getLaunchableApps(): Map<String, ResolvedApp> {
        launchableApps?.let { return it }
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities: List<ResolveInfo> = try {
            pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        } catch (e: Exception) {
            pm.queryIntentActivities(intent, 0)
        }
        val map = HashMap<String, ResolvedApp>(activities.size)
        for (info in activities) {
            val pkg = info.activityInfo?.packageName ?: continue
            val label = info.loadLabel(pm).toString().trim()
            if (label.isEmpty()) continue
            val normalized = normalize(label)
            // Keep first entry per normalized label
            if (!map.containsKey(normalized)) {
                map[normalized] = ResolvedApp(pkg, label)
            }
        }
        launchableApps = map
        Timber.d("Indexed %d launchable apps", map.size)
        return map
    }

    /** Invalidates the cached app list (call if apps are installed/removed at runtime). */
    fun refreshInstalledApps() {
        launchableApps = null
    }

    // ----------------------------------------------------------------------
    // Matching internals
    // ----------------------------------------------------------------------

    private fun matchScore(input: String, label: String): Int {
        if (input.isEmpty() || label.isEmpty()) return 0
        if (input == label) return 100
        if (label.startsWith(input)) return 85
        if (input.startsWith(label)) return 85
        if (label.contains(input)) return 70
        if (input.contains(label)) return 70
        val distance = levenshtein(input, label)
        val maxLen = maxOf(input.length, label.length)
        val similarity = 100 - (distance * 100 / maxLen)
        return if (similarity >= 60) similarity / 2 else 0 // typo tolerance, capped below partial matches
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
            }
            val tmp = prev; prev = curr; curr = tmp
        }
        return prev[b.length]
    }

    companion object {
        private const val MIN_SCORE = 30

        /** Normalize text for matching: Arabic-aware (hamza/taa-marbuta/ya/diacritics). */
        fun normalize(text: String): String {
            return text
                .lowercase()
                .replace(Regex("[ً-ْٔۡـ]"), "") // tashkeel + tatweel
                .replace('أ', 'ا')
                .replace('إ', 'ا')
                .replace('آ', 'ا')
                .replace('ة', 'ه')
                .replace('ى', 'ي')
                .replace(Regex("ال"), "") // drop leading/embedded "ال" for tolerance
                .replace(Regex("\\s+"), " ")
                .trim()
        }
    }
}
