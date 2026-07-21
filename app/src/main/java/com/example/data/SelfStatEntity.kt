package com.example.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * سجل تقييم الأداء الذاتي لكل نوع إجراء:
 * كم محاولة؟ كم نجاح؟ كم تصحيحاً احتاج؟ وكم استغرق؟
 * يغذّي «مراقبة النفس» ويُدخل عاملاً في حساب الثقة.
 */
@Entity(tableName = "self_stats")
data class SelfStatEntity(
    @PrimaryKey val actionKey: String,
    val attempts: Int,
    val successes: Int,
    val corrections: Int,
    val totalMs: Long,
    val lastAt: Long
)

@Dao
interface SelfStatDao {
    @Query("SELECT * FROM self_stats ORDER BY attempts DESC")
    suspend fun all(): List<SelfStatEntity>

    @Query("SELECT * FROM self_stats WHERE actionKey = :key")
    suspend fun find(key: String): SelfStatEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(stat: SelfStatEntity)

    @Query("DELETE FROM self_stats")
    suspend fun clearAll()
}
