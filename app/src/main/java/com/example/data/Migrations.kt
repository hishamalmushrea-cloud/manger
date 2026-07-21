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

/**
 * v3 -> v4: الطبقة الإدراكية — جدول أحداث الاستخدام (ذاكرة العادات/الأمد الطويل)
 * وجدول حقائق المستخدم (مرادفات اللهجة + خريطة العلاقات). البيانات القديمة
 * تبقى كما هي؛ الجداول جديدة كلياً.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `command_events` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`kind` TEXT NOT NULL, " +
                "`label` TEXT, " +
                "`text` TEXT, " +
                "`bucket` TEXT NOT NULL, " +
                "`at` INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `user_facts` (" +
                "`key` TEXT NOT NULL PRIMARY KEY, " +
                "`originalKey` TEXT NOT NULL, " +
                "`value` TEXT NOT NULL, " +
                "`type` TEXT NOT NULL, " +
                "`useCount` INTEGER NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`lastUsed` INTEGER NOT NULL)"
        )
    }
}

/**
 * v4 -> v5: مراقبة الأداء الذاتي — جدول إحصاءات النجاح/الفشل/التصحيحات
 * لكل نوع إجراء، يغذّي محرك الثقة وتقرير «ما نسبة نجاحك؟».
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `self_stats` (" +
                "`actionKey` TEXT NOT NULL PRIMARY KEY, " +
                "`attempts` INTEGER NOT NULL, " +
                "`successes` INTEGER NOT NULL, " +
                "`corrections` INTEGER NOT NULL, " +
                "`totalMs` INTEGER NOT NULL, " +
                "`lastAt` INTEGER NOT NULL)"
        )
    }
}

/**
 * v5 -> v6: البحث الصوتي عن الملفات — جدول الفهرس المحلي file_index (لقطة
 * موحّدة: نوع/اسم/فنان/مجلد/URI/إحصاءات تشغيل) وجدول تفضيلات التصحيح
 * file_choice_fixes («أقصد الثانية» ← تتعلم). البيانات القديمة سليمة.
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `file_index` (" +
                "`uriString` TEXT NOT NULL PRIMARY KEY, " +
                "`mediaStoreId` INTEGER NOT NULL DEFAULT -1, " +
                "`kind` TEXT NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`nameNorm` TEXT NOT NULL DEFAULT '', " +
                "`stem` TEXT NOT NULL DEFAULT '', " +
                "`artist` TEXT NOT NULL DEFAULT '', " +
                "`artistNorm` TEXT NOT NULL DEFAULT '', " +
                "`album` TEXT NOT NULL DEFAULT '', " +
                "`folder` TEXT NOT NULL DEFAULT '', " +
                "`folderPath` TEXT NOT NULL DEFAULT '', " +
                "`mime` TEXT NOT NULL DEFAULT '', " +
                "`durationMs` INTEGER NOT NULL DEFAULT 0, " +
                "`sizeBytes` INTEGER NOT NULL DEFAULT 0, " +
                "`dateModified` INTEGER NOT NULL DEFAULT 0, " +
                "`dateAdded` INTEGER NOT NULL DEFAULT 0, " +
                "`playCount` INTEGER NOT NULL DEFAULT 0, " +
                "`lastPlayedAt` INTEGER NOT NULL DEFAULT 0, " +
                "`lastPositionMs` INTEGER NOT NULL DEFAULT 0, " +
                "`hidden` INTEGER NOT NULL DEFAULT 0, " +
                "`fromSaf` INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_file_index_kind` ON `file_index` (`kind`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_file_index_nameNorm` ON `file_index` (`nameNorm`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_file_index_artistNorm` ON `file_index` (`artistNorm`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_file_index_folder` ON `file_index` (`folder`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `file_choice_fixes` (" +
                "`queryNorm` TEXT NOT NULL PRIMARY KEY, " +
                "`uriString` TEXT NOT NULL, " +
                "`at` INTEGER NOT NULL)"
        )
    }
}
