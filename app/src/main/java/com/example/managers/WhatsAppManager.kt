package com.example.managers

import android.content.Context
import android.content.Intent
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Opens WhatsApp chats with a ready-to-send draft using official wa.me
 * deep links. Works for WhatsApp and WhatsApp Business.
 */
@Singleton
class WhatsAppManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    fun isWhatsAppInstalled(): Boolean = installedPackages().isNotEmpty()

    private fun installedPackages(): List<String> {
        val pm = context.packageManager
        return listOf("com.whatsapp", "com.whatsapp.w4b").filter { pkg ->
            try {
                pm.getLaunchIntentForPackage(pkg) != null
            } catch (e: Exception) {
                false
            }
        }
    }

    /**
     * wa.me needs full international format without "+"/"00".
     * Local Yemeni numbers (7XXXXXXXX, 6–9 digits) get the 967 country code.
     */
    fun toInternationalNumber(raw: String): String {
        var digits = raw.filter { it.isDigit() }
        if (digits.startsWith("00")) digits = digits.drop(2)
        if (digits.length in 6..9 && !digits.startsWith("967")) digits = "967$digits"
        return digits
    }

    /** Opens the chat with [phoneRaw]; [draftText] is pre-filled ready to send. */
    fun openChat(phoneRaw: String, draftText: String?): Boolean {
        val phone = toInternationalNumber(phoneRaw)
        if (phone.isEmpty()) return false
        val url = buildString {
            append("https://wa.me/").append(phone)
            if (!draftText.isNullOrBlank()) append("?text=").append(Uri.encode(draftText))
        }
        val packages = installedPackages()
        // 1) Primary: official wa.me deep link (documented by WhatsApp).
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                if (packages.isNotEmpty()) setPackage(packages.first())
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return true
        } catch (e: Exception) {
            Timber.w(e, "wa.me link failed — trying legacy whatsapp:// scheme")
        }
        // 2) Fallback: legacy explicit scheme (works on older WhatsApp builds).
        return try {
            val legacy = buildString {
                append("whatsapp://send?phone=").append(phone)
                if (!draftText.isNullOrBlank()) append("&text=").append(Uri.encode(draftText))
            }
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(legacy)).apply {
                if (packages.isNotEmpty()) setPackage(packages.first())
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to open WhatsApp chat")
            false
        }
    }
}
