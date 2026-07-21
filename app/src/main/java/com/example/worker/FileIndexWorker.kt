package com.example.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.files.FileIndexer
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * مهمة الفهرسة الخلفية — تحافظ على طزاجة فهرس الملفات دون أن يشعر المستخدم:
 *
 *  - دورية كل 12 ساعة مع قيد «البطارية ليست منخفضة» (لا نحرق بطاريتك يا هشام 🔋).
 *  - عاجلة (expedited) تُستدعى من ContentObserver عندما يكتشف أن المستخدم
 *    أضاف أغنية/صورة/ملفاً جديداً — فيصبح قابلاً للبحث الصوتي خلال ثوانٍ.
 *
 * كل العمل محلي؛ لا ينشط أي اتصال شبكة.
 */
@HiltWorker
class FileIndexWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val fileIndexer: FileIndexer
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val summary = fileIndexer.ensureIndexed(force = true)
            Timber.d("Background file index done: %d files in %d ms", summary.total, summary.tookMs)
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "Background file indexing failed")
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val PERIODIC_NAME = "file_index_periodic"
        private const val EXPEDITED_NAME = "file_index_expedited"

        /** تسجيل مهمة دورية خفيفة — تُستدعى مرة واحدة عند تشغيل التطبيق. */
        fun schedulePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()
            val request = PeriodicWorkRequestBuilder<FileIndexWorker>(12, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        /** فحص عاجل بعد تغيّر MediaStore — KEEP حتى لا تتراكم الطلبات. */
        fun enqueueExpedited(context: Context) {
            val request = OneTimeWorkRequestBuilder<FileIndexWorker>()
                .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                EXPEDITED_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
