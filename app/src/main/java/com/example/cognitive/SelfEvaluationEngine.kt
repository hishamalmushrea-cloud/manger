package com.example.cognitive

import com.example.data.SelfStatDao
import com.example.data.SelfStatEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * مراقبة النفس: يسجّل كل محاولة (نجاح/فشل/مدة) وتصحيحات المستخدم لكل نوع
 * إجراء، ويحوّلها إلى:
 *  - عامل ثقة حي: نسبة نجاح مرتفعة ترفع الثقة، ومنخفضة تُنزلها وتحفّز السؤال.
 *  - تقرير صادق: «ما نسبة نجاحك؟» بأرقام حقيقية من دفتر الإحصاء.
 */
@Singleton
class SelfEvaluationEngine @Inject constructor(
    private val dao: SelfStatDao
) {

    suspend fun noteResult(actionKey: String, success: Boolean, durationMs: Long) {
        val now = System.currentTimeMillis()
        val existing = dao.find(actionKey)
        val updated = if (existing != null) {
            existing.copy(
                attempts = existing.attempts + 1,
                successes = existing.successes + (if (success) 1 else 0),
                totalMs = existing.totalMs + durationMs.coerceAtLeast(0),
                lastAt = now
            )
        } else {
            SelfStatEntity(
                actionKey = actionKey,
                attempts = 1,
                successes = if (success) 1 else 0,
                corrections = 0,
                totalMs = durationMs.coerceAtLeast(0),
                lastAt = now
            )
        }
        dao.upsert(updated)
    }

    suspend fun noteCorrection(actionKey: String) {
        val now = System.currentTimeMillis()
        val existing = dao.find(actionKey)
        val updated = if (existing != null) {
            existing.copy(corrections = existing.corrections + 1, lastAt = now)
        } else {
            SelfStatEntity(
                actionKey = actionKey, attempts = 0, successes = 0,
                corrections = 1, totalMs = 0, lastAt = now
            )
        }
        dao.upsert(updated)
    }

    /**
     * عامل ضرب على الثقة (0.80 .. 1.05):
     * سجل ممتاز يرفع الثقة قليلاً، وسجل مهزوز يخفضها كي يتأنى ويسأل.
     * بيانات قليلة (أقل من 4 محاولات) = حياد تام.
     */
    suspend fun successFactor(actionKey: String): Float {
        val s = dao.find(actionKey) ?: return 1.0f
        if (s.attempts < 4) return 1.0f
        val rate = s.successes.toFloat() / s.attempts
        return when {
            rate >= 0.90f -> 1.05f
            rate >= 0.75f -> 1.00f
            rate >= 0.55f -> 0.90f
            else -> 0.80f
        }
    }

    /** «ما نسبة نجاحك؟» — تقرير صادق من دفتر الإحصاء، بلا تجميل. */
    suspend fun report(): String {
        val stats = dao.all().filter { it.attempts + it.corrections >= 2 }
        if (stats.isEmpty()) {
            return "لم أجمع بعد إحصاءات كافية عن أدائي. استخدمني في يومك، وبعد فترة سأجيبك عن هذا السؤال بأرقام دقيقة"
        }
        val parts = mutableListOf<String>()
        stats.take(4).forEach { s ->
            if (s.attempts >= 2) {
                val rate = (s.successes * 100) / s.attempts
                parts.add("${labelFor(s.actionKey)}: نجاح $rate% من ${s.attempts} محاولة")
            }
        }
        val weakest = stats.filter { it.attempts >= 5 }.minByOrNull {
            it.successes.toFloat() / it.attempts
        }
        weakest?.let {
            val rate = (it.successes * 100) / it.attempts
            if (rate < 75) {
                parts.add("أضعف جانبي حالياً ${labelFor(it.actionKey)} بنجاح $rate% — سأتأنى وأسألك أكثر فيه")
            }
        }
        val totalCorrections = stats.sumOf { it.corrections }
        if (totalCorrections > 0) {
            parts.add("صححت لي $totalCorrections مرة، وكل تصحيح تعلمت منه")
        }
        return if (parts.isEmpty()) {
            "إحصاءات أدائي ما زالت قليلة، امنحني أياماً إضافية"
        } else {
            "تقريري الصادق عن نفسي: " + parts.joinToString("، ")
        }
    }

    /** أسماء بشرية لأنواع الإجراءات في تقرير الأداء. */
    private fun labelFor(actionKey: String) = when (actionKey) {
        "system" -> "إعدادات النظام"
        "navigation" -> "التنقل"
        "volume" -> "التحكم بالصوت"
        "alarm" -> "المنبه والمؤقت"
        "health" -> "صحة الجهاز"
        "info" -> "المعلومات"
        "call_log" -> "سجل المكالمات"
        "call" -> "الاتصال"
        "whatsapp" -> "واتساب"
        "sms" -> "الرسائل"
        "youtube" -> "يوتيوب"
        "music" -> "الموسيقى"
        "file_search" -> "البحث عن الملفات"
        "search" -> "البحث"
        "open_app" -> "فتح التطبيقات"
        "gemini" -> "جيميناي"
        else -> "أوامر عامة"
    }
}
