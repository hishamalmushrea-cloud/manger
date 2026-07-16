package com.example.processor

import com.example.managers.CallManager
import javax.inject.Inject

class CallStrategy @Inject constructor(
    private val callManager: CallManager
) : CommandStrategy {
    override fun canHandle(command: String): Boolean {
        return command.contains("اتصل ب") || command.contains("دق على") || command.contains("call")
    }

    override suspend fun execute(command: String, isScheduled: Boolean): CommandResult {
        val target = extractCallTarget(command)
        return if (target.isNotEmpty()) {
            val success = callManager.makeCall(target, isScheduled)
            if (success) CommandResult(true, "جاري الاتصال بـ $target")
            else CommandResult(false, "تعذر الاتصال، يرجى التحقق من الاسم أو الصلاحيات")
        } else {
            CommandResult(false, "لم يتم تحديد جهة الاتصال")
        }
    }

    private fun extractCallTarget(command: String): String {
        val prefixes = listOf("اتصل بـ", "اتصل ب", "دق على", "call")
        var result = command
        for (prefix in prefixes) {
            if (result.contains(prefix)) {
                result = result.substringAfter(prefix).trim()
                break
            }
        }
        return result
    }
}
