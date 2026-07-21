package com.example.managers

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the device call log so commands like «اتصل بآخر رقم اتصل بي»،
 * «رد على المكالمة الفائتة»، «اتصل بقبل الأخير» can be resolved.
 */
@Singleton
class RecentCallsManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    data class RecentCall(val number: String, val name: String, val type: Int)

    fun hasCallLogPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) ==
                PackageManager.PERMISSION_GRANTED

    /**
     * The [index]-th most recent call matching [types]
     * (0 = latest; null/empty types = any direction).
     */
    @SuppressLint("MissingPermission") // guarded by hasCallLogPermission()
    fun getRecentCall(types: Set<Int>?, index: Int): RecentCall? {
        if (!hasCallLogPermission()) return null

        val projection = arrayOf(
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.TYPE
        )
        val selection: String?
        val args: Array<String>?
        if (types.isNullOrEmpty()) {
            selection = null
            args = null
        } else {
            val placeholders = types.joinToString(",") { "?" }
            selection = "${CallLog.Calls.TYPE} IN ($placeholders)"
            args = types.map { it.toString() }.toTypedArray()
        }

        return try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI, projection, selection, args,
                "${CallLog.Calls.DATE} DESC"
            )?.use { cursor ->
                val numIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                val nameIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
                val typeIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)
                var skipped = 0
                while (cursor.moveToNext()) {
                    if (skipped < index) {
                        skipped++
                        continue
                    }
                    val number = cursor.getString(numIdx) ?: continue
                    val name = cursor.getString(nameIdx)?.takeIf { it.isNotBlank() } ?: number
                    return RecentCall(number, name, cursor.getInt(typeIdx))
                }
                null
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to read call log")
            null
        }
    }
}
