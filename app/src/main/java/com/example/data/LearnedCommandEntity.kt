package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A command the assistant learned from a user correction.
 * Everything stays 100% on-device (no internet, no cloud).
 *
 * Example: user says «شغل البلو» → not understood → user corrects:
 * «افتح البلوتوث» → the pair is stored here and next time
 * «شغل البلو» executes instantly.
 */
@Entity(tableName = "learned_commands")
data class LearnedCommandEntity(
    /** Normalized spoken phrase — the lookup key (Arabic-tolerant). */
    @PrimaryKey val phrase: String,
    /** Exactly what the user said, for display/diagnostics. */
    val originalPhrase: String,
    /** The working command to execute when [phrase] is heard again. */
    val correctCommand: String,
    /** How many times this learned mapping has been used. */
    val useCount: Int,
    /** 0..1 — grows with successful re-use (see LearningManager). */
    val confidence: Float,
    /** When the correction was first saved. */
    val learnedAt: Long,
    /** Last time the mapping was executed. */
    val lastUsed: Long
)
