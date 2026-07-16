package com.example.processor

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.example.service.NavigationAccessibilityService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class NavigationStrategy @Inject constructor(
    @ApplicationContext private val context: Context
) : CommandStrategy {
    override fun canHandle(command: String): Boolean {
        return command.contains("رجوع") || command.contains("back") ||
               command.contains("رئيسية") || command.contains("home") ||
               command.contains("تطبيقات أخيرة") || command.contains("recent")
    }

    override suspend fun execute(command: String, isScheduled: Boolean): CommandResult {
        if (command.contains("رجوع") || command.contains("back")) {
            return performAccessibilityAction(AccessibilityService.GLOBAL_ACTION_BACK, "رجوع")
        }
        if (command.contains("رئيسية") || command.contains("home")) {
            return performAccessibilityAction(AccessibilityService.GLOBAL_ACTION_HOME, "الرئيسية")
        }
        if (command.contains("تطبيقات أخيرة") || command.contains("recent")) {
            return performAccessibilityAction(AccessibilityService.GLOBAL_ACTION_RECENTS, "التطبيقات الأخيرة")
        }
        return CommandResult(false, "أمر تنقل غير معروف")
    }

    private fun performAccessibilityAction(action: Int, actionName: String): CommandResult {
        if (NavigationAccessibilityService.isAccessibilityServiceEnabled()) {
            val success = NavigationAccessibilityService.performGlobalActionStatic(action)
            return if (success) CommandResult(true, "تم تنفيذ $actionName")
            else CommandResult(false, "تعذر تنفيذ $actionName")
        } else {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            return CommandResult(false, "يرجى تفعيل صلاحية إمكانية الوصول للتطبيق من الإعدادات")
        }
    }
}
