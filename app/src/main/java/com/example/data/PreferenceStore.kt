package com.example.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * تفضيلات المحادثة المخزنة محلياً 100% (SharedPreferences):
 *
 *  - آخر مستوى صوت اختاره المستخدم لكل فترة من اليوم (صباح/ظهر/مساء/ليل)
 *    لتغذية الأسئلة الاستباقية: «هل تريد رفعه إلى ٧٠٪ مثل آخر مرة؟».
 *  - عدّاد التصحيحات الفورية («ليس هذا») كي يعرف المستخدم أن ملاحظاته مسجلة.
 */
@Singleton
class PreferenceStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences("conversation_prefs", Context.MODE_PRIVATE)

    // ---------------- تفضيل الصوت لكل فترة من اليوم ----------------

    fun saveVolumePreference(hourBucket: String, percent: Int) {
        prefs.edit()
            .putInt("vol_$hourBucket", percent)
            .putInt("vol_any", percent)
            .apply()
    }

    /** تفضيل هذه الفترة أولاً، ثم آخر تفضيل معروف كخط رجعة. */
    fun volumePreference(hourBucket: String): Int? {
        if (prefs.contains("vol_$hourBucket")) {
            return prefs.getInt("vol_$hourBucket", -1).takeIf { it in 0..100 }
        }
        if (prefs.contains("vol_any")) {
            return prefs.getInt("vol_any", -1).takeIf { it in 0..100 }
        }
        return null
    }

    // ---------------- سجل التصحيحات الفورية ----------------

    fun recordCorrection(original: String, corrected: String) {
        prefs.edit()
            .putInt("corrections_count", correctionsCount() + 1)
            .putString("last_correction", "$original → $corrected")
            .apply()
    }

    fun correctionsCount(): Int = prefs.getInt("corrections_count", 0)
}
