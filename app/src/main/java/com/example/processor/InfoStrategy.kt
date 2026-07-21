package com.example.processor

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/** Quick spoken info: current time, today's date, battery level. */
class InfoStrategy @Inject constructor(
    @ApplicationContext private val context: Context
) : CommandStrategy {

    override fun canHandle(command: String): Boolean {
        return command.contains("الساعة") || command.contains("الوقت") ||
                command.contains("time") || command.contains("التاريخ") ||
                command.contains("اليوم كم") || command.contains("كم اليوم") ||
                command.contains("date") || command.contains("بطارية") ||
                command.contains("البطارية") || command.contains("شحن") ||
                command.contains("battery")
    }

    override suspend fun execute(command: String, isScheduled: Boolean): CommandResult {
        return when {
            command.contains("بطارية") || command.contains("البطارية") ||
                command.contains("شحن") || command.contains("battery") -> batteryInfo()
            command.contains("تاريخ") || command.contains("اليوم") ||
                command.contains("date") -> CommandResult(true, currentDate())
            else -> CommandResult(true, currentTime())
        }
    }

    private fun currentTime(): String {
        val hour = SimpleDateFormat("H", Locale.US).format(Date()).toInt()
        val period = if (hour < 12) "صباحاً" else if (hour < 18) "مساءً" else "ليلاً"
        val time = SimpleDateFormat("h:mm", Locale("ar")).format(Date())
        return "الآن الساعة $time $period"
    }

    private fun currentDate(): String {
        val date = SimpleDateFormat("EEEE، d MMMM yyyy", Locale("ar")).format(Date())
        return "اليوم هو $date"
    }

    private fun batteryInfo(): CommandResult {
        val batteryIntent: Intent? = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level < 0 || scale <= 0) return CommandResult(false, "تعذر قراءة حالة البطارية")

        val percent = (level * 100) / scale
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        return CommandResult(
            true,
            "نسبة البطارية $percent بالمائة" + if (charging) " والجهاز موصول بالشاحن" else ""
        )
    }
}
