package com.example.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.BuildConfig
import com.example.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

class SpeechListenerService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    
    // Picovoice Porcupine (Optional - depends on API key)
    // private var porcupineManager: PorcupineManager? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundService()
        initializeWakeWord()
    }

    private fun startForegroundService() {
        val channelId = "speech_listener_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Background Listening",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("استماع الأوامر الصوتية نشط")
            .setContentText("التطبيق يستمع لكلمة التنبيه 'Hey Manager'")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()

        startForeground(101, notification)
    }

    private fun initializeWakeWord() {
        // Due to dynamic requirement of Wake Word and Porcupine SDK limitations without valid keys,
        // we'll leave a stub here that the user can implement properly or use standard SpeechRecognizer.
        // For a full production app, you would initialize PorcupineManager here with BuildConfig.PORCUPINE_API_KEY
        val apiKey = BuildConfig.PORCUPINE_API_KEY
        if (apiKey.isEmpty() || apiKey == "YOUR_API_KEY_HERE") {
            Timber.e("Porcupine API Key is missing. Wake word disabled.")
            return
        }
        
        try {
            /* 
            porcupineManager = PorcupineManager.Builder()
                .setAccessKey(apiKey)
                .setKeyword(Porcupine.BuiltInKeyword.HEY_BARISTA) // Assuming "Hey Manager" custom model isn't available, fallback to a built-in
                .build(applicationContext) { keywordIndex ->
                    Timber.d("Wake word detected!")
                    // Trigger UI to start speech recognition
                    sendBroadcast(Intent("WAKE_WORD_DETECTED"))
                }
            porcupineManager?.start()
            */
            Timber.d("Porcupine initialized (Stubbed)")
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize Porcupine")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // porcupineManager?.stop()
        // porcupineManager?.delete()
        job.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
