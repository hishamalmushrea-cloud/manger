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
