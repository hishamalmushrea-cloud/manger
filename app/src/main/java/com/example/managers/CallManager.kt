package com.example.managers

import android.content.Context
import android.content.Intent
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CallManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val contactResolver: ContactResolver
) {

    fun makeCall(target: String, scheduled: Boolean = false): Boolean {
        // If it's a number, call directly. Otherwise, find contact.
        val isNumber = target.matches(Regex("^[0-9+*#]+$"))
        val numberToCall = if (isNumber) target else contactResolver.resolveNumber(target)

        if (numberToCall == null) return false

        // For scheduled tasks in background, Android 10+ restricts background activity starts
        // So we use ACTION_DIAL for scheduled, and ACTION_CALL for direct (if permission granted).
        val action = if (scheduled) Intent.ACTION_DIAL else Intent.ACTION_CALL
        val intent = Intent(action, Uri.parse("tel:$numberToCall")).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            // Fallback to DIAL if CALL fails due to permission
            if (action == Intent.ACTION_CALL) {
                val fallbackIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$numberToCall")).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                try {
                    context.startActivity(fallbackIntent)
                    true
                } catch (e2: Exception) {
                    false
                }
            } else {
                false
            }
        }
    }
}
