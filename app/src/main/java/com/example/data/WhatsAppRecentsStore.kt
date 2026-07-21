package com.example.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remembers who messaged you on WhatsApp, newest first. WhatsApp exposes no
 * chat-list API, so NotificationReaderService learns senders from incoming
 * WhatsApp notifications — enabling name-free commands like
 * «افتح آخر محادثة واتساب»، «المحادثة الثانية»، or «المحادثة المفضلة»
 * (your most active conversation).
 */
@Singleton
class WhatsAppRecentsStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Records a WhatsApp sender title (newest at the head of the list). */
    @Synchronized
    fun record(sender: String) {
        val clean = sender.replace("|", " ").trim()
        if (clean.isEmpty()) return
        val raw = loadRaw()
        raw.add(0, clean)
        while (raw.size > MAX_ENTRIES) raw.removeLast()
        prefs.edit().putString(KEY, raw.joinToString("|")).apply()
    }

    /** Distinct senders, newest first. */
    fun recent(): List<String> = loadRaw().distinct()

    /** The [index]-th most recent distinct sender (0 = latest). */
    fun nth(index: Int): String? = recent().getOrNull(index)

    /** Sender with the most stored messages = your "favourite" conversation. */
    fun mostFrequent(): String? =
        loadRaw().groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

    fun isEmpty(): Boolean = loadRaw().isEmpty()

    private fun loadRaw(): MutableList<String> {
        val stored = prefs.getString(KEY, "") ?: ""
        if (stored.isBlank()) return mutableListOf()
        return stored.split("|").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
    }

    companion object {
        private const val PREFS = "whatsapp_recents"
        private const val KEY = "senders"
        private const val MAX_ENTRIES = 100
    }
}
