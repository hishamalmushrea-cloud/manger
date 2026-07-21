package com.example.processor

import android.provider.CallLog
import com.example.managers.CallManager
import com.example.managers.RecentCallsManager
import javax.inject.Inject

/**
 * Calls straight from the call log — no contact name needed:
 *  - «اتصل بآخر رقم اتصل بي» / «رد على آخر مكالمة» / «اتصل بآخر مكالمة واردة»
 *  - «اتصل بآخر رقم اتصلت به» / «آخر مكالمة صادرة»
 *  - «اتصل بالمكالمة الفائتة»
 *  - «اتصل بقبل الأخير» / «بثاني رقم» / «بالثالث»
 *
 * Must run BEFORE CallStrategy so «اتصل بآخر...» is not treated as a name.
 */
class CallLogStrategy @Inject constructor(
    private val recentCalls: RecentCallsManager,
    private val callManager: CallManager
) : CommandStrategy {

    override fun canHandle(command: String): Boolean {
        val hasCallVerb = CALL_VERBS.any { command.contains(it) }
        if (!hasCallVerb) return false
        return RECENCY_WORDS.any { command.contains(it) }
    }

    override suspend fun execute(command: String, isScheduled: Boolean): CommandResult {
        if (!recentCalls.hasCallLogPermission()) {
            return CommandResult(false, "أحتاج صلاحية سجل المكالمات — افتح التطبيق ووافق عليها")
        }

        val cmd = normalizeDigits(command)
        val (types, typeLabel) = detectCallType(cmd)
        val index = detectIndex(cmd)
        val ordinal = ordinalLabel(index)

        val entry = recentCalls.getRecentCall(types, index)
            ?: return CommandResult(false, "لا يوجد $typeLabel$ordinal في سجل مكالماتك")

        val ok = callManager.makeCall(entry.number, isScheduled)
        return if (ok) {
            CommandResult(true, "جاري الاتصال بـ ${entry.name} ($typeLabel$ordinal)")
        } else {
            CommandResult(false, "تعذر الاتصال بـ ${entry.name} — تحقق من الصلاحيات")
        }
    }

    // ------------------------------------------------------------------
    // Parsing helpers
    // ------------------------------------------------------------------

    private fun detectCallType(cmd: String): Pair<Set<Int>?, String> {
        // Order matters: check missed, then outgoing, then incoming.
        if (MISSED_WORDS.any { cmd.contains(it) }) {
            return setOf(CallLog.Calls.MISSED_TYPE, CallLog.Calls.REJECTED_TYPE) to "مكالمة فائتة"
        }
        if (OUTGOING_WORDS.any { cmd.contains(it) }) {
            return setOf(CallLog.Calls.OUTGOING_TYPE) to "اتصلتَ به"
        }
        if (INCOMING_WORDS.any { cmd.contains(it) }) {
            return setOf(CallLog.Calls.INCOMING_TYPE) to "اتصل بك"
        }
        return null to "مكالمة"
    }

    private fun detectIndex(cmd: String): Int = when {
        SECOND_WORDS.any { cmd.contains(it) } -> 1
        THIRD_WORDS.any { cmd.contains(it) } -> 2
        FOURTH_WORDS.any { cmd.contains(it) } -> 3
        else -> 0
    }

    private fun ordinalLabel(index: Int): String = when (index) {
        0 -> ""
        1 -> " — قبل الأخيرة"
        2 -> " — الثالثة"
        3 -> " — الرابعة"
        else -> " — رقم ${index + 1}"
    }

    private fun normalizeDigits(s: String): String {
        val arabicIndic = "٠١٢٣٤٥٦٧٨٩"
        return s.map { c ->
            val i = arabicIndic.indexOf(c)
            if (i >= 0) '0' + i else c
        }.joinToString("")
    }

    companion object {
        private val CALL_VERBS = listOf("اتصل", "دق", "كلم", "رد على", "رد علـى", "رن", "call")
        private val RECENCY_WORDS = listOf(
            "آخر", "اخر", "الأخير", "الاخير", "الأخيرة", "الاخيرة",
            "فائت", "فاتت", "ضائعة", "ضائعه", "واردة", "وارد", "صادرة", "صادر",
            "قبل", "ثاني", "ثالث", "رابع", "سجل المكالمات", "missed", "recent", "last"
        )
        private val MISSED_WORDS = listOf("فائت", "فاتت", "ضائعة", "ضائعه", "فوتني", "لم أرد", "missed")
        private val INCOMING_WORDS = listOf(
            "اتصل بي", "اتصل في", "اتصل بيا", "اتصل فيني", "واردة", "وارد",
            "دق علي", "دق لي", "رن علي", "رنت علي", "incoming", "اتصل لي"
        )
        private val OUTGOING_WORDS = listOf(
            "اتصلت به", "اتصلت بـ", "اتصلت ب", "اتصلت على", "اتصلت فيه",
            "اتصلت لـ", "اتصلت ل", "صادرة", "صادر", "كلمته", "outgoing"
        )
        private val SECOND_WORDS = listOf(
            "قبل الاخير", "قبل الأخير", "قبل الاخيرة", "قبل الأخيرة",
            "قبله", "قبلها", "الثاني", "الثانية", "ثاني", "ثانية", "second"
        )
        private val THIRD_WORDS = listOf("الثالث", "الثالثة", "ثالث", "third")
        private val FOURTH_WORDS = listOf("الرابع", "الرابعة", "رابع", "fourth")
    }
}
