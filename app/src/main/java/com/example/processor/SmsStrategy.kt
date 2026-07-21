package com.example.processor

import com.example.managers.MessageManager
import javax.inject.Inject

/** "أرسل رسالة لأحمد تقول أنا قادم" / "ارسل sms للرقم 771234567 نصها ..." */
class SmsStrategy @Inject constructor(
    private val messageManager: MessageManager
) : CommandStrategy {

    override fun canHandle(command: String): Boolean {
        val hasSend = command.contains("ارسل") || command.contains("أرسل") || command.contains("send")
        val hasMessage = command.contains("رسالة") || command.contains("رساله") ||
                command.contains("sms") || command.contains("نصي") || command.contains("رسالة نصية")
        return hasSend && hasMessage
    }

    override suspend fun execute(command: String, isScheduled: Boolean): CommandResult {
        val (target, body) = parse(command)
        if (target.isBlank()) return CommandResult(false, "لم أفهم لمن أرسل الرسالة")
        if (body.isBlank()) return CommandResult(false, "لم أفهم نص الرسالة. قل مثلاً: أرسل رسالة لأحمد تقول أنا قادم")

        return when (val result = messageManager.sendSms(target, body)) {
            MessageManager.SmsResult.Sent -> CommandResult(true, "تم إرسال الرسالة إلى $target")
            MessageManager.SmsResult.NoPermission ->
                CommandResult(false, "أحتاج صلاحية إرسال الرسائل. فعّلها عند الطلب")
            MessageManager.SmsResult.ContactNotFound ->
                CommandResult(false, "لم أجد رقم $target في جهات الاتصال")
            is MessageManager.SmsResult.Failed ->
                CommandResult(false, "فشل إرسال الرسالة")
        }
    }

    internal data class ParsedSms(val target: String, val body: String)

    private fun parse(command: String): ParsedSms {
        // Patterns: <send verbs> <message words> (لـ|ل|الى|إلى|على|for|to) <target> (تقول|نصها|مضمونها|مفادها|بأن|:) <body>
        val marker = BODY_MARKERS.firstOrNull { command.contains(it) }
        val head: String
        val body: String
        if (marker != null) {
            head = command.substringBefore(marker).trim()
            body = command.substringAfter(marker).trim()
        } else {
            head = command.trim()
            body = ""
        }

        var target = ""
        for (t in TARGET_MARKERS) {
            if (head.contains(t)) {
                target = head.substringAfter(t).trim()
                break
            }
        }
        if (target.isBlank()) {
            // Fallback: strip known leading verbs/nouns and take the tail as target
            target = SEND_WORDS.fold(head) { acc, w -> acc.replace(w, " ") }
                .replace(Regex("\\s+"), " ").trim()
        }
        // Strip leading connectors that may be glued to the name ("لأحمد" -> "أحمد")
        target = target.replace(Regex("^(لـ|الى|إلى|على|ل|ال|to|for)\\s*"), "").trim()
        return ParsedSms(target, body)
    }

    companion object {
        private val TARGET_MARKERS = listOf("لـ", "الى ", "إلى ", "ل ", "على ", "to ", "for ")
        private val BODY_MARKERS = listOf("قل له", "وقل له", "قول له", "قل لها", "وقل لها", "قول لها", "نصها", "مضمونها", "مفادها", "تقول", "بأن", "اكتب", "واكتب", ": ")
        private val SEND_WORDS = listOf("أرسل", "ارسل", "send", "رسالة", "رساله", "نصية", "sms")
    }
}
