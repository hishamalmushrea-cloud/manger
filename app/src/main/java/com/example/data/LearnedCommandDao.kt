package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LearnedCommandDao {

    @Query("SELECT * FROM learned_commands WHERE phrase = :phrase LIMIT 1")
    suspend fun findByPhrase(phrase: String): LearnedCommandEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LearnedCommandEntity)

    @Query("DELETE FROM learned_commands")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM learned_commands")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM learned_commands ORDER BY lastUsed DESC")
    fun observeAll(): Flow<List<LearnedCommandEntity>>
}
