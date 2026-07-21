package com.example.processor

import com.example.ai.GeminiResult
import com.example.ai.GeminiService
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fallback strategy: handles every command that the local strategies
 * (calls, apps, settings, navigation) could not handle, by asking Gemini.
 * MUST be the last strategy in CommandProcessor's list.
 */
@Singleton
class GeminiStrategy @Inject constructor(
    private val geminiService: GeminiService
) : CommandStrategy {

    override fun canHandle(command: String): Boolean = true

    override suspend fun execute(command: String, isScheduled: Boolean): CommandResult {
        if (!geminiService.hasUsableKey()) {
            return CommandResult(
                success = false,
                message = "عذراً، لم يتم التعرف على الأمر. أضف مفتاح Gemini من زر الإعدادات لتفعيل الردود الذكية",
                unrecognized = true // nothing understood this command -> triggers learning
            )
        }
        return when (val result = geminiService.ask(command)) {
            is GeminiResult.Success -> CommandResult(success = true, message = result.text)
            GeminiResult.NoApiKey -> CommandResult(
                success = false,
                message = "عذراً، لم يتم التعرف على الأمر. أضف مفتاح Gemini من زر الإعدادات لتفعيل الردود الذكية",
                unrecognized = true
            )
            GeminiResult.InvalidApiKey -> CommandResult(
                success = false,
                message = "مفتاح Gemini غير صحيح. تحقق منه في الإعدادات"
            )
            is GeminiResult.Failure -> CommandResult(
                success = false,
                message = "تعذر الوصول إلى Gemini. تحقق من الاتصال بالإنترنت"
            )
        }
    }
}
