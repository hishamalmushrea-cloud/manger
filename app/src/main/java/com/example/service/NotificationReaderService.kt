package com.example.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import timber.log.Timber

class NotificationReaderService : NotificationListenerService() {
    
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        super.onNotificationPosted(sbn)
        val title = sbn.notification.extras.getString("android.title") ?: return
        Timber.d("Notification Received from ${sbn.packageName}: $title")
        // We only read the title as requested by the user, for specific future actions.
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        super.onNotificationRemoved(sbn)
    }
}
