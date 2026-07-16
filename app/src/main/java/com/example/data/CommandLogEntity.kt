package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "command_logs")
data class CommandLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val commandText: String,
    val timestamp: Long,
    val status: String, // "SUCCESS" or "FAILED"
    val reason: String? = null
)
