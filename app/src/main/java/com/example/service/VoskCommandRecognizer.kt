package com.example.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream

/**
 * Offline Arabic command recognizer (Vosk) — the second half of the fully
 * offline voice pipeline.
 *
 * Replaces android.speech.SpeechRecognizer (Google Speech Services, which
 * requires internet) so that BOTH stages run on-device with no internet
 * and no API keys at all:
 *
 *   1) wake word  → [SpeechListenerService]  (Vosk, English, offline)
 *   2) command    → [VoskCommandRecognizer]  (Vosk, Arabic, offline)  ← this class
 *
 * The Arabic model (~158MB, bundled in assets) is extracted to internal
 * storage once and pre-loaded in the background at construction time.
 */
class VoskCommandRecognizer(private val context: Context) {

    /** Callbacks mirror the old android.speech.RecognitionListener semantics we need. */
    interface Listener {
        /** نتيجة جزئية حيّة أثناء الكلام (اختياري — لعرض النص لحظياً). */
        fun onPartial(text: String) {}

        /** تعرّف نهائي على عبارة غير فارغة. */
        fun onResult(text: String)

        /** انتهت مهلة الالتقاط دون سماع شيء — مكافئ ERROR_NO_MATCH / SPEECH_TIMEOUT. */
        fun onNoMatch()

        /** خطأ في المحرك أو المايك أو أثناء تجهيز النموذج. */
        fun onError(message: String?)
    }

    companion object {
        private const val MODEL_ASSETS_FOLDER = "vosk-model-ar-small"
        private const val SAMPLE_RATE = 16000.0f

        /** مهلة الالتقاط القصوى منذ فتح المايك قبل إعلان الصمت. */
        private const val CAPTURE_HARD_TIMEOUT_MS = 15_000L

        /** بعد آخر كلام جزئي نمدّد المهلة — المستخدم قد يتأنّى منتصف الجملة. */
        private const val CAPTURE_AFTER_PARTIAL_MS = 6_000L

        /** محاولات إعادة فتح المايك عندما لم يُغلق مسجّل الجلسة السابقة بعد. */
        private const val MAX_START_ATTEMPTS = 3
    }

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speechService: SpeechService? = null

    @Volatile
    var modelReady = false
        private set

    @Volatile private var listening = false
    @Volatile private var generation = 0

    private var listener: Listener? = null
    private var lastPartial: String? = null

    private val silenceWatchdog = Runnable { onSilenceTimeout() }

    init {
        prepareModelAsync()
    }

    // ------------------------------------------------------------------
    // Model preparation (assets -> internal storage, once)
    // ------------------------------------------------------------------

    private fun prepareModelAsync() {
        scope.launch {
            try {
                val modelDir = File(context.filesDir, MODEL_ASSETS_FOLDER)
                val marker = File(modelDir, ".extracted-ok")
                if (!marker.exists()) {
                    Timber.d("Extracting Arabic Vosk model to internal storage...")
                    modelDir.deleteRecursively()
                    modelDir.mkdirs()
                    copyAssetsRecursively(MODEL_ASSETS_FOLDER, modelDir)
                    marker.writeText("1")
                }
                model = Model(modelDir.absolutePath)
                modelReady = true
                Timber.d("Arabic Vosk command model ready (%s)", MODEL_ASSETS_FOLDER)
            } catch (e: Exception) {
                Timber.e(e, "Failed to prepare Arabic Vosk model")
            }
        }
    }

    /** Recursively copies an assets folder to internal storage. */
    private fun copyAssetsRecursively(assetPath: String, targetDir: File) {
        val children = context.assets.list(assetPath) ?: return
        for (child in children) {
            val childAssetPath = "$assetPath/$child"
            val childFile = File(targetDir, child)
            val grandchildren = context.assets.list(childAssetPath)
            if (grandchildren.isNullOrEmpty()) {
                context.assets.open(childAssetPath).use { input ->
                    FileOutputStream(childFile).buffered().use { input.copyTo(it) }
                }
            } else {
                childFile.mkdirs()
                copyAssetsRecursively(childAssetPath, childFile)
            }
        }
    }

    // ------------------------------------------------------------------
    // Public capture API
    // ------------------------------------------------------------------

    /** بدء التقاط أمر واحد. آمن للاستدعاء مراراً — يتجاهل الطلب إن كان يسمع أصلاً. */
    fun startListening(l: Listener) {
        val startGeneration = generation
        mainHandler.post { startOnMain(l, attempt = 0, startGeneration = startGeneration) }
    }

    /** إيقاف لطيف (المستخدم ضغط زر الإيقاف بنفسه). */
    fun stop() = teardown()

    /** إلغاء فوري دون أي ردود لاحقة (نهاية جلسة محادثة أو خروج). */
    fun cancel() = teardown()

    /** تحرير كل الموارد — يُستدعى من onCleared. */
    fun shutdown() {
        teardown()
        modelReady = false
        job.cancel()
        mainHandler.post {
            try {
                speechService?.shutdown()
            } catch (e: Exception) {
                Timber.w(e, "Arabic recognizer shutdown")
            }
            speechService = null
            recognizer?.close()
            recognizer = null
            model?.close()
            model = null
        }
    }

    // ------------------------------------------------------------------
    // Internals (always on the main thread)
    // ------------------------------------------------------------------

    private fun startOnMain(l: Listener, attempt: Int, startGeneration: Int) {
        if (listening) return
        if (generation != startGeneration) return // أُلغي أثناء انتظار إعادة المحاولة
        val currentModel = model
        if (!modelReady || currentModel == null) {
            l.onError("النموذج العربي ما زال قيد التجهيز، حاول بعد لحظات")
            return
        }
        try {
            if (speechService == null) {
                recognizer = Recognizer(currentModel, SAMPLE_RATE)
                speechService = SpeechService(recognizer, SAMPLE_RATE)
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to create Arabic recognizer")
            l.onError("تعذّر تجهيز المتعرف العربي")
            return
        }

        listener = l
        lastPartial = null
        val started = try {
            speechService?.startListening(recognitionListener) == true
        } catch (e: Exception) {
            Timber.e(e, "SpeechService.startListening threw")
            false
        }

        if (started) {
            listening = true
            armSilenceWatchdog(CAPTURE_HARD_TIMEOUT_MS)
        } else {
            listener = null
            if (attempt < MAX_START_ATTEMPTS) {
                // مسجّل الجلسة السابقة قد لا يكون أُغلق بعد — انتظر قليلاً وأعد.
                try {
                    speechService?.stop()
                } catch (e: Exception) {
                    Timber.w(e, "stop before retry")
                }
                mainHandler.postDelayed({
                    startOnMain(l, attempt + 1, startGeneration)
                }, 250)
            } else {
                Timber.e("Vosk SpeechService refused to start after retries")
                l.onError("تعذّر تشغيل المايك")
            }
        }
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) {
            val partial = extractField(hypothesis, "partial")?.trim()
            if (!partial.isNullOrEmpty()) {
                lastPartial = partial
                listener?.onPartial(partial)
                // المستخدم يتحدث — مدّد المهلة حتى لا نقطع عليه.
                armSilenceWatchdog(CAPTURE_AFTER_PARTIAL_MS)
            }
        }

        override fun onResult(hypothesis: String?) {
            val text = extractField(hypothesis, "text")?.trim()
            if (!text.isNullOrEmpty()) finishWith(text)
            // الفقرة الفارغة = ضجيج/صمت — نترك العدّاد يحسم.
        }

        override fun onFinalResult(hypothesis: String?) {
            if (!listening) return
            val text = extractField(hypothesis, "text")?.trim()
            if (!text.isNullOrEmpty()) finishWith(text)
        }

        override fun onError(exception: Exception?) {
            Timber.e(exception, "Vosk Arabic recognition error")
            notifyError(exception?.message)
        }

        override fun onTimeout() {
            // Vosk SpeechService لا يُنهي نفسه — عدّاد الصمت يتكفّل بذلك.
        }
    }

    private fun finishWith(text: String) {
        if (!listening) return
        val l = listener
        teardown()
        l?.onResult(text)
    }

    private fun onSilenceTimeout() {
        if (!listening) return
        val candidate = lastPartial?.trim().orEmpty()
        val l = listener
        teardown()
        if (candidate.isNotEmpty()) {
            // كان هناك كلام جزئي لكن Vosk لم يُصدر نتيجة نهائية — اعتمده.
            l?.onResult(candidate)
        } else {
            l?.onNoMatch()
        }
    }

    private fun notifyError(message: String?) {
        val l = listener
        teardown()
        l?.onError(message)
    }

    private fun teardown() {
        generation++
        listening = false
        listener = null
        mainHandler.removeCallbacks(silenceWatchdog)
        try {
            speechService?.stop()
        } catch (e: Exception) {
            Timber.w(e, "teardown stop")
        }
    }

    private fun armSilenceWatchdog(delayMs: Long) {
        mainHandler.removeCallbacks(silenceWatchdog)
        mainHandler.postDelayed(silenceWatchdog, delayMs)
    }

    /**
     * استخراج قيمة حقل نصي من مخرجات JSON البسيطة لـ Vosk بدون مكتبة تحليل —
     * يعالج علامات الاقتباس و\\uXXXX عند الحاجة.
     */
    private fun extractField(hypothesis: String?, key: String): String? {
        if (hypothesis == null) return null
        val marker = "\"$key\""
        val keyIndex = hypothesis.indexOf(marker)
        if (keyIndex < 0) return null
        val colonIndex = hypothesis.indexOf(':', keyIndex + marker.length)
        if (colonIndex < 0) return null
        val quoteStart = hypothesis.indexOf('"', colonIndex + 1)
        if (quoteStart < 0) return null
        val sb = StringBuilder()
        var i = quoteStart + 1
        while (i < hypothesis.length) {
            val c = hypothesis[i]
            if (c == '\\' && i + 1 < hypothesis.length) {
                when (hypothesis[i + 1]) {
                    'n' -> { sb.append(' '); i += 2 }
                    '"' -> { sb.append('"'); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    'u' -> {
                        var decoded = false
                        if (i + 5 < hypothesis.length) {
                            try {
                                sb.append(hypothesis.substring(i + 2, i + 6).toInt(16).toChar())
                                i += 6
                                decoded = true
                            } catch (e: NumberFormatException) {
                                // نص غير صالح — نُبقي الحرف كما هو
                            }
                        }
                        if (!decoded) { sb.append('u'); i += 2 }
                    }
                    else -> { sb.append(hypothesis[i + 1]); i += 2 }
                }
            } else if (c == '"') {
                return sb.toString()
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}
