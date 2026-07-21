package com.example.managers

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import android.Manifest
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shared helper that resolves a spoken contact name (Arabic or Latin) to a phone number.
 * Two passes: a fast LIKE query, then a normalized fuzzy scan that tolerates
 * hamza/ya/taa-marbuta variants (أحمد/احمد، فاطمة/فاطمه، علي/على).
 */
/** جهة اتصال مطابقة: الاسم المعروض ورقمه. */
data class ContactMatch(val displayName: String, val number: String)

@Singleton
class ContactResolver @Inject constructor(
    @ApplicationContext private val context: Context
) {

    fun hasContactsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** Returns the first matching phone number for [name], or null. */
    @SuppressLint("Range")
    fun resolveNumber(name: String): String? {
        if (!hasContactsPermission()) return null
        val cleaned = name.trim()
        if (cleaned.isEmpty()) return null

        // Pass 1: simple LIKE match (fast path)
        queryByLike(cleaned)?.let { return it }

        // Pass 2: normalized comparison (handles hamza/taa-marbuta variants)
        return queryNormalized(normalizeArabic(cleaned))
    }

    @SuppressLint("Range")
    private fun queryByLike(name: String): String? {
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER)
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        return context.contentResolver.query(
            uri, projection, selection, arrayOf("%$name%"), null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER))
            } else null
        }
    }

    @SuppressLint("Range")
    private fun queryNormalized(normalizedName: String): String? {
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        return context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            var best: String? = null
            var bestExact = false
            while (cursor.moveToNext()) {
                val displayName = cursor.getString(nameIdx) ?: continue
                val normalized = normalizeArabic(displayName)
                when {
                    normalized == normalizedName -> {
                        best = cursor.getString(numIdx); bestExact = true
                    }
                    !bestExact && (normalized.contains(normalizedName) || normalizedName.contains(normalized)) -> {
                        if (best == null) best = cursor.getString(numIdx)
                    }
                }
                if (bestExact) break
            }
            best
        }
    }

    /**
     * كل المطابقات التقريبية (حتى [limit]) — وقود محرك ترشيح الغموض في
     * الطبقة الإدراكية: «عندك ثلاثة باسم محمد، أي واحد؟».
     * تطابق تام أولاً ثم متسامح، مع إزالة التكرار على الاسم الموحَّد.
     */
    @SuppressLint("Range")
    fun findAll(name: String, limit: Int = 5): List<ContactMatch> {
        if (!hasContactsPermission()) return emptyList()
        val needle = normalizeArabic(name.trim())
        if (needle.isEmpty()) return emptyList()
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val exact = linkedMapOf<String, ContactMatch>()
        val loose = linkedMapOf<String, ContactMatch>()
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext()) {
                val display = cursor.getString(nameIdx)?.trim() ?: continue
                val number = cursor.getString(numIdx)?.trim() ?: continue
                val norm = normalizeArabic(display)
                val match = ContactMatch(display, number)
                when {
                    norm == needle -> exact.putIfAbsent(norm, match)
                    norm.contains(needle) || needle.contains(norm) || tokenMatch(norm, needle) ->
                        if (!exact.containsKey(norm)) loose.putIfAbsent(norm, match)
                }
                if (exact.size + loose.size >= limit * 2) break
            }
        }
        return (exact.values + loose.values).take(limit)
    }

    /** مطابقة على مستوى المقطع: «محمد» تلتقط «محمد الحداد» ولا تلتقط «محمود». */
    private fun tokenMatch(contactNorm: String, needle: String): Boolean {
        val tokens = contactNorm.split(" ").filter { it.isNotBlank() }
        return tokens.any { it.startsWith(needle) || (it.length >= 3 && needle.startsWith(it)) }
    }

    companion object {
        /** Normalize Arabic text for tolerant matching. */
        fun normalizeArabic(input: String): String {
            return input
                .lowercase()
                .replace(Regex("[ً-ْٔۡـ]"), "") // diacritics & tatweel
                .replace('أ', 'ا')
                .replace('إ', 'ا')
                .replace('آ', 'ا')
                .replace('ة', 'ه')
                .replace('ى', 'ي')
                .trim()
        }
    }
}
