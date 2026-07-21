package com.example.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * حقيقة عن المستخدم علّمها للمساعد صراحة بصوته — ذاكرة أمد طويل:
 *
 *  - verb_synonym : مرادف لفظي «دق» ← «اتصل» (لهجة المستخدم الشخصية)
 *  - person_alias : كنية/قرابة «اخي» ← «محمد الحداد» (خريطة العلاقات)
 *  - fact         : حقيقة حرة «سيارتي» ← «هيلوكس» (نموذج المستخدم مستقبلاً)
 */
@Entity(tableName = "user_facts")
data class UserFactEntity(
    @PrimaryKey val key: String,
    val originalKey: String,
    val value: String,
    val type: String,
    val useCount: Int,
    val createdAt: Long,
    val lastUsed: Long
)

@Dao
interface UserFactDao {
    @Query("SELECT * FROM user_facts WHERE type = :type")
    suspend fun allOfType(type: String): List<UserFactEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(fact: UserFactEntity)

    @Query("SELECT * FROM user_facts WHERE `key` = :key AND type = :type")
    suspend fun find(key: String, type: String): UserFactEntity?

    @Query("DELETE FROM user_facts WHERE `key` = :key AND type = :type")
    suspend fun delete(key: String, type: String)

    @Query("DELETE FROM user_facts")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM user_facts")
    fun observeCount(): Flow<Int>
}
