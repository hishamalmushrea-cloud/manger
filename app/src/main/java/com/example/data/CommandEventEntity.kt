package com.example.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * حدث استخدام واحد — وقود «العادات» وذاكرة الأمد الطويل للطبقة الإدراكية.
 * يُسجَّل بعد كل إجراء ناجح: نوعه (فتح تطبيق/اتصال/واتساب...) وتسمية الكيان
 * (اسم التطبيق أو جهة الاتصال) وفترة اليوم، ويُحتفظ به 90 يوماً فقط.
 */
@Entity(tableName = "command_events")
data class CommandEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    /** نوع الإجراء: open_app / call / whatsapp / volume / alarm ... */
    val kind: String,
    /** الاسم البشري للكيان (تسمية التطبيق أو الاسم المعروض لجهة الاتصال) — قد يكون null. */
    val label: String?,
    /** النص الخام للأمر (للتدقيق البشري فقط). */
    val text: String?,
    /** فترة اليوم: morning / afternoon / evening / night */
    val bucket: String,
    val at: Long
)

/** صف تجميعي: التسمية وعدد تكرارها. */
data class LabelCount(val label: String, val c: Int)

/** صف تجميعي: فترة اليوم وعدد أحداثها. */
data class BucketCount(val bucket: String, val c: Int)

@Dao
interface CommandEventDao {
    @Insert
    suspend fun insert(event: CommandEventEntity)

    @Query(
        "SELECT label, COUNT(*) AS c FROM command_events " +
            "WHERE kind = :kind AND at > :since AND label IS NOT NULL " +
            "GROUP BY label ORDER BY c DESC LIMIT :limit"
    )
    suspend fun top(kind: String, since: Long, limit: Int): List<LabelCount>

    @Query("SELECT COUNT(*) FROM command_events WHERE at > :since")
    suspend fun countSince(since: Long): Int

    @Query(
        "SELECT bucket, COUNT(*) AS c FROM command_events " +
            "WHERE at > :since GROUP BY bucket ORDER BY c DESC LIMIT 1"
    )
    suspend fun topBucket(since: Long): BucketCount?

    @Query("DELETE FROM command_events WHERE at < :before")
    suspend fun prune(before: Long)
}
