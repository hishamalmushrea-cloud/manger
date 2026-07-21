package com.example.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
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
 * Offline wake-word listener powered by Vosk (no internet, no API key).
 *
 * Flow: listens for [WAKE_WORD] with a grammar-restricted recognizer
 * (only the wake phrase + [unk] can be produced — cheap and precise).
 * On detection it releases the microphone so the Google SpeechRecognizer
 * can capture the actual command, and broadcasts [ACTION_WAKE_WORD].
 * When the command capture ends, the app broadcasts [ACTION_RESUME_WAKE_WORD]
 * and this service resumes listening.
 */
class SpeechListenerService : Service(), RecognitionListener {

    companion object {
        /** Say this phrase to wake the assistant. */
        const val WAKE_WORD = "hey manager"

        const val ACTION_WAKE_WORD = "com.example.WAKE_WORD_DETECTED"
        const val ACTION_PAUSE_WAKE_WORD = "com.example.PAUSE_WAKE_WORD"
        const val ACTION_RESUME_WAKE_WORD = "com.example.RESUME_WAKE_WORD"

        private const val NOTIFICATION_ID = 101
        private const val CHANNEL_ID = "speech_listener_channel"
        private const val MODEL_ASSETS_FOLDER = "vosk-model-en-base"
        private const val SAMPLE_RATE = 16000.0f
    }

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speechService: SpeechService? = null

    @Volatile private var paused = false
    @Volatile private var listenerRunning = false

    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_PAUSE_WAKE_WORD -> pauseEngine()
                ACTION_RESUME_WAKE_WORD -> resumeEngine()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification("جاري تجهيز كلمة التنبيه...")

        ContextCompat.registerReceiver(
            this,
            controlReceiver,
            IntentFilter().apply {
                addAction(ACTION_PAUSE_WAKE_WORD)
                addAction(ACTION_RESUME_WAKE_WORD)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        initializeVosk()
    }

    // ------------------------------------------------------------------
    // Model loading (assets -> app storage, once)
    // ------------------------------------------------------------------

    private fun initializeVosk() {
        scope.launch {
            try {
                val modelDir = File(filesDir, MODEL_ASSETS_FOLDER)
                val marker = File(modelDir, ".extracted-ok")
                if (!marker.exists()) {
                    Timber.d("Extracting Vosk model to internal storage...")
                    updateNotification("جاري تجهيز نموذج التعرف الصوتي لأول مرة...")
                    modelDir.deleteRecursively()
                    modelDir.mkdirs()
                    copyAssetsRecursively(MODEL_ASSETS_FOLDER, modelDir)
                    marker.writeText("1")
                }

                model = Model(modelDir.absolutePath)
                startEngine()
                Timber.d("Vosk wake-word engine ready (wake word: '%s')", WAKE_WORD)
            } catch (e: Exception) {
                Timber.e(e, "Failed to initialize Vosk engine")
                updateNotification("تعذر تجهيز كلمة التنبيه — سيستمر التطبيق بالزر")
            }
        }
    }

    /** Recursively copies an assets folder to internal storage. */
    private fun copyAssetsRecursively(assetPath: String, targetDir: File) {
        val children = assets.list(assetPath) ?: return
        for (child in children) {
            val childAssetPath = "$assetPath/$child"
            val childFile = File(targetDir, child)
            val grandchildren = assets.list(childAssetPath)
            if (grandchildren.isNullOrEmpty()) {
                // File
                assets.open(childAssetPath).use { input ->
                    FileOutputStream(childFile).buffered().use { input.copyTo(it) }
                }
            } else {
                childFile.mkdirs()
                copyAssetsRecursively(childAssetPath, childFile)
            }
        }
    }

    // ------------------------------------------------------------------
    // Engine lifecycle
    // ------------------------------------------------------------------

    private fun startEngine() {
        val currentModel = model ?: return
        try {
            // Grammar restriction: the recognizer can ONLY output the wake
            // phrase or [unk] — minimal CPU, minimal false positives.
            val grammar = "[\"$WAKE_WORD\", \"[unk]\"]"
            recognizer = Recognizer(currentModel, SAMPLE_RATE, grammar)
            speechService = SpeechService(recognizer, SAMPLE_RATE)
            speechService?.startListening(this)
            listenerRunning = true
            paused = false
            updateNotification("قل «$WAKE_WORD» لتشغيل المساعد")
            Timber.d("Vosk listening started (grammar-restricted)")
        } catch (e: Exception) {
            Timber.e(e, "Failed to start Vosk speech service")
        }
    }

    private fun pauseEngine() {
        paused = true
        if (listenerRunning) {
            Timber.d("Pausing wake-word engine (mic handed over)")
            speechService?.stop()
            listenerRunning = false
        }
    }

    private fun resumeEngine() {
        if (!paused) return
        paused = false
        // Small delay lets the other recognizer release the mic first.
        mainHandler.postDelayed({
            if (paused) return@postDelayed
            try {
                val service = speechService
                if (service != null) {
                    service.startListening(this)
                    listenerRunning = true
                    updateNotification("قل «$WAKE_WORD» لتشغيل المساعد")
                    Timber.d("Wake-word engine resumed")
                } else if (model != null) {
                    startEngine()
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to resume wake-word engine")
            }
        }, 600)
    }

    // ------------------------------------------------------------------
    // RecognitionListener
    // ------------------------------------------------------------------

    override fun onPartialResult(hypothesis: String?) {
        checkForWakeWord(hypothesis)
    }

    override fun onResult(hypothesis: String?) {
        checkForWakeWord(hypothesis)
    }

    override fun onFinalResult(hypothesis: String?) {
        checkForWakeWord(hypothesis)
    }

    override fun onError(exception: Exception?) {
        Timber.e(exception, "Vosk recognition error")
    }

    override fun onTimeout() {
        // Nothing to do — the engine keeps listening.
    }

    private fun checkForWakeWord(hypothesis: String?) {
        // Hypothesis is JSON like {"partial":"hey manager"} — a plain
        // contains-check is sufficient because grammar results are restricted.
        if (paused || hypothesis == null) return
        if (hypothesis.contains(WAKE_WORD)) {
            Timber.d("Wake word detected!")
            // Release the mic BEFORE the command recognizer starts.
            pauseEngine()
            bringAssistantToFront()
            sendBroadcast(Intent(ACTION_WAKE_WORD).setPackage(packageName))
        }
    }

    /**
     * Android 10+ blocks activities started from the background UNLESS the
     * user granted "Display over other apps" (SYSTEM_ALERT_WINDOW). With that
     * grant we surface the assistant UI — and every command that opens
     * another app keeps working even while Hey Manager is in the background.
     */
    private fun bringAssistantToFront() {
        if (!Settings.canDrawOverlays(this)) {
            Timber.w("Overlay permission missing — background launches stay blocked by the system")
            return
        }
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                putExtra(MainActivity.EXTRA_WAKE_COMMAND, true)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Timber.e(e, "Could not bring assistant to front")
        }
    }

    // ------------------------------------------------------------------
    // Foreground plumbing
    // ------------------------------------------------------------------

    private fun startForegroundNotification(text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Background Listening",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
        startForeground(NOTIFICATION_ID, buildNotification(text))
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("استماع كلمة التنبيه نشط")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(controlReceiver)
        } catch (e: IllegalArgumentException) {
            // not registered — ignore
        }
        try {
            speechService?.apply {
                stop()
                shutdown()
            }
        } catch (e: Exception) {
            Timber.e(e, "Error shutting down Vosk speech service")
        }
        speechService = null
        recognizer?.close()
        recognizer = null
        model?.close()
        model = null
        job.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
