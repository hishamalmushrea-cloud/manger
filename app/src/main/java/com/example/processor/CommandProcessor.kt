package com.example.processor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * @param unrecognized true only when NO strategy (incl. the AI fallback)
 * could understand the command — this is the "low confidence" signal that
 * drives the user-learning flow in MainViewModel.
 * @param handledBy short human-readable label of the capability that handled
 * the command — shown in the Command Center as «الإجراء الذي تم تنفيذه».
 * @param actionKey stable machine key of the handled action (open_app, volume,
 * call, ...) — feeds the conversation engine (context entities + undo).
 * @param confidence understanding confidence 0.0..1.0 — shown in the Command
 * Center as «نسبة الثقة في الفهم».
 */
data class CommandResult(
    val success: Boolean,
    val message: String,
    val unrecognized: Boolean = false,
    val handledBy: String? = null,
    val actionKey: String? = null,
    val confidence: Float = 0f,
    /** Optional rich payload for the on-screen Device Health dialog. */
    val healthReport: com.example.managers.HealthReport? = null,
    /** Optional clickable file-search results for the on-screen dialog. */
    val fileHits: List<com.example.managers.FileHit>? = null
)

@Singleton
class CommandProcessor @Inject constructor(
    systemSettingStrategy: SystemSettingStrategy,
    navigationStrategy: NavigationStrategy,
    volumeStrategy: VolumeStrategy,
    alarmStrategy: AlarmStrategy,
    healthStrategy: HealthStrategy,
    infoStrategy: InfoStrategy,
    callLogStrategy: CallLogStrategy,
    callStrategy: CallStrategy,
    whatsAppStrategy: WhatsAppStrategy,
    smsStrategy: SmsStrategy,
    youTubeStrategy: YouTubeStrategy,
    musicStrategy: MusicStrategy,
    fileSearchStrategy: FileSearchStrategy,
    searchStrategy: SearchStrategy,
    appOpenerStrategy: AppOpenerStrategy,
    geminiStrategy: GeminiStrategy
) {
    // Strategy Pattern implementation — ordered by specificity.
    // System/Navigation/Volume/Alarm/Info come FIRST so their keywords are
    // never swallowed by the generic "افتح"/"شغل" app opener.
    // CallLogStrategy precedes CallStrategy («اتصل بآخر رقم» ≠ a contact name).
    // WhatsAppStrategy precedes SmsStrategy («أرسل رسالة واتساب» ≠ a plain SMS).
    // NOTE: GeminiStrategy is intentionally LAST as the AI-powered fallback.
    private val strategies = listOf(
        systemSettingStrategy,
        navigationStrategy,
        volumeStrategy,
        alarmStrategy,
        healthStrategy,
        infoStrategy,
        callLogStrategy,
        callStrategy,
        whatsAppStrategy,
        smsStrategy,
        youTubeStrategy,
        musicStrategy,
        fileSearchStrategy,
        searchStrategy,
        appOpenerStrategy,
        geminiStrategy
    )

    suspend fun processCommand(rawCommand: String, isScheduled: Boolean = false): CommandResult = withContext(Dispatchers.IO) {
        val command = rawCommand.lowercase().trim()
        
        try {
            for (strategy in strategies) {
                if (strategy.canHandle(command)) {
                    val result = strategy.execute(command, isScheduled)
                    val (key, label) = tagFor(strategy)
                    return@withContext result.copy(
                        handledBy = label,
                        actionKey = key,
                        confidence = when {
                            // Gemini refused (no key) → command went to learning mode.
                            result.unrecognized -> 0.10f
                            // AI-generated replies are useful but inherently fuzzy.
                            strategy is GeminiStrategy -> 0.70f
                            // Deterministic keyword strategy — very high certainty.
                            else -> 0.95f
                        }
                    )
                }
            }
            return@withContext CommandResult(
                success = false,
                message = "عذراً، لم يتم التعرف على الأمر",
                unrecognized = true,
                confidence = 0.05f
            )
        } catch (e: Exception) {
            return@withContext CommandResult(false, "حدث خطأ غير متوقع: ${e.message}")
        }
    }

    /**
     * Stable machine key (feeds the conversation engine) + human-readable
     * capability label (shown in the Command Center).
     */
    private fun tagFor(strategy: CommandStrategy): Pair<String, String> = when (strategy) {
        is SystemSettingStrategy -> "system" to "ضبط إعدادات الجهاز"
        is NavigationStrategy -> "navigation" to "التنقل داخل الجهاز"
        is VolumeStrategy -> "volume" to "التحكم بمستوى الصوت"
        is AlarmStrategy -> "alarm" to "المنبه والمؤقت"
        is HealthStrategy -> "health" to "تقرير صحة الجهاز"
        is InfoStrategy -> "info" to "معلومات الوقت والتاريخ"
        is CallLogStrategy -> "call_log" to "قراءة سجل المكالمات"
        is CallStrategy -> "call" to "إجراء مكالمة"
        is WhatsAppStrategy -> "whatsapp" to "تجهيز رسالة واتساب"
        is SmsStrategy -> "sms" to "تجهيز رسالة SMS"
        is YouTubeStrategy -> "youtube" to "بحث يوتيوب"
        is MusicStrategy -> "music" to "تشغيل الموسيقى"
        is FileSearchStrategy -> "file_search" to "البحث عن ملفات"
        is SearchStrategy -> "search" to "بحث في الإنترنت"
        is AppOpenerStrategy -> "open_app" to "فتح تطبيق"
        is GeminiStrategy -> "gemini" to "رد ذكاء اصطناعي"
        else -> "other" to "أمر عام"
    }
}
