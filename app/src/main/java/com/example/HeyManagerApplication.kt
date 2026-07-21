package com.example

import android.app.Application
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.example.worker.FileIndexWorker
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class HeyManagerApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    /** يرصد تغيّرات الوسائط (أغنية/صورة/ملف جديد) ويطلب فحصاً عاجلاً للفهرس. */
    private val mediaObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            FileIndexWorker.enqueueExpedited(this@HeyManagerApplication)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Timber.plant(Timber.DebugTree())

        // فهرس الملفات: دورية 12 ساعة + مراقبة فورية لعمليات الإضافة.
        FileIndexWorker.schedulePeriodic(this)
        try {
            contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, mediaObserver)
            contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, mediaObserver)
            contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, mediaObserver)
        } catch (e: Exception) {
            Timber.w(e, "MediaStore observer registration failed")
        }
    }
}
