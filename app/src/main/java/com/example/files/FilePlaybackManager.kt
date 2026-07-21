package com.example.files

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import com.example.data.FileIndexDao
import com.example.data.FileIndexEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * مشغّل هاي مانجر الداخلي — تشغيل مباشر للصوتيات داخل التطبيق نفسه،
 * بدون فتح تطبيق خارجي، حتى تبقى أوامر «أوقف/واصل/التالي/السابق/كرر/عشوائي»
 * تحت سيطرة صوتك تماماً وبدون إنترنت.
 *
 * القدرات:
 *  - قائمة تشغيل: كل أمر بحث ينتج قائمة (حتى لو أُذاعت أغنية واحدة، نحفظ
 *    باقي المرشحين حتى يعمل «التالي/السابق» بذكاء).
 *  - استئناف: يحفظ موضع التوقف لكل أغنية (file_index.lastPositionMs)،
 *    ويعيد بناء آخر جلسة (القائمة + المؤشر + الموضع) بعد إغلاق التطبيق.
 *  - أوضاع: تكرار المقطع، خلط عشوائي — أوامر «كرر»/«عشوائي».
 *  - فتح خارجي اختياري: «افتحها بالمشغل الخارجي» يمرر المقطع الحالي
 *    للمشغل الافتراضي عبر ACTION_VIEW.
 *
 * ملاحظة صادقة: المشغل يعيش في عملية التطبيق؛ إن قتل النظام العملية يتوقف
 * الصوت (يُستأنف بكلمة «واصل»). الترقية المستقبلية إلى MediaSessionService
 * موثقة في وثيقة التصميم — متعمد إرجاؤها لتقليل المخاطرة في هذه النسخة.
 */
@Singleton
class FilePlaybackManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: FileIndexDao
) {
    private val prefs = context.getSharedPreferences("file_playback_prefs", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ------------------------------------------------------------------
    // الحالة
    // ------------------------------------------------------------------

    private var player: MediaPlayer? = null
    private var queue: List<FileIndexEntity> = emptyList()
    private var index: Int = 0

    @Volatile private var repeatOne = false
    @Volatile private var shuffle = false
    @Volatile private var pausedAtMs = 0L

    /** آخر نشاط — يحدد هل أوامر التحكم تخصنا أم المشغل الخارجي. */
    @Volatile private var lastActiveAt = 0L

    fun hasActiveSession(): Boolean =
        player != null && (System.currentTimeMillis() - lastActiveAt) < ACTIVE_WINDOW_MS

    fun isPlaying(): Boolean = try { player?.isPlaying == true } catch (e: Exception) { false }

    fun current(): FileIndexEntity? = queue.getOrNull(index)

    fun queueSize(): Int = queue.size

    fun currentIndex(): Int = index

    fun isRepeatOn() = repeatOne
    fun isShuffleOn() = shuffle

    // ------------------------------------------------------------------
    // التشغيل
    // ------------------------------------------------------------------

    /**
     * يشغّل قائمة [items] بدءاً من [startIndex]. معظم المسارات يجب أن تمر
     * بالقائمة الكاملة لا بأغنية وحيدة — ذلك ما يجعل «التالي/السابق» سحريين.
     */
    fun playQueue(items: List<FileIndexEntity>, startIndex: Int): Boolean {
        if (items.isEmpty() || startIndex !in items.indices) return false
        queue = items
        index = startIndex
        persistSession(items, startIndex, 0L)
        return startCurrent(resumeFromSaved = false)
    }

    /** يشغّل ملفاً واحداً (إن كان متضمناً في آخر قائمة نحافظ عليها). */
    fun playSingle(item: FileIndexEntity): Boolean {
        val existing = queue.indexOfFirst { it.uriString == item.uriString }
        return if (existing >= 0) {
            index = existing
            startCurrent(resumeFromSaved = false)
        } else {
            playQueue(listOf(item), 0)
        }
    }

    private fun startCurrent(resumeFromSaved: Boolean): Boolean {
        val track = current() ?: return false
        releasePlayer()
        return try {
            val p = MediaPlayer()
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            p.setDataSource(context, Uri.parse(track.uriString))
            p.setOnPreparedListener { mp ->
                val resumePos = if (resumeFromSaved) track.lastPositionMs else 0L
                if (resumePos > 5_000L && resumePos < track.durationMs - 5_000L) {
                    mp.seekTo(resumePos.toInt())
                }
                mp.start()
                lastActiveAt = System.currentTimeMillis()
            }
            p.setOnCompletionListener { onTrackCompleted() }
            p.setOnErrorListener { _, what, extra ->
                Timber.e("MediaPlayer error what=%d extra=%d", what, extra)
                onTrackCompleted()
                true
            }
            p.prepareAsync()
            player = p
            lastActiveAt = System.currentTimeMillis()
            scope.launch(Dispatchers.IO) { dao.markPlayed(track.uriString, System.currentTimeMillis()) }
            true
        } catch (e: Exception) {
            Timber.e(e, "Cannot play %s", track.name)
            false
        }
    }

    private fun onTrackCompleted() {
        val track = current() ?: return
        lastActiveAt = System.currentTimeMillis()
        scope.launch(Dispatchers.IO) { dao.savePosition(track.uriString, 0L) }
        if (repeatOne) {
            player?.seekTo(0)
            try { player?.start() } catch (e: Exception) { Timber.e(e, "repeat failed") }
            return
        }
        nextInternal()
    }

    // ------------------------------------------------------------------
    // أوامر التحكم الصوتية
    // ------------------------------------------------------------------

    /** «أوقف الأغنية» — إيقاف مؤقت مع حفظ الموضع (يرجع «واصل» إليه). */
    fun pausePlayback(): Boolean {
        val p = player ?: return false
        return try {
            if (p.isPlaying) {
                p.pause()
                pausedAtMs = p.currentPosition.toLong()
                current()?.let { track ->
                    scope.launch(Dispatchers.IO) { dao.savePosition(track.uriString, pausedAtMs) }
                    persistSession(queue, index, pausedAtMs)
                }
                lastActiveAt = System.currentTimeMillis()
                true
            } else false
        } catch (e: Exception) { false }
    }

    /**
     * «واصل / استأنف / أكمل» — أولوية القرار:
     *  1) مشغل حي متوقف مؤقتاً ← استئناف فوري من نفس الموضع.
     *  2) لا شيء حي لكن توجد جلسة محفوظة ← إعادة بناء القائمة + المتغير
     *     والاستئناف من آخر موضع محفوظ (حتى بعد إعادة تشغيل التطبيق).
     */
    suspend fun resumePlayback(): Boolean {
        val p = player
        if (p != null) {
            return try {
                p.start()
                lastActiveAt = System.currentTimeMillis()
                true
            } catch (e: Exception) { false }
        }
        return resumeLastSession()
    }

    /** إعادة بناء آخر جلسة تشغيل من التفضيلات + صفوف الفهرس. */
    private suspend fun resumeLastSession(): Boolean {
        val savedUris = prefs.getString(KEY_URIS, null)?.split("\n")?.filter { it.isNotBlank() } ?: return false
        val savedIndex = prefs.getInt(KEY_INDEX, 0).coerceAtLeast(0)
        val rows = savedUris.mapNotNull { dao.byUri(it) }
        if (rows.isEmpty()) return false
        queue = rows
        index = savedIndex.coerceIn(0, rows.size - 1)
        // نقرأ الموضع من قاعدة البيانات (مصدر الحقيقة بعد kill) لا من التفضيلات.
        val fresh = current() ?: return false
        return startCurrent(resumeFromSaved = fresh.lastPositionMs > 0L)
    }

    /** «التالي / تخطّى / اللي بعده». */
    fun next(): Boolean {
        if (queue.isEmpty()) return false
        savePositionOfCurrent()
        return nextInternal()
    }

    private fun nextInternal(): Boolean {
        if (queue.isEmpty()) return false
        index = when {
            shuffle -> (queue.indices - index).randomOrNull() ?: index
            index + 1 < queue.size -> index + 1
            else -> 0 // نلفّ القائمة بدل توقف مفاجئ
        }
        persistSession(queue, index, 0L)
        return startCurrent(resumeFromSaved = false)
    }

    /** «السابق / ارجع / اللي قبله» — ضغطة ثانية في أول 3 ثوانٍ ترجع فعلاً. */
    fun previous(): Boolean {
        if (queue.isEmpty()) return false
        val posMs = try { player?.currentPosition?.toLong() ?: 0L } catch (e: Exception) { 0L }
        if (posMs > 3_000L) {
            // كسلوك المشغلات التقليدية: «السابق» في بداية المقطع يعيده من أوله.
            try { player?.seekTo(0); lastActiveAt = System.currentTimeMillis(); return true } catch (e: Exception) {}
        }
        index = if (index - 1 >= 0) index - 1 else queue.size - 1
        persistSession(queue, index, 0L)
        return startCurrent(resumeFromSaved = false)
    }

    /** «كرر / وضع التكرار» — يقلب الحالة ويخبرك بصوتها في الاستراتيجية. */
    fun toggleRepeat(): Boolean {
        repeatOne = !repeatOne
        lastActiveAt = System.currentTimeMillis()
        return repeatOne
    }

    /** «عشوائي / خلط» — يقلب الخلط. */
    fun toggleShuffle(): Boolean {
        shuffle = !shuffle
        lastActiveAt = System.currentTimeMillis()
        return shuffle
    }

    /** إيقاف كامل مع تحرير الموارد (ولا نمسح الجلسة المحفوظة — «واصل» ما زال يعمل). */
    fun stopPlayback() {
        savePositionOfCurrent()
        releasePlayer()
        lastActiveAt = System.currentTimeMillis()
    }

    fun describeCurrent(): String {
        val t = current() ?: return "لا يوجد شيء قيد التشغيل"
        val pos = try { player?.currentPosition ?: 0 } catch (e: Exception) { 0 }
        val mm = (pos / 60000)
        val ss = ((pos % 60000) / 1000)
        val repeatTxt = if (repeatOne) "، والتكرار مفعل" else ""
        return "يشغّل الآن ${t.stem.ifBlank { t.name }} ${if (t.artist.isNotBlank()) "لـ${t.artist} " else ""}عند الدقيقة $mm:${String.format("%02d", ss)}$repeatTxt"
    }

    // ------------------------------------------------------------------
    // جسر للمشغل الخارجي («افتحها بالمشغل الخارجي»)
    // ------------------------------------------------------------------

    fun openCurrentExternally(): Boolean {
        val track = current() ?: return false
        return try {
            pausePlayback()
            context.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.parse(track.uriString), track.mime.ifBlank { "audio/*" })
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            true
        } catch (e: Exception) {
            Timber.e(e, "no external player")
            false
        }
    }

    // ------------------------------------------------------------------
    // أدوات داخلية
    // ------------------------------------------------------------------

    private fun savePositionOfCurrent() {
        val p = player ?: return
        val track = current() ?: return
        val pos = try { p.currentPosition.toLong() } catch (e: Exception) { return }
        scope.launch(Dispatchers.IO) { dao.savePosition(track.uriString, pos) }
        persistSession(queue, index, pos)
    }

    private fun releasePlayer() {
        try {
            player?.setOnCompletionListener(null)
            player?.setOnPreparedListener(null)
            player?.setOnErrorListener(null)
            player?.release()
        } catch (e: Exception) { /* released already */ }
        player = null
    }

    private fun persistSession(items: List<FileIndexEntity>, idx: Int, posMs: Long) {
        prefs.edit()
            .putString(KEY_URIS, items.joinToString("\n") { it.uriString })
            .putInt(KEY_INDEX, idx)
            .putLong(KEY_POS, posMs)
            .apply()
    }

    companion object {
        /** نافذة اعتبار جلسة التشغيل «حية» لاستحواذ أوامر التحكم (30 دقيقة). */
        private const val ACTIVE_WINDOW_MS = 30L * 60_000L

        private const val KEY_URIS = "queue_uris"
        private const val KEY_INDEX = "queue_index"
        private const val KEY_POS = "queue_pos"
    }
}
