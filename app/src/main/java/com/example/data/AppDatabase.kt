package com.example.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [CommandLogEntity::class, ScheduledTaskEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun commandDao(): CommandDao
    abstract fun scheduledTaskDao(): ScheduledTaskDao
}
