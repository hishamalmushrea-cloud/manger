package com.example.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.example.data.WhatsAppRecentsStore
import timber.log.Timber

/**
 * Reads notification titles only (as required by the user's consent).
 * WhatsApp senders are recorded so voice commands like «افتح آخر محادثة
 * واتساب» work without needing a contact name — WhatsApp itself exposes
 * no chat-list API, so incoming notifications are the discovery source.
 */
class NotificationReaderService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        super.onNotificationPosted(sbn)
        val title = sbn.notification.extras.getString("android.title")?.trim() ?: return
        Timber.d("Notification Received from ${sbn.packageName}: $title")

        if (sbn.packageName == WHATSAPP || sbn.packageName == WHATSAPP_BUSINESS) {
            // Skip service/summary notifications and group chats
            // (groups have no wa.me target).
            if (title.isEmpty()) return
            if (title.equals("WhatsApp", ignoreCase = true)) return
            if (title.contains("messages", ignoreCase = true)) return
            if (title.contains("backup", ignoreCase = true)) return
            if (sbn.notification.extras.getBoolean(EXTRA_IS_GROUP, false)) return

            WhatsAppRecentsStore(applicationContext).record(title)
            Timber.d("Recorded WhatsApp sender: %s", title)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        super.onNotificationRemoved(sbn)
    }

    companion object {
        private const val WHATSAPP = "com.whatsapp"
        private const val WHATSAPP_BUSINESS = "com.whatsapp.w4b"
        private const val EXTRA_IS_GROUP = "android.isGroupConversation"
    }
}
