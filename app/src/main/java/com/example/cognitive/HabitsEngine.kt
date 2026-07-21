package com.example.cognitive

import com.example.data.CommandEventDao
import com.example.data.CommandEventEntity
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * محلل العادات (الذاكرة الطويلة التحليلية):
 *
 * يسجّل كل إجراء ناجح (نوع + تسمية + فترة اليوم + توقيت، باحتفاظ 90 يوماً)،
 * ثم يحوّل الأرشيف إلى معرفة تخدم الفهم:
 *  - ترشيح الغموض: «محمد» ← محمد الذي تكلمه كثيراً يتقدّم على الآخرين.
 *  - أسئلة المستخدم: «ماذا تعرف عني؟» «أكثر تطبيق أستخدمه؟» «من أكثر من أكلم؟».
 */
@Singleton
class HabitsEngine @Inject constructor(
    private val eventDao: CommandEventDao
) {

    /** سجّل إجراءً ناجحاً — وقود ذاكرة الأمد الطويل. */
    suspend fun record(kind: String, label: String?, text: String?) {
        val now = System.currentTimeMillis()
        eventDao.insert(
            CommandEventEntity(
                kind = kind,
                label = label?.trim()?.ifBlank { null },
                text = text,
                bucket = currentBucket(),
                at = now
            )
        )
        // نظافة دورية رخيصة: أرشيف 90 يوماً يكفي لاستخلاص العادات.
        eventDao.prune(now - DAY_MS * 90)
    }

    /** أكثر الكيانات استعمالاً لنوع معيّن خلال [days] يوماً. */
    suspend fun top(kind: String, days: Int = 30, limit: Int = 3) =
        eventDao.top(kind, System.currentTimeMillis() - DAY_MS * days, limit)

    /** أكثر شخص تتواصل معه (اتصال أولاً ثم واتساب) — لاقتراحات النوايا الضمنية. */
    suspend fun topContactLabel(days: Int = 30): String? {
        top("call", days, 1).firstOrNull()?.let { return it.label }
        top("whatsapp", days, 1).firstOrNull()?.let { return it.label }
        return null
    }

    /**
     * دعم ترتيب الترشيحات بناءً على العادة: هل هذا المرشّح من «أهل التكرار»؟
     * أعلى قيمة 0.15 للأكثر استعمالاً وتتناقص مع الترتيب.
     */
    suspend fun boostScore(kind: String, candidateLabel: String): Float {
        val firstToken = candidateLabel.trim().split(" ").firstOrNull() ?: return 0f
        if (firstToken.length < 2) return 0f
        val tops = top(kind, 30, 5)
        for (entry in tops) {
            val topToken = entry.label.split(" ").firstOrNull() ?: continue
            if (entry.label.contains(firstToken) || candidateLabel.contains(topToken)) {
                val rank = tops.indexOf(entry)
                return (0.15f - rank * 0.03f).coerceAtLeast(0.03f)
            }
        }
        return 0f
    }

    /** «ماذا تعرف عني؟» — ملخص محكي صادق: ما نعرفه فعلاً من أرشيفك. */
    suspend fun insights(): String {
        val since = System.currentTimeMillis() - DAY_MS * 30
        val total = eventDao.countSince(since)
        if (total < 3) {
            return "ما زلت أتعرف على عاداتك. استخدمني في أوامرك اليومية، وخلال أيام سأخبرك بملاحظات دقيقة عنك"
        }
        val parts = mutableListOf<String>()
        parts.add("سجلت لك $total استخداماً خلال ثلاثين يوماً")
        top("open_app", 30, 1).firstOrNull()?.let {
            parts.add("أكثر تطبيق تفتحه بصوتك: ${it.label}، ${it.c} مرة")
        }
        top("call", 30, 1).firstOrNull()?.let {
            parts.add("أكثر شخص تتصل به: ${it.label}")
        }
        top("whatsapp", 30, 1).firstOrNull()?.let {
            parts.add("أكثر من تراسله واتساب: ${it.label}")
        }
        eventDao.topBucket(since)?.let { b ->
            parts.add("أنشط وقت تستخدمني فيه: ${bucketArabic(b.bucket)}")
        }
        return parts.joinToString("، ")
    }

    private fun currentBucket(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        in 5..11 -> "morning"
        in 12..16 -> "afternoon"
        in 17..21 -> "evening"
        else -> "night"
    }

    private fun bucketArabic(bucket: String) = when (bucket) {
        "morning" -> "الصباح"
        "afternoon" -> "الظهر والعصر"
        "evening" -> "المساء"
        else -> "الليل"
    }

    companion object {
        private const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
