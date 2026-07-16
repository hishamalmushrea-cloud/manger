package com.example.processor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class CommandResult(val success: Boolean, val message: String)

@Singleton
class CommandProcessor @Inject constructor(
    appOpenerStrategy: AppOpenerStrategy,
    callStrategy: CallStrategy,
    systemSettingStrategy: SystemSettingStrategy,
    navigationStrategy: NavigationStrategy
) {
    // Strategy Pattern implementation
    private val strategies = listOf(
        appOpenerStrategy,
        callStrategy,
        systemSettingStrategy,
        navigationStrategy
    )

    suspend fun processCommand(rawCommand: String, isScheduled: Boolean = false): CommandResult = withContext(Dispatchers.IO) {
        val command = rawCommand.lowercase().trim()
        
        try {
            for (strategy in strategies) {
                if (strategy.canHandle(command)) {
                    return@withContext strategy.execute(command, isScheduled)
                }
            }
            return@withContext CommandResult(false, "عذراً، لم يتم التعرف على الأمر")
        } catch (e: Exception) {
            return@withContext CommandResult(false, "حدث خطأ غير متوقع: ${e.message}")
        }
    }
}
