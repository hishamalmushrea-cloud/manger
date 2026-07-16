package com.example.processor

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.example.managers.SystemManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class SystemSettingStrategy @Inject constructor(
    @ApplicationContext private val context: Context,
    private val systemManager: SystemManager
) : CommandStrategy {
    override fun canHandle(command: String): Boolean {
        return command.contains("واي فاي") || command.contains("wifi") ||
               command.contains("بلوتوث") || command.contains("bluetooth") ||
               command.contains("فلاش") || command.contains("كشاف") || command.contains("flashlight") ||
               command.contains("طيران") || command.contains("airplane") ||
               command.contains("سطوع") || command.contains("إضاءة") || command.contains("brightness")
    }

    override suspend fun execute(command: String, isScheduled: Boolean): CommandResult {
        val enable = command.contains("شغل") || command.contains("افتح") || command.contains("on") || command.contains("رفع") || command.contains("زيادة")
        
        if (command.contains("واي فاي") || command.contains("wifi")) {
            systemManager.setWifiState(enable)
            return CommandResult(true, "تم تعديل حالة الواي فاي")
        }
        
        if (command.contains("بلوتوث") || command.contains("bluetooth")) {
            systemManager.setBluetoothState(enable)
            return CommandResult(true, "تم فتح إعدادات البلوتوث")
        }
        
        if (command.contains("فلاش") || command.contains("كشاف") || command.contains("flashlight")) {
            val success = systemManager.setFlashlight(enable)
            return if (success) CommandResult(true, "تم تعديل الفلاش")
            else CommandResult(false, "تعذر تشغيل الفلاش")
        }
        
        if (command.contains("طيران") || command.contains("airplane")) {
            systemManager.openAirplaneModeSettings()
            return CommandResult(true, "تم فتح إعدادات وضع الطيران")
        }

        if (command.contains("سطوع") || command.contains("إضاءة") || command.contains("brightness")) {
            // Android requires WRITE_SETTINGS permission for brightness.
            return try {
                if (Settings.System.canWrite(context)) {
                    val value = if (enable) 255 else 50
                    Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                    Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, value)
                    CommandResult(true, "تم تعديل السطوع")
                } else {
                    CommandResult(false, "يرجى منح صلاحية تعديل الإعدادات أولاً")
                }
            } catch (e: Exception) {
                CommandResult(false, "حدث خطأ أثناء تعديل السطوع")
            }
        }
        
        return CommandResult(false, "أمر نظام غير معروف")
    }
}
