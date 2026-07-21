package com.example.processor

import com.example.files.FilePlaybackManager
import com.example.managers.MusicManager
import javax.inject.Inject

/**
 * أوامر التحكم بالتشغيل — «أوقف»، «واصل»، «التالي»، «السابق»، «كرر»، «عشوائي».
 *
 * سياسة التوجيه (مهمة لتعايش المشغل الداخلي مع مشغلات المستخدم الخارجية):
 *  1) إن كانت هناك جلسة تشغيل داخلية حية (خلال آخر 30 دقيقة) ← الأمر ينفذ
 *     داخلياً بدقة كاملة (تكرار/عشوائي/موضع استئناف).
 *  2) وإلا نمرر «أوقف/التالي/السابق» كمفاتيح وسائط لأي مشغل خارجي يملك
 *     جلسة الوسائط (يدعم المستخدم الذي يفضل مشغله المعتاد).
 *  3) «واصل/كرر/عشوائي» بلا جلسة داخلية ← نخبر المستخدم بلطف بدل فشل صامت.
 *
 * موضعها: قبل MusicStrategy مباشرة (كلمات التحكم يجب ألا تُفسَّر كطلب تشغيل)،
 * وبعد YouTubeStrategy حتى تبقى «شغل فيديو يوتيوب» في مسارها.
 */
class PlaybackControlStrategy @Inject constructor(
    private val playback: FilePlaybackManager,
    private val musicManager: MusicManager
) : CommandStrategy {

    override fun canHandle(command: String): Boolean {
        if (EXTERNAL_WORDS.any { command.contains(it) }) return true
        if (RESUME_WORDS.any { command.contains(it) }) return true
        if (REPEAT_WORDS.any { command.contains(it) }) return true
        if (SHUFFLE_WORDS.any { command.contains(it) }) return true
        if (DESCRIBE_WORDS.any { command.contains(it) }) return true
        // «أوقف / التالي / السابق» + كلمة موسيقية ← أمر تشغيل واضح
        val hasMediaContext = MEDIA_HINTS.any { command.contains(it) } || playback.hasActiveSession()
        if (!hasMediaContext) return false
        return STOP_WORDS.any { command.contains(it) } ||
                NEXT_WORDS.any { command.contains(it) } ||
                PREV_WORDS.any { command.contains(it) }
    }

    override suspend fun execute(command: String, isScheduled: Boolean): CommandResult {

        // «افتحها بالمشغل الخارجي»
        if (EXTERNAL_WORDS.any { command.contains(it) }) {
            return if (playback.openCurrentExternally()) {
                CommandResult(true, "فتحت المقطع في مشغلك الخارجي")
            } else {
                CommandResult(false, "لا يوجد مقطع جارٍ لأرسله — شغّل أغنية أولاً")
            }
        }

        // «ماذا يشتغل الآن؟»
        if (DESCRIBE_WORDS.any { command.contains(it) }) {
            return CommandResult(true, playback.describeCurrent())
        }

        // «كرر / التكرار»
        if (REPEAT_WORDS.any { command.contains(it) }) {
            val on = playback.toggleRepeat()
            return CommandResult(true, if (on) "فعّلت تكرار المقطع الحالي" else "أطفأت التكرار")
        }

        // «عشوائي / خبط»
        if (SHUFFLE_WORDS.any { command.contains(it) }) {
            val on = playback.toggleShuffle()
            return CommandResult(true, if (on) "فعّلت الخلط العشوائي للقائمة" else "رجعنا للترتيب الطبيعي")
        }

        // «واصل / استأنف / كمّل»
        if (RESUME_WORDS.any { command.contains(it) }) {
            return if (playback.resumePlayback()) {
                val t = playback.current()
                CommandResult(true, "استأنفت التشغيل" + (t?.let { e -> ": ${e.stem.ifBlank { e.name }}" } ?: ""))
            } else {
                CommandResult(false, "لا يوجد تشغيل متوقف لاستئنافه — قل: شغل أغنية")
            }
        }

        // «التالي»
        if (NEXT_WORDS.any { command.contains(it) }) {
            return if (playback.hasActiveSession() && playback.next()) {
                val t = playback.current()
                CommandResult(true, "شغّلت التالي" + (t?.let { e -> ": ${e.stem.ifBlank { e.name }}" } ?: ""))
            } else {
                musicManager.next()
                CommandResult(true, "أرسلت أمر «التالي» للمشغل الخارجي")
            }
        }

        // «السابق»
        if (PREV_WORDS.any { command.contains(it) }) {
            return if (playback.hasActiveSession() && playback.previous()) {
                val t = playback.current()
                CommandResult(true, "رجعت للسابق" + (t?.let { e -> ": ${e.stem.ifBlank { e.name }}" } ?: ""))
            } else {
                musicManager.previous()
                CommandResult(true, "أرسلت أمر «السابق» للمشغل الخارجي")
            }
        }

        // «أوقف / وقف»
        if (STOP_WORDS.any { command.contains(it) }) {
            return if (playback.hasActiveSession() && playback.pausePlayback()) {
                CommandResult(true, "أوقفت الأغنية مؤقتاً — قل «واصل» لتكملها من نفس الموضع")
            } else {
                musicManager.pause()
                CommandResult(true, "أرسلت أمر الإيقاف للمشغل الخارجي")
            }
        }

        return CommandResult(false, "ما فهمت أمر التحكم — قل: واصل، التالي، السابق، أوقف، كرر، عشوائي")
    }

    companion object {
        private val STOP_WORDS = listOf("اوقف", "أوقف", "وقف", "إيقاف", "ايقاف", "سكّر", "سكر", "اقفل", "pause", "stop")
        private val NEXT_WORDS = listOf("التالي", "التالية", "التاليه", "اللي بعده", "اللي بعدها", "بعده", "تخطي", "تخطى", "next", "skip")
        private val PREV_WORDS = listOf("السابق", "السابقة", "السابقه", "اللي قبله", "اللي قبلها", "قبله", "ارجع للقبل", "previous", "back")
        private val RESUME_WORDS = listOf("واصل", "استأنف", "استانف", "كمل", "كمّل", "اكمل", "تابع", "رجع التشغيل", "resume", "continue")
        private val REPEAT_WORDS = listOf("كرر", "تكرار", "كررها", "اعيدها", "أعيدها", "ريبيت", "repeat")
        private val SHUFFLE_WORDS = listOf("عشوائي", "عشوائية", "خلط", "اختلط", "خبط", "شفل", "shuffle")
        private val EXTERNAL_WORDS = listOf("المشغل الخارجي", "مشغل خارجي", "بالمشغل", "في مشغل الموسيقى", "بمشغل الموسيقى")
        private val DESCRIBE_WORDS = listOf("ماذا يشتغل", "ماذا يشغل", "ايش يشتغل", "إيش يشتغل", "وش يشتغل", "شنو يشتغل", "ماذا تسمع", "اي اغنية تشتغل")
        private val MEDIA_HINTS = listOf("موسيقى", "اغنية", "أغنية", "اغاني", "أغاني", "الاغنية", "الأغنية", "المقطع", "الصوت", "song", "music")
    }
}
