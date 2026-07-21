package com.example.processor

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject

/**
 * System alarms & timers via the built-in Clock app — no API key:
 *  - «اضبط المنبه الساعة 7:30 صباحاً» / «منبه الساعة 6 مساءً»
 *  - «شغل مؤقت 10 دقائق» / «عداد 30 ثانية»
 */
class AlarmStrategy @Inject constructor(
    @ApplicationContext private val context: Context
) : CommandStrategy {

    override fun canHandle(command: String): Boolean =
        ALARM_WORDS.any { command.contains(it) } || TIMER_WORDS.any { command.contains(it) }

    override suspend fun execute(command: String, isScheduled: Boolean): CommandResult {
        // Speech-to-text may output Arabic-Indic digits (٧:٣٠) — normalize first.
        val cmd = normalizeDigits(command)
        val isTimer = TIMER_WORDS.any { cmd.contains(it) } &&
                ALARM_WORDS.none { cmd.contains(it) }
        return if (isTimer) setTimer(cmd) else setAlarm(cmd)
    }

    private fun normalizeDigits(s: String): String {
        val arabicIndic = "٠١٢٣٤٥٦٧٨٩"
        return s.map { c ->
            val i = arabicIndic.indexOf(c)
            if (i >= 0) '0' + i else c
        }.joinToString("")
    }

    // ------------------------------------------------------------------
    // Timer: «مؤقت 10 دقائق»
    // ------------------------------------------------------------------

    private fun setTimer(command: String): CommandResult {
        val match = DURATION_REGEX.find(command)
            ?: return CommandResult(false, "لم أفهم مدة المؤقت. قل مثلاً: مؤقت 10 دقائق")
        val amount = match.groupValues[1].toLongOrNull() ?: 0L
        if (amount <= 0) return CommandResult(false, "لم أفهم مدة المؤقت")
        val unitWord = match.groupValues[2]
        val totalSeconds = when {
            SECONDS_WORDS.any { unitWord.startsWith(it) } -> amount
            HOUR_WORDS.any { unitWord.startsWith(it) } -> amount * 3600
            unitWord.startsWith("hour") -> amount * 3600
            unitWord.startsWith("second") -> amount
            else -> amount * 60 // default unit: minutes
        }
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_MESSAGE, "مؤقت Hey Manager")
                putExtra(AlarmClock.EXTRA_LENGTH, "${totalSeconds}s")
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            val unitText = if (unitWord.isBlank()) "دقيقة" else unitWord
            CommandResult(true, "شغّلت مؤقتاً لمدة $amount $unitText")
        } catch (e: ActivityNotFoundException) {
            Timber.w("No clock app handles ACTION_SET_TIMER")
            CommandResult(false, "تطبيق الساعة في جهازك لا يدعم المؤقت")
        }
    }

    // ------------------------------------------------------------------
    // Alarm: «اضبط المنبه الساعة 7:30 صباحاً»
    // ------------------------------------------------------------------

    private fun setAlarm(command: String): CommandResult {
        var hour: Int
        var minute = 0

        val clockMatch = CLOCK_REGEX.find(command)
        if (clockMatch != null) {
            hour = clockMatch.groupValues[1].toIntOrNull()
                ?: return CommandResult(false, "لم أفهم الساعة المطلوبة")
            minute = clockMatch.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
        } else {
            val bare = BARE_TIME_REGEX.find(command)
                ?: return CommandResult(false, "قل مثلاً: اضبط المنبه الساعة 7:30 صباحاً")
            hour = bare.groupValues[1].toIntOrNull() ?: 0
            minute = bare.groupValues[2].toIntOrNull() ?: 0
        }

        if (hour > 23 || minute > 59) {
            return CommandResult(false, "الوقت غير مفهوم — استخدم أرقاماً مثل 7:30")
        }

        // صباحاً / مساءً
        val isPm = PM_WORDS.any { command.contains(it) }
        val isAm = AM_WORDS.any { command.contains(it) }
        if (isPm && hour in 1..11) hour += 12
        if (isAm && hour == 12) hour = 0

        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, "منبه Hey Manager")
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            CommandResult(true, "ضبطت المنبه على الساعة ${format12(hour)}:${"%02d".format(minute)}")
        } catch (e: ActivityNotFoundException) {
            Timber.w("No clock app handles ACTION_SET_ALARM")
            CommandResult(false, "لا يوجد تطبيق ساعة يدعم المنبه في جهازك")
        }
    }

    private fun format12(h: Int): Int = when {
        h == 0 -> 12
        h > 12 -> h - 12
        else -> h
    }

    companion object {
        private val ALARM_WORDS = listOf("منبه", "المنبه", "alarm")
        private val TIMER_WORDS = listOf("مؤقت", "ميقات", "عداد", "تايمر", "timer")
        private val PM_WORDS = listOf("مساء", "ليلا", "ليلاً", "مغرب", "عشاء", "عصر", "ظهر")
        private val AM_WORDS = listOf("صباح", "فجر")
        private val SECONDS_WORDS = listOf("ثانية", "ثواني", "ثوان", "ثانيه")
        private val HOUR_WORDS = listOf("ساعة", "ساعات")
        private val CLOCK_REGEX = Regex("الساعة\\s+(\\d{1,2})(?::(\\d{1,2}))?")
        private val BARE_TIME_REGEX = Regex("(\\d{1,2}):(\\d{1,2})")
        private val DURATION_REGEX = Regex(
            "(\\d+)\\s*(ثانية|ثواني|ثوان|ثانيه|دقيقة|دقائق|دقيقه|ساعة|ساعات|hours?|minutes?|seconds?)?"
        )
    }
}
