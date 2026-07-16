package com.example.processor

import com.example.managers.AppOpenerManager
import javax.inject.Inject

class AppOpenerStrategy @Inject constructor(
    private val appOpenerManager: AppOpenerManager
) : CommandStrategy {
    override fun canHandle(command: String): Boolean {
        return command.contains("افتح") || command.contains("شغل تطبيق") || command.contains("open")
    }

    override suspend fun execute(command: String, isScheduled: Boolean): CommandResult {
        val appName = extractAppName(command)
        return if (appName.isNotEmpty()) {
            val success = appOpenerManager.openApp(appName)
            if (success) CommandResult(true, "تم فتح $appName")
            else CommandResult(false, "التطبيق غير موجود على الجهاز")
        } else {
            CommandResult(false, "لم يتم تحديد اسم التطبيق")
        }
    }

    private fun extractAppName(command: String): String {
        val prefixes = listOf("افتح تطبيق", "شغل تطبيق", "افتح", "شغل", "open app", "open")
        var result = command
        for (prefix in prefixes) {
            if (result.contains(prefix)) {
                result = result.substringAfter(prefix).trim()
                break
            }
        }
        return result.split(" ")[0]
    }
}
