package com.example.ai

import com.example.BuildConfig
import com.example.data.SettingsRepository
import kotlinx.coroutines.flow.first
import retrofit2.HttpException
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

sealed class GeminiResult {
    data class Success(val text: String) : GeminiResult()
    object NoApiKey : GeminiResult()
    object InvalidApiKey : GeminiResult()
    data class Failure(val reason: String) : GeminiResult()
}

@Singleton
class GeminiService @Inject constructor(
    private val api: GeminiApi,
    private val settingsRepository: SettingsRepository
) {
    companion object {
        const val DEFAULT_MODEL = "gemini-2.5-flash"

        private const val SYSTEM_PROMPT =
            "أنت «Hey Manager» مساعد صوتي عربي داخل تطبيق أندرويد. " +
            "أجب باللغة العربية بإجابة قصيرة جداً ومباشرة (جملة إلى ثلاث جمل)، " +
            "وبأسلوب محادثة طبيعي مناسب للقراءة الصوتية بدون أي رموز تنسيق أو نجوم أو قوائم."
    }

    /** Effective key: the one saved in-app takes precedence, then the build-time key. */
    private suspend fun effectiveApiKey(): String {
        val stored = settingsRepository.geminiApiKey.first().trim()
        return when {
            settingsRepository.isUsableKey(stored) -> stored
            settingsRepository.isUsableKey(BuildConfig.GEMINI_API_KEY) -> BuildConfig.GEMINI_API_KEY
            else -> ""
        }
    }

    suspend fun hasUsableKey(): Boolean = effectiveApiKey().isNotEmpty()

    /** Ask Gemini a free-form question and return a short Arabic answer. */
    suspend fun ask(userText: String): GeminiResult {
        val apiKey = effectiveApiKey()
        if (apiKey.isEmpty()) return GeminiResult.NoApiKey

        val request = GeminiRequest(
            contents = listOf(GeminiContent(parts = listOf(GeminiPart(text = userText)), role = "user")),
            systemInstruction = GeminiContent(parts = listOf(GeminiPart(text = SYSTEM_PROMPT)))
        )

        return try {
            val response = api.generateContent(DEFAULT_MODEL, apiKey, request)
            val text = response.candidates
                ?.firstOrNull()
                ?.content
                ?.parts
                ?.firstOrNull()
                ?.text
                ?.let { sanitizeForSpeech(it) }
                .orEmpty()
            if (text.isBlank()) GeminiResult.Failure("empty_response") else GeminiResult.Success(text)
        } catch (e: HttpException) {
            Timber.e(e, "Gemini HTTP error ${e.code()}")
            if (e.code() == 400 || e.code() == 403) GeminiResult.InvalidApiKey
            else GeminiResult.Failure("http_${e.code()}")
        } catch (e: IOException) {
            Timber.e(e, "Gemini network error")
            GeminiResult.Failure("network")
        } catch (e: Exception) {
            Timber.e(e, "Gemini unexpected error")
            GeminiResult.Failure("unexpected")
        }
    }

    /** Remove characters that sound bad when read aloud by TTS. */
    private fun sanitizeForSpeech(text: String): String {
        return text
            .replace(Regex("[*_#`~]"), "")
            .replace(Regex("^[\\s\\-•]+", RegexOption.MULTILINE), "")
            .trim()
    }
}
