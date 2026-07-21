package com.example.managers

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** UI-ready section of the on-screen health report. */
data class HealthSection(val emoji: String, val title: String, val lines: List<String>)

/** Full report: short spoken summary + structured sections for the dialog. */
data class HealthReport(val summary: String, val sections: List<HealthSection>)

/**
 * Device Health Monitor — answers «كيف أداء الهاتف؟» with live numbers:
 * battery %, battery temperature, RAM usage, storage, and uptime.
 * All APIs used are public/permission-free.
 *
 * NOTE: per-app battery & RAM consumption is restricted by Android
 * (BATTERY_STATS is signature-level); [openBatteryUsageScreen] deep-links
 * the user to the system's official battery-usage screen instead.
 */
@Singleton
class DeviceHealthManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    data class BatteryStat(
        val levelPct: Int,
        val charging: Boolean,
        val statusText: String,
        val temperatureC: Float,
        val healthText: String
    )

    data class MemoryStat(val totalBytes: Long, val availBytes: Long, val usedPct: Int, val isLow: Boolean)
    data class StorageStat(val totalBytes: Long, val freeBytes: Long, val usedPct: Int)

    // ------------------------------------------------------------------
    // Probes
    // ------------------------------------------------------------------

    fun battery(): BatteryStat {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val raw = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val pct = if (raw >= 0 && scale > 0) raw * 100 / scale else -1

        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val statusText = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "يشحن الآن"
            BatteryManager.BATTERY_STATUS_FULL -> "ممتلئة"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "غير موصولة بالشاحن"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "لا تشحن"
            else -> "غير معروفة"
        }
        val health = intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1
        val healthText = when (health) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "سليمة"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "سخونة زائدة"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "جهد زائد"
            BatteryManager.BATTERY_HEALTH_DEAD -> "تالفة"
            BatteryManager.BATTERY_HEALTH_COLD -> "باردة جداً"
            else -> "غير معروفة"
        }
        val tempTenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        val tempC = if (tempTenths >= 0) tempTenths / 10f else -1f

        return BatteryStat(pct, status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL, statusText, tempC, healthText)
    }

    fun memory(): MemoryStat {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val usedPct = if (info.totalMem > 0) {
            ((info.totalMem - info.availMem) * 100 / info.totalMem).toInt()
        } else -1
        return MemoryStat(info.totalMem, info.availMem, usedPct, info.lowMemory)
    }

    fun storage(): StorageStat {
        val stats = StatFs(Environment.getDataDirectory().absolutePath)
        val total = stats.totalBytes
        val free = stats.availableBytes
        val usedPct = if (total > 0) ((total - free) * 100 / total).toInt() else -1
        return StorageStat(total, free, usedPct)
    }

    fun uptimeMillis(): Long = SystemClock.elapsedRealtime()

    // ------------------------------------------------------------------
    // Verdicts & formatting
    // ------------------------------------------------------------------

    /** Temperature verdict in Arabic with a yes/no style answer. */
    fun temperatureVerdict(tempC: Float): Pair<String, Boolean> = when {
        tempC < 0 -> "تعذرت قراءة الحرارة" to false
        tempC >= 42 -> "مرتفعة" to true
        tempC >= 38 -> "أعلى من المعتاد قليلاً" to false
        else -> "طبيعية" to false
    }

    fun formatGb(bytes: Long): String =
        String.format(Locale.US, "%.1f", bytes / 1_000_000_000f)

    /** Arabic pluralization helper: 1 يوم، 2 يومان، 3-10 أيام، 11+ يوماً */
    fun pluralize(n: Long, one: String, two: String, few: String, many: String): String = when {
        n == 1L -> "$one واحد"
        n == 2L -> two
        n in 3..10 -> "$n $few"
        else -> "$n $many"
    }

    fun formatUptime(ms: Long): String {
        val days = ms / 86_400_000L
        val hours = (ms % 86_400_000L) / 3_600_000L
        val minutes = (ms % 3_600_000L) / 60_000L
        val parts = mutableListOf<String>()
        if (days > 0) parts.add(pluralize(days, "يوم", "يومان", "أيام", "يوماً"))
        if (hours > 0) parts.add(pluralize(hours, "ساعة", "ساعتان", "ساعات", "ساعة"))
        if (minutes > 0 || parts.isEmpty()) parts.add(pluralize(minutes, "دقيقة", "دقيقتان", "دقائق", "دقيقة"))
        return parts.joinToString(" و")
    }

    /** Opens the system's official per-app battery usage screen. */
    fun openBatteryUsageScreen(): Boolean {
        return try {
            context.startActivity(
                Intent(Intent.ACTION_POWER_USAGE_SUMMARY)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (e: Exception) {
            Timber.e(e, "Cannot open battery usage screen")
            false
        }
    }
}
