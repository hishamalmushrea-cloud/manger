package com.example.cognitive

import com.example.data.UserFactDao
import com.example.data.UserFactEntity
import com.example.managers.LearningManager
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * المعجم الشخصي للمستخدم (ذاكرة أمد طويل + فهم اللهجات + خريطة علاقات):
 *
 *  - مرادفات الأفعال: «دق» ← «اتصل» — يتعلم لهجتك بالكامل بتعليمك الصريح.
 *  - كنى الأشخاص:  «اخي» ← «محمد الحداد» — خريطة علاقات عائلية/اجتماعية.
 *  - حقائق حرة:    «سيارتي» ← «هيلوكس» — نموذج معرفي عن المستخدم.
 *
 * يُطبَّق المعجم على نص الأمر قبل كل بوابات الفهم، فيستفيد النظام كله.
 */
@Singleton
class UserFactsStore @Inject constructor(
    private val dao: UserFactDao
) {

    /** طبّق مرادفات اللهجة وكنى الأقارب على الأمر الخام (كلمات كاملة فقط). */
    suspend fun applyLexicon(rawInput: String): String {
        var text = rawInput
        // الكنى أولاً: «اتصل باخي» ← «اتصل بمحمد الحداد» قبل محلل الاستراتيجيات.
        for (alias in dao.allOfType(TYPE_ALIAS)) {
            text = replaceWholeWord(text, normalizeKey(alias.originalKey), normalizeKey(alias.key), alias.value)
        }
        // ثم المرادفات: الأطول أولاً لتجنب بلع مقطع من مرادف مركّب.
        for (syn in dao.allOfType(TYPE_SYNONYM).sortedByDescending { it.key.length }) {
            text = replaceWholeWord(text, normalizeKey(syn.originalKey), normalizeKey(syn.key), syn.value)
        }
        return text.replace(Regex("\\s+"), " ").trim()
    }

    private fun replaceWholeWord(
        text: String,
        normOriginal: String,
        normKey: String,
        replacement: String
    ): String {
        var result = replaceWhole(text, normOriginal, replacement)
        if (normKey != normOriginal) result = replaceWhole(result, normKey, replacement)
        return result
    }

    private fun replaceWhole(text: String, needle: String, replacement: String): String {
        if (needle.isBlank()) return text
        val pattern = "(?<![\\p{L}])" + Regex.escape(needle) + "(?![\\p{L}])"
        return try {
            text.replace(Regex(pattern, RegexOption.IGNORE_CASE), replacement)
        } catch (e: Exception) {
            text
        }
    }

    suspend fun teachSynonym(spoken: String, meaning: String) =
        teach(TYPE_SYNONYM, spoken, meaning)

    suspend fun teachPersonAlias(spokenAlias: String, realName: String) =
        teach(TYPE_ALIAS, spokenAlias, realName)

    suspend fun teachFreeFact(spokenKey: String, value: String) =
        teach(TYPE_FACT, spokenKey, value)

    private suspend fun teach(type: String, spokenKey: String, value: String) {
        val key = normalizeKey(spokenKey)
        if (key.isEmpty() || value.isBlank()) return
        val now = System.currentTimeMillis()
        val existing = dao.find(key, type)
        dao.upsert(
            UserFactEntity(
                key = key,
                originalKey = spokenKey.trim(),
                value = value.trim(),
                type = type,
                useCount = (existing?.useCount ?: 0) + 1,
                createdAt = existing?.createdAt ?: now,
                lastUsed = now
            )
        )
    }

    fun observeCount(): Flow<Int> = dao.observeCount()

    suspend fun clearAll() = dao.clearAll()

    /** نفس تطبيع محرك التعلم: همزات موحدة، بلا تشكيل، بلا «ال» تعريف. */
    fun normalizeKey(text: String): String = LearningManager.normalize(text)

    companion object {
        const val TYPE_SYNONYM = "verb_synonym"
        const val TYPE_ALIAS = "person_alias"
        const val TYPE_FACT = "fact"
    }
}
