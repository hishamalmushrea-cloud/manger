package com.example.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * فهرس الملفات المحلي (Voice File Search) — جدول واحد يمثل لقطة موحدة عن
 * ملفات الجهاز التي يهتم بها المساعد الصوتي: صوت، صور، فيديو، ومستندات.
 *
 * لماذا فهرس داخلي بدل الاستعلام المباشر من MediaStore في كل مرة؟
 *  1) سرعة: بحث كامل في ذاكرة التطبيق يكلف أجزاء من الثانية (<300ms لعشرة
 *     آلاف ملف) مقابل استعلامات MediaStore المتكررة.
 *  2) حقول إضافية لا يوفرها MediaStore مباشرة: playCount/lastPositionMs
 *     (استئناف التشغيل) وhidden (إخفاء يدوي) واسم المجلد الموحّد.
 *  3) خصوصية مضاعفة: اللقطة تعيش داخل قاعدة التطبيق الخاصة ولا تغادره أبداً.
 *
 * التحديث يحدث بثلاث طرق: فحص كسول عند أول أمر ملفات (إن قدمت اللقطة أكثر
 * من 30 دقيقة)، مهمة دورية خلفية (WorkManager)، ومراقب تغييرات MediaStore.
 */
@Entity(
    tableName = "file_index",
    indices = [
        Index(value = ["kind"]),
        Index(value = ["nameNorm"]),
        Index(value = ["artistNorm"]),
        Index(value = ["folder"])
    ]
)
data class FileIndexEntity(
    /** المفتاح الأساسي: نص الـURI (مستقر لكل ملف، ويعمل لـ MediaStore وSAF معاً). */
    @PrimaryKey val uriString: String,
    /** معرف MediaStore إن وُجد (أو ‎-1 لملفات SAF). يفيد عند إعادة بناء الـURI. */
    val mediaStoreId: Long = -1L,
    /** التصنيف: AUDIO / IMAGE / VIDEO / PDF / WORD / EXCEL / OTHER. */
    val kind: String,
    /** الاسم الحقيقي كما يظهر للمستخدم. */
    val name: String,
    /** الاسم بعد التطبيع (بلا تشكيل/همزات/فراغات زائدة) — أساس البحث المتسامح. */
    val nameNorm: String = "",
    /** jسم الملف بلا امتداد ومطبَّع — «فاتورة_2024.pdf» يجدها استعلام «فاتورة». */
    val stem: String = "",
    /** الفنان (للصوتيات فقط) — خام ومطبَّع. */
    val artist: String = "",
    val artistNorm: String = "",
    val album: String = "",
    /** اسم المجلد الأقرب (مطبَّع): download, camera, screenshots, المفضلة... */
    val folder: String = "",
    /** المسار النسبي الكامل عند توفره (RELATIVE_PATH أو مسار DocumentFile). */
    val folderPath: String = "",
    val mime: String = "",
    val durationMs: Long = 0L,
    val sizeBytes: Long = 0L,
    val dateModified: Long = 0L,
    val dateAdded: Long = 0L,
    /** عدد مرات التشغيل الناجحة — يغذي الترتيب واقتراح «الأكثر استماعاً». */
    val playCount: Int = 0,
    val lastPlayedAt: Long = 0L,
    /** آخر موضع توقف (ms) — يمكّن «واصل الأغنية من حيث توقفت». */
    val lastPositionMs: Long = 0L,
    /** أخفاها المستخدم من نتائج البحث. */
    val hidden: Boolean = false,
    /** أتت من شجرة SAF يملكها المستخدم (وليست من MediaStore). */
    val fromSaf: Boolean = false
)

/** صف خفيف لنقل إحصاءات التشغيل عند إعادة بناء الفهرس دون خسارتها. */
data class FileStatRow(
    val uriString: String,
    val playCount: Int,
    val lastPlayedAt: Long,
    val lastPositionMs: Long,
    val hidden: Boolean
)

/**
 * تصحيح اختيار المستخدم: «لا، أقصد الأغنية الثانية» — نربط الاستعلام المطبَّع
 * بالملف الذي اختاره فعلاً حتى يتصدر في المرة القادمة (آلية التعلم من التصحيح).
 */
@Entity(tableName = "file_choice_fixes")
data class ChoiceFixEntity(
    @PrimaryKey val queryNorm: String,
    val uriString: String,
    val at: Long
)

@Dao
interface FileIndexDao {

    @Upsert
    suspend fun upsertAll(items: List<FileIndexEntity>)

    @Query("SELECT COUNT(*) FROM file_index")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM file_index WHERE kind = :kind AND hidden = 0")
    suspend fun countByKind(kind: String): Int

    @Query("SELECT uriString, playCount, lastPlayedAt, lastPositionMs, hidden FROM file_index")
    suspend fun allStats(): List<FileStatRow>

    @Query("SELECT * FROM file_index WHERE kind IN (:kinds) AND hidden = 0")
    suspend fun activeByKinds(kinds: List<String>): List<FileIndexEntity>

    @Query("SELECT * FROM file_index WHERE kind = 'AUDIO' AND hidden = 0")
    suspend fun allAudio(): List<FileIndexEntity>

    @Query("SELECT * FROM file_index WHERE uriString = :uri LIMIT 1")
    suspend fun byUri(uri: String): FileIndexEntity?

    @Query("UPDATE file_index SET playCount = playCount + 1, lastPlayedAt = :at, lastPositionMs = 0 WHERE uriString = :uri")
    suspend fun markPlayed(uri: String, at: Long)

    @Query("UPDATE file_index SET lastPositionMs = :pos WHERE uriString = :uri")
    suspend fun savePosition(uri: String, pos: Long)

    @Query("UPDATE file_index SET hidden = :hidden WHERE uriString = :uri")
    suspend fun setHidden(uri: String, hidden: Boolean)

    @Query("DELETE FROM file_index WHERE fromSaf = 0")
    suspend fun clearNonSaf()

    @Query("DELETE FROM file_index WHERE fromSaf = 1")
    suspend fun clearSaf()

    @Query("DELETE FROM file_index")
    suspend fun clear()

    @Query("SELECT * FROM file_index ORDER BY kind, playCount DESC, dateAdded DESC")
    fun observeAll(): Flow<List<FileIndexEntity>>
}

@Dao
interface ChoiceFixDao {

    @Query("SELECT * FROM file_choice_fixes WHERE queryNorm = :q LIMIT 1")
    suspend fun forQuery(q: String): ChoiceFixEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(fix: ChoiceFixEntity)

    @Query("DELETE FROM file_choice_fixes")
    suspend fun clear()
}
