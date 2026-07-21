package com.example.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v1 -> v2: adds the user-learning table (keeps logs & scheduled tasks intact).
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `learned_commands` (" +
                "`phrase` TEXT NOT NULL PRIMARY KEY, " +
                "`originalPhrase` TEXT NOT NULL, " +
                "`correctCommand` TEXT NOT NULL, " +
                "`useCount` INTEGER NOT NULL, " +
                "`confidence` REAL NOT NULL, " +
                "`learnedAt` INTEGER NOT NULL, " +
                "`lastUsed` INTEGER NOT NULL)"
        )
    }
}

/**
 * v2 -> v3: enriches the command log for the Command Center —
 * understood command, understanding confidence, executed action label,
 * failure reason and execution duration. Old rows are kept (defaults).
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `command_logs` ADD COLUMN `understoodCommand` TEXT")
        db.execSQL("ALTER TABLE `command_logs` ADD COLUMN `confidence` REAL NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `command_logs` ADD COLUMN `actionTaken` TEXT")
        db.execSQL("ALTER TABLE `command_logs` ADD COLUMN `failReason` TEXT")
        db.execSQL("ALTER TABLE `command_logs` ADD COLUMN `durationMs` INTEGER NOT NULL DEFAULT 0")
    }
}
