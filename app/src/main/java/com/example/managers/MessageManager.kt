package com.example.managers

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessageManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val contactResolver: ContactResolver
) {

    sealed class SmsResult {
        object Sent : SmsResult()
        object NoPermission : SmsResult()
        object ContactNotFound : SmsResult()
        data class Failed(val reason: String) : SmsResult()
    }

    /** Send an SMS. [target] may be a phone number or a contact name. */
    fun sendSms(target: String, message: String): SmsResult {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return SmsResult.NoPermission
        }

        val isNumber = target.matches(Regex("^[0-9+*#\\s-]{3,}$"))
        val number = if (isNumber) target.filter { it.isDigit() || it == '+' }
                     else contactResolver.resolveNumber(target) ?: return SmsResult.ContactNotFound

        return try {
            @Suppress("DEPRECATION")
            val smsManager: SmsManager = context.getSystemService(SmsManager::class.java)
                ?: return SmsResult.Failed("no_sms_service")
            val parts = smsManager.divideMessage(message)
            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(number, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(number, null, message, null, null)
            }
            Timber.d("SMS sent to %s", number)
            SmsResult.Sent
        } catch (e: Exception) {
            Timber.e(e, "Failed to send SMS")
            SmsResult.Failed(e.message ?: "unknown")
        }
    }
}
