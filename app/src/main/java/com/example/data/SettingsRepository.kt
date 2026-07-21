package com.example.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

// DataStore holding user-provided API keys (entered from the in-app settings dialog).
private val Context.settingsDataStore by preferencesDataStore(name = "hey_manager_settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext context: Context
) {
    private val dataStore = context.settingsDataStore

    companion object {
        private val KEY_GEMINI = stringPreferencesKey("gemini_api_key")
        private val KEY_CONTINUOUS_ENABLED = booleanPreferencesKey("continuous_conversation_enabled")
        private val KEY_CONVERSATION_SECONDS = intPreferencesKey("conversation_seconds")

        const val DEFAULT_CONVERSATION_SECONDS = 30

        /** Keys equal to these values (from .env.example) are placeholders, not real keys. */
        private val PLACEHOLDER_VALUES = setOf("", "MY_GEMINI_API_KEY", "YOUR_API_KEY_HERE")
    }

    val geminiApiKey: Flow<String> = dataStore.data.map { it[KEY_GEMINI].orEmpty() }

    suspend fun setGeminiApiKey(value: String) {
        dataStore.edit { it[KEY_GEMINI] = value.trim() }
    }

    // ---------------- Continuous conversation mode ----------------

    /** After a voice command, keep listening this long for follow-ups. Default ON. */
    val continuousEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_CONTINUOUS_ENABLED] ?: true }

    /** Conversation window length in seconds (default 30). */
    val conversationSeconds: Flow<Int> =
        dataStore.data.map { it[KEY_CONVERSATION_SECONDS] ?: DEFAULT_CONVERSATION_SECONDS }

    suspend fun setContinuousEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_CONTINUOUS_ENABLED] = enabled }
    }

    suspend fun setConversationSeconds(seconds: Int) {
        dataStore.edit { it[KEY_CONVERSATION_SECONDS] = seconds.coerceIn(5, 300) }
    }

    fun isUsableKey(value: String?): Boolean =
        !value.isNullOrBlank() && value.trim() !in PLACEHOLDER_VALUES
}
