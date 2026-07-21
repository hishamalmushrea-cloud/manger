package com.example.cognitive

import com.example.data.CommandEventDao
import com.example.managers.ContactMatch
import com.example.managers.ConversationContextManager
import com.example.managers.LearningManager
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * محرك الثقة متعدد العوامل — الثقة ليست رقماً ثابتاً بل محصلة موزونة:
 *
 *   تشابه الاسم        40%
 *   سياق المحادثة       20%   (هل ذُكر هذا الشخص قبل قليل؟)
 *   حداثة الاستخدام     15%   (متى آخر تواصل معه؟ اضمحلال زمني)
 *   عادة توقيت اليوم    10%   (تكلمه عادة في هذه الفترة؟)
 *   جلسة نشطة           10%   (محادثة حيّة ترفع السياق)
 *   العادات العامة       5%   (الأكثر تكراراً في أرشيفك)
 *                          × مراقبة النفس (سجل نوع الإجراء يعدّل الناتج)
 *
 * سياسة القرار (محرك التأني): ≥85% بهامش 15% ينفّذ فوراً · 60..85% يسأل
 * بقائمة مرتبة · <60% يطلب إعادة الصياغة بدل التخمين الخاطئ.
 */
@Singleton
class ConfidenceEngine @Inject constructor(
    private val habits: HabitsEngine,
    private val eventDao: CommandEventDao,
    private val conversationContext: ConversationContextManager,
    private val selfEval: SelfEvaluationEngine
) {

    /** نتيجة تقييم مرشح: الرقم النهائي + مبرر بشري قصير يُقال في السؤال. */
    data class ContactScore(
        val match: ContactMatch,
        val confidence: Float,
        val note: String?
    )

    suspend fun assessContact(query: String, kind: String, match: ContactMatch): ContactScore {
        val normQuery = LearningManager.normalize(query)
        val normLabel = LearningManager.normalize(match.displayName)

        // 40% — تشابه الاسم (Jaccard على المقاطع + علاوة البادئة والتطابق الكامل)
        var sim = tokenSimilarity(normQuery, normLabel)
        if (normQuery.isNotEmpty() && normLabel.startsWith(normQuery)) sim = maxOf(sim, 0.85f)
        if (normLabel == normQuery) sim = 1.0f
        var score = 0.40f * sim
        var note: String? = null

        // 20% — سياق المحادثة: هذا الشخص ذُكر خلال دقيقتين
        conversationContext.lastEntityOf(
            ConversationContextManager.EntityKind.CONTACT
        )?.let { ent ->
            val normEnt = LearningManager.normalize(ent.label)
            val fresh = System.currentTimeMillis() - ent.at <= CONTEXT_FRESH_MS
            if (fresh && normEnt.isNotEmpty() &&
                (normLabel.contains(normEnt) || normEnt.contains(normLabel))
            ) {
                score += 0.20f
                note = "سياق حديث في محادثتنا"
            }
        }

        // 15% — حداثة الاستخدام: اضمحلال يوم/أسبوع/شهر
        val firstToken = normLabel.split(" ").firstOrNull() ?: normLabel
        val lastAt = eventDao.lastUsedAt(kind, "%$firstToken%")
        val now = System.currentTimeMillis()
        val recency = when {
            lastAt == null -> 0f
            now - lastAt < DAY_MS -> 1.0f
            now - lastAt < 7 * DAY_MS -> 0.6f
            now - lastAt < 30 * DAY_MS -> 0.3f
            else -> 0f
        }
        if (recency >= 1.0f && note == null) note = "الأكثر تواصلاً مؤخراً"
        score += 0.15f * recency

        // 10% — عادة توقيت اليوم: تكلمه عادة في هذه الفترة
        val bucketHits = eventDao.bucketCount(
            kind, currentBucket(), "%$firstToken%", now - 30 * DAY_MS
        )
        score += 0.10f * when {
            bucketHits >= 3 -> 1.0f
            bucketHits > 0 -> 0.5f
            else -> 0f
        }

        // 10% — جلسة محادثة نشطة (تطبيق فُتح قبل قليل = سياق حي)
        conversationContext.lastEntityOf(
            ConversationContextManager.EntityKind.APP
        )?.let {
            if (now - it.at <= CONTEXT_FRESH_MS) score += 0.10f
        }

        // 5% — العادات العامة (الأرشيف الطويل)
        val habitFactor = (habits.boostScore(kind, match.displayName) / 0.15f).coerceIn(0f, 1f)
        score += 0.05f * habitFactor

        // مراقبة النفس تُدخل حكمها على الناتج النهائي
        score *= selfEval.successFactor(kind)

        return ContactScore(match, score.coerceIn(0f, 1f), note)
    }

    /** تشابه المقاطع: نسبة المشترك إلى الاتحاد (0..1). */
    private fun tokenSimilarity(a: String, b: String): Float {
        if (a.isBlank() || b.isBlank()) return 0f
        val sa = a.split(" ").filter { it.isNotBlank() }.toSet()
        val sb = b.split(" ").filter { it.isNotBlank() }.toSet()
        val union = sa.union(sb).size
        if (union == 0) return 0f
        return sa.intersect(sb).size.toFloat() / union
    }

    private fun currentBucket(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        in 5..11 -> "morning"
        in 12..16 -> "afternoon"
        in 17..21 -> "evening"
        else -> "night"
    }

    companion object {
        private const val CONTEXT_FRESH_MS = 120_000L
        private const val DAY_MS = 24L * 60 * 60 * 1000

        /** عتبات محرك التأني — معلنة لأن فلسفتها صريحة بالكامل. */
        const val DIRECT_EXECUTE_THRESHOLD = 0.85f
        const val DIRECT_EXECUTE_MARGIN = 0.15f
        const val REPHRASE_THRESHOLD = 0.60f
        const val REPHRASE_MARGIN = 0.10f
    }
}
