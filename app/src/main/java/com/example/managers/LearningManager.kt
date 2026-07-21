package com.example.managers

import com.example.data.LearnedCommandDao
import com.example.data.LearnedCommandEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device user learning ("learn from my corrections"):
 *
 *  - [lookup]  — heard this exact phrase before? run its saved command.
 *  - [learn]   — user corrected us: map their phrase to the right command.
 *  - [recordUse] — every re-use raises confidence & stats.
 *  - [clearAll] — settings option to wipe everything learned.
 *
 * Confidence model: starts at 0.60 and grows +0.05 per successful use,
 * capped at 0.98 (we never claim 100% certainty for voice input).
 */
@Singleton
class LearningManager @Inject constructor(
    private val dao: LearnedCommandDao
) {

    /** Find a previously learned mapping for what the user just said. */
    suspend fun lookup(rawPhrase: String): LearnedCommandEntity? =
        dao.findByPhrase(normalize(rawPhrase))

    /** Save (or reinforce) a correction: [rawPhrase] now means [correctCommand]. */
    suspend fun learn(rawPhrase: String, correctCommand: String) {
        val key = normalize(rawPhrase)
        if (key.isEmpty() || correctCommand.isBlank()) return
        val existing = dao.findByPhrase(key)
        val now = System.currentTimeMillis()
        val entity = if (existing != null) {
            existing.copy(
                correctCommand = correctCommand.trim(),
                useCount = existing.useCount + 1,
                confidence = confidenceFor(existing.useCount + 1),
                lastUsed = now
            )
        } else {
            LearnedCommandEntity(
                phrase = key,
                originalPhrase = rawPhrase.trim(),
                correctCommand = correctCommand.trim(),
                useCount = 1,
                confidence = confidenceFor(1),
                learnedAt = now,
                lastUsed = now
            )
        }
        dao.upsert(entity)
    }

    /** A learned command was just executed — grow confidence & counters. */
    suspend fun recordUse(entity: LearnedCommandEntity) {
        dao.upsert(
            entity.copy(
                useCount = entity.useCount + 1,
                confidence = confidenceFor(entity.useCount + 1),
                lastUsed = System.currentTimeMillis()
            )
        )
    }

    suspend fun clearAll() = dao.clearAll()

    fun observeCount(): Flow<Int> = dao.observeCount()

    private fun confidenceFor(useCount: Int): Float =
        (BASE_CONFIDENCE + GROWTH * useCount).coerceAtMost(MAX_CONFIDENCE)

    companion object {
        private const val BASE_CONFIDENCE = 0.60f
        private const val GROWTH = 0.05f
        private const val MAX_CONFIDENCE = 0.98f

        /** Arabic-tolerant normalization (shares the app matcher's logic). */
        fun normalize(text: String): String = AppOpenerManager.normalize(text)
    }
}
