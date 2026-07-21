package com.example.processor

import com.example.files.FileIndexer
import com.example.files.FilePlaybackManager
import com.example.files.VoiceFileSearchManager
import com.example.managers.MusicManager
import javax.inject.Inject

/**
 * On-device music control — works offline, no AI needed:
 *  - «شغل أغنية فيروز» / «شغل أغاني عمرو دياب» (by artist)
 *  - «شغل أي أغنية» / «شغل موسيقى» (random)
 *  - «أوقف الموسيقى» / «الأغنية التالية» / «الأغنية السابقة»
 */
class MusicStrategy @Inject constructor(
    private val musicManager: MusicManager,
    private val voiceFiles: VoiceFileSearchManager,
    private val indexer: FileIndexer,
    private val playback: FilePlaybackManager
) : CommandStrategy {

    override fun canHandle(command: String): Boolean {
        val hasMusicWord = MUSIC_WORDS.any { command.contains(it) }
        if (!hasMusicWord) return false
        return PLAY_VERBS.any { command.contains(it) } ||
                CONTROL_WORDS.any { command.contains(it) }
    }

    override suspend fun execute(command: String, isScheduled: Boolean): CommandResult {
        // Playback controls don't need the storage permission.
        when {
            STOP_WORDS.any { command.contains(it) } -> {
                musicManager.pause()
                return CommandResult(true, "أوقفت الموسيقى مؤقتاً")
            }
            NEXT_WORDS.any { command.contains(it) } -> {
                musicManager.next()
                return CommandResult(true, "انتقلت للمقطع التالي")
            }
            PREV_WORDS.any { command.contains(it) } -> {
                musicManager.previous()
                return CommandResult(true, "رجعت للمقطع السابق")
            }
        }

        if (!musicManager.hasAudioPermission()) {
            return CommandResult(
                false,
                "أحتاج صلاحية الوصول للموسيقى — افتح التطبيق ووافق على إذن الملفات الصوتية"
            )
        }

        val query = extractQuery(command)
        val wantsRandom = query.isBlank() || RANDOM_WORDS.any { command.contains(" $it ") || command.endsWith(" $it") }

        // أولاً: مشغل هاي مانجر الداخلي عبر الفهرس المحلي — تحكم صوتي كامل
        // (التالي/السابق/كرر/عشوائي/واصل) وقائمة تشغيل حقيقية بدل مقطع وحيد.
        run {
            indexer.ensureIndexed()
            val queue = if (wantsRandom) voiceFiles.randomAudioQueue(40) else voiceFiles.searchAudio(query, 25)
            if (queue.isNotEmpty() && playback.playQueue(queue, 0)) {
                val first = queue.first()
                val artist = if (first.artist.isNotBlank()) " — ${first.artist}" else ""
                val queueNote = if (queue.size > 1) " — القائمة فيها ${queue.size}، قل: التالي أو السابق" else ""
                return CommandResult(true, "شغّلت لك: ${first.stem.ifBlank { first.name }}$artist$queueNote")
            }
        }

        // الاحتياط: البحث الحي القديم عبر MediaStore ثم تسليم المقطع خارجياً.
        val track = if (wantsRandom) musicManager.randomTrack() else musicManager.findTrack(query)

        if (track == null) {
            return if (wantsRandom) {
                CommandResult(false, "لم أجد أي مقاطع موسيقية محفوظة على جهازك")
            } else {
                CommandResult(false, "لم أجد موسيقى تطابق \"$query\" على جهازك")
            }
        }

        return if (musicManager.playTrack(track)) {
            val artist = if (track.artist.isNotBlank() && !track.artist.contains("unknown", true)) {
                " — ${track.artist}"
            } else ""
            CommandResult(true, "شغّلت لك: ${track.title}$artist")
        } else {
            CommandResult(false, "لا يوجد مشغل موسيقى يستطيع تشغيل المقطع")
        }
    }

    /**
     * Removes command/filler words, keeping only the song/artist name.
     * Words are matched space-delimited so we never mangle names that merely
     * CONTAIN a filler word (e.g. «منير» won't be cut by removing «من»).
     */
    internal fun extractQuery(command: String): String {
        var result = " $command "
        for (phrase in STRIP_WORDS) {
            result = result.replace(" $phrase ", " ")
        }
        return result.replace(Regex("\\s+"), " ").trim()
    }

    companion object {
        private val MUSIC_WORDS = listOf(
            "موسيقى", "اغنية", "أغنية", "اغاني", "أغاني", "اغنيه",
            "انشودة", "أنشودة", "song", "songs", "music"
        )
        private val PLAY_VERBS = listOf("شغل", "شغّل", "شغلي", "تشغيل", "عزف", "اعزف", "play")
        private val STOP_WORDS = listOf("اوقف", "أوقف", "وقف", "اقفل", "سكر", "pause", "stop")
        private val NEXT_WORDS = listOf("التالي", "التالية", "التاليه", "بعدها", "next")
        private val PREV_WORDS = listOf("السابق", "السابقة", "السابقه", "قبلها", "previous")
        private val CONTROL_WORDS = STOP_WORDS + NEXT_WORDS + PREV_WORDS
        private val RANDOM_WORDS = listOf("اي", "أي", "عشوائي", "عشوائية", "random")

        // Filler/command words removed to isolate the song or artist name.
        private val STRIP_WORDS = listOf(
            "شغل لي", "شغلي", "شغّل", "شغل", "تشغيل", "اعزف", "عزف", "play",
            "اغنية", "أغنية", "اغاني", "أغاني", "اغنيه", "انشودة", "أنشودة",
            "موسيقى", "music", "songs", "song",
            "من فضلك", "لو سمحت", "على الجهاز", "الجهاز",
            "باسم", "اسم", "للفنان", "الفنان", "لي", "رجاءً"
        )
    }
}
