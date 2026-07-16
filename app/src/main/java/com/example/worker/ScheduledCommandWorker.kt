package com.example.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.R
import com.example.data.CommandDao
import com.example.data.CommandLogEntity
import com.example.data.ScheduledTaskDao
import com.example.processor.CommandProcessor
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

@HiltWorker
class ScheduledCommandWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val commandProcessor: CommandProcessor,
    private val scheduledTaskDao: ScheduledTaskDao,
    private val commandDao: CommandDao
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val commandText = inputData.getString("COMMAND_TEXT") ?: return@withContext Result.failure()
        val taskId = inputData.getString("TASK_ID") ?: return@withContext Result.failure()

        Timber.d("Executing scheduled command: $commandText")
        
        val result = commandProcessor.processCommand(commandText, isScheduled = true)
        
        // Log it
        commandDao.insertLog(
            CommandLogEntity(
                commandText = commandText,
                timestamp = System.currentTimeMillis(),
                status = if (result.success) "SUCCESS" else "FAILED",
                reason = result.message
            )
        )

        // Mark completed
        scheduledTaskDao.markCompleted(taskId)
        
        showNotification(commandText, result.message)

        Result.success()
    }

    private fun showNotification(commandText: String, message: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "scheduled_commands"
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Scheduled Commands", NotificationManager.IMPORTANCE_DEFAULT)
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("تنفيذ أمر مجدول: $commandText")
            .setContentText(message)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }
}
