package com.example.processor

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.example.data.WhatsAppRecentsStore
import com.example.managers.ContactResolver
import com.example.managers.WhatsAppManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * WhatsApp, ready-to-send — no AI:
 *  - «أرسل واتساب لأحمد تقول أنا قادم»
 *  - «افتح واتساب لأمي»
 *  - «أرسل واتساب للرقم 777123456 تقول ...»
 *  - «افتح آخر محادثة واتساب» / «المحادثة الثانية» / «قبل الأخيرة»
 *  - «أرسل للمحادثة المفضلة» (your most active chat)
 *
 * Name-free commands are powered by [WhatsAppRecentsStore], which learns
 * recent senders from incoming WhatsApp notifications.
 * Must run BEFORE SmsStrategy and AppOpenerStrategy.
 */
class WhatsAppStrategy @Inject constructor(
    @ApplicationContext private val context: Context,
    private val whatsAppManager: WhatsAppManager,
    private val contactResolver: ContactResolver,
    private val recentsStore: WhatsAppRecentsStore
) : CommandStrategy {

    internal data class Target(val display: String, val number: String)

    override fun canHandle(command: String): Boolean {
        if (WA_WORDS.none { command.contains(it) }) return false
        if (FAV_WORDS.any { command.contains(it) }) return true
        if (recencyIndex(command) != null) return true
        val (head, _) = splitBody(command)
        if (DIGIT_RUN.find(head)?.value?.filter { it.isDigit() }?.let { it.length >= 6 } == true) return true
        // «افتح واتساب» alone has no target -> falls through to the app opener.
        return extractName(head).isNotBlank()
    }

    override suspend fun execute(command: String, isScheduled: Boolean): CommandResult {
        if (!whatsAppManager.isWhatsAppInstalled()) {
            return CommandResult(false, "واتساب غير مثبت على جهازك")
        }

        val cmd = normalizeDigits(command)
        val (head, body) = splitBody(cmd)
        val digits = DIGIT_RUN.find(head)?.value?.filter { it.isDigit() }

        val target: Target = when {
            // 1) Explicit phone number in the command wins.
            !digits.isNullOrBlank() && digits.length >= 6 -> Target(digits, digits)

            // 2) «المحادثة المفضلة» = most active chat from notifications.
            FAV_WORDS.any { cmd.contains(it) } -> {
                val top = recentsStore.mostFrequent() ?: return noRecentsYet()
                resolveSender(top) ?: return CommandResult(false, "لم أجد رقم «$top» في جهات الاتصال")
            }

            // 3) «آخر محادثة» / «الثانية» / «قبل الأخيرة» from notifications.
            recencyIndex(cmd) != null -> {
                val index = recencyIndex(cmd)!!
                val sender = recentsStore.nth(index) ?: return CommandResult(
                    false,
                    "لم تصلني هذه المحادثة بعد — عند وصول رسالة واتساب جديدة سأتعرف عليها تلقائياً"
                )
                resolveSender(sender) ?: return CommandResult(false, "لم أجد رقم «$sender» في جهات الاتصال")
            }

            // 4) Classic: by contact name.
            else -> {
                val name = extractName(head)
                if (name.isBlank()) {
                    return CommandResult(false, "لمن تريد الإرسال؟ قل: أرسل واتساب لأحمد تقول ...")
                }
                val number = contactResolver.resolveNumber(name)
                    ?: return CommandResult(false, "لم أجد رقم «$name» في جهات الاتصال")
                Target(name, number)
            }
        }

        val ok = whatsAppManager.openChat(target.number, body.ifBlank { null })
        return if (ok) {
            if (body.isBlank()) {
                CommandResult(true, "فتحت محادثة ${target.display} في واتساب")
            } else {
                CommandResult(true, "جهّزت رسالتك إلى ${target.display} في واتساب — اضغط زر الإرسال ✈️")
            }
        } else {
            CommandResult(false, "تعذر فتح واتساب")
        }
    }

    // ------------------------------------------------------------------
    // Parsing helpers
    // ------------------------------------------------------------------

    /** Splits «... تقول <message>» into (head, messageBody). */
    internal fun splitBody(command: String): Pair<String, String> {
        val marker = BODY_MARKERS.firstOrNull { command.contains(it) }
        return if (marker != null) {
            command.substringBefore(marker).trim() to command.substringAfter(marker).trim()
        } else {
            command.trim() to ""
        }
    }

    /** WhatsApp notification titles may be names or raw unsaved numbers. */
    private fun resolveSender(rawTitle: String): Target? {
        val cleaned = rawTitle
            .replace(Regex("[^\\p{L}\\p{N} ]"), "") // strip emoji/symbols
            .replace(Regex("\\s+"), " ")
            .trim()
        if (cleaned.isEmpty()) return null
        val digits = cleaned.filter { it.isDigit() }
        if (digits.length >= 6) return Target(rawTitle, digits)
        return contactResolver.resolveNumber(cleaned)?.let { Target(rawTitle, it) }
    }

    /** 0 = latest chat, 1 = second, 2 = third ... or null when not requested. */
    internal fun recencyIndex(command: String): Int? {
        val mentionsChat = CHAT_WORDS.any { command.contains(it) }
        if (!mentionsChat) return null
        return when {
            SECOND_WORDS.any { command.contains(it) } -> 1
            THIRD_WORDS.any { command.contains(it) } -> 2
            FOURTH_WORDS.any { command.contains(it) } -> 3
            LAST_WORDS.any { command.contains(it) } -> 0
            else -> null
        }
    }

    /** Keeps only the contact name: removes WhatsApp words, verbs and fillers. */
    internal fun extractName(head: String): String {
        // Punctuation («،» after الواتساب) must not glue words together.
        var s = " ${head.trim().replace(Regex("[،,؛;!؟?.]"), " ")} "
        for (phrase in STRIP_PHRASES) s = s.replace(" $phrase ", " ")
        for (w in STOP_WORDS) s = s.replace(" $w ", " ")
        s = s.trim()
        for (t in TARGET_MARKERS) {
            if (s.contains(t)) {
                s = s.substringAfter(t).trim()
                break
            }
        }
        return s.replace(Regex("^(لـ|الى|إلى|على|ل|ال|to|for)\\s*"), "").trim()
    }

    private fun noRecentsYet(): CommandResult {
        return if (isNotificationAccessEnabled()) {
            CommandResult(false, "لم تصلني إشعارات واتساب بعد — عند وصول أي رسالة جديدة سأتعرف على محادثاتك تلقائياً")
        } else {
            try {
                context.startActivity(
                    Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (ignored: Exception) {
            }
            CommandResult(false, "فعّل وصول الإشعارات لـ Hey Manager (فتحت لك الإعدادات) لأتعرف على محادثاتك الأخيرة")
        }
    }

    private fun isNotificationAccessEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver, "enabled_notification_listeners"
        ) ?: return false
        return enabled.contains(context.packageName)
    }

    private fun normalizeDigits(s: String): String {
        val arabicIndic = "٠١٢٣٤٥٦٧٨٩"
        return s.map { c ->
            val i = arabicIndic.indexOf(c)
            if (i >= 0) '0' + i else c
        }.joinToString("")
    }

    companion object {
        private val WA_WORDS = listOf("واتساب", "الواتساب", "واتس", "الواتس", "وتساب", "وتس", "whatsapp")
        private val FAV_WORDS = listOf("مفضلة", "المفضلة", "مفضل", "الأكثر نشاطا", "الاكثر نشاطا", "الأكثر استخدام", "favourite", "favorite")
        private val CHAT_WORDS = listOf("محادثة", "محادثه", "دردشة", "شات", "chat")
        private val LAST_WORDS = listOf("آخر", "اخر", "الأخيرة", "الاخيرة", "الأخير", "الاخير", "last")
        private val SECOND_WORDS = listOf(
            "قبل الاخيرة", "قبل الأخيرة", "قبل الاخير", "قبل الأخير",
            "الثانية", "الثاني", "ثانية", "ثاني", "second"
        )
        private val THIRD_WORDS = listOf("الثالثة", "الثالث", "ثالثة", "ثالث", "third")
        private val FOURTH_WORDS = listOf("الرابعة", "الرابع", "رابعة", "رابع", "fourth")
        private val BODY_MARKERS = listOf("تقول", "قل له", "وقل له", "قول له", "قل لها", "وقل لها", "قول لها", "اكتب له", "اكتب لها", "نصها", "مضمونها", "مفادها", "بأن", ":")
        private val TARGET_MARKERS = listOf("الى ", "إلى ", "لل", "على ", "to ")
        private val DIGIT_RUN = Regex("\\+?[0-9][0-9\\s]{5,}")
        // Multi-word phrases to remove before single words («في الواتساب»، «عبر واتساب»).
        private val STRIP_PHRASES = listOf(
            "في الواتساب", "على الواتساب", "عبر الواتساب", "بالواتساب", "عن طريق الواتساب",
            "في واتساب", "على واتساب", "عبر واتساب", "بواسطة الواتساب"
        )
        private val STOP_WORDS = listOf(
            "أرسل", "ارسل", "ابعث", "افتح", "افتحلي", "شغل", "اكتب",
            "واتساب", "الواتساب", "واتس", "الواتس", "وتساب", "وتس", "whatsapp",
            "رسالة", "رساله", "نصية", "دردشة", "محادثة", "محادثه", "شات",
            "send", "open", "chat"
        )
    }
}
