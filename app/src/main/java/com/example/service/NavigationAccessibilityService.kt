package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import timber.log.Timber

class NavigationAccessibilityService : AccessibilityService() {

    companion object {
        private var instance: NavigationAccessibilityService? = null
        
        fun performGlobalActionStatic(action: Int): Boolean {
            return instance?.performGlobalAction(action) ?: false
        }
        
        fun isAccessibilityServiceEnabled(): Boolean {
            return instance != null
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val info = AccessibilityServiceInfo()
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        serviceInfo = info
        Timber.d("Accessibility Service Connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We only use this for global actions, not reading content
    }

    override fun onInterrupt() {
        // Interrupted
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }
}
