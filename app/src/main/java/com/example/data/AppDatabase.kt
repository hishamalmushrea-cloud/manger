package com.example.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        CommandLogEntity::class,
        ScheduledTaskEntity::class,
        LearnedCommandEntity::class,
        CommandEventEntity::class,
        UserFactEntity::class,
        SelfStatEntity::class
    ],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun commandDao(): CommandDao
    abstract fun scheduledTaskDao(): ScheduledTaskDao
    abstract fun learnedCommandDao(): LearnedCommandDao
    abstract fun commandEventDao(): CommandEventDao
    abstract fun userFactDao(): UserFactDao
    abstract fun selfStatDao(): SelfStatDao
}
