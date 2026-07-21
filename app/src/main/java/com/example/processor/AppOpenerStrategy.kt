package com.example.processor

import com.example.managers.AppOpenerManager
import javax.inject.Inject

class AppOpenerStrategy @Inject constructor(
    private val appOpenerManager: AppOpenerManager
) : CommandStrategy {

    override fun canHandle(command: String): Boolean {
        return OPEN_PREFIXES.any { command.contains(it) }
    }

    override suspend fun execute(command: String, isScheduled: Boolean): CommandResult {
        val appName = extractAppName(command)
        if (appName.isEmpty()) {
            return CommandResult(false, "لم يتم تحديد اسم التطبيق. قل مثلاً: افتح واتساب")
        }
        val resolved = appOpenerManager.openApp(appName)
        return if (resolved != null) {
            CommandResult(true, "تم فتح ${resolved.label}")
        } else {
            CommandResult(false, "لم أجد تطبيقاً باسم \"$appName\" على جهازك")
        }
    }

    internal fun extractAppName(command: String): String {
        var result = command
        for (prefix in OPEN_PREFIXES) {
            val idx = result.indexOf(prefix)
            if (idx >= 0) {
                result = result.substring(idx + prefix.length)
                break
            }
        }
        return result
            .replace(Regex("^(تطبيق|برنامج|العبة|لعبة)\\s+"), "")
            .trim()
    }

    companion object {
        // Longest first so "افتح تطبيق" wins over "افتح"
        private val OPEN_PREFIXES = listOf(
            "افتح تطبيق", "افتح برنامج", "شغل تطبيق", "شغل برنامج",
            "افتح", "شغل", "افتحلي", "شغلي", "open app", "open"
        )
    }
}
