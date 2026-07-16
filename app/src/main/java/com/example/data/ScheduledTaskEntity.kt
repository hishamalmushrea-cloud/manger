package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "scheduled_tasks")
data class ScheduledTaskEntity(
    @PrimaryKey val id: String, // UUID from WorkManager
    val commandText: String,
    val scheduledTime: Long,
    val isCompleted: Boolean = false
)
