package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CommandDao {

    @Insert
    suspend fun insertLog(log: CommandLogEntity)

    @Query("SELECT * FROM command_logs ORDER BY timestamp DESC")
    fun getAllLogs(): Flow<List<CommandLogEntity>>

    /** حذف أمر محدد من السجل. */
    @Query("DELETE FROM command_logs WHERE id = :id")
    suspend fun deleteById(id: Int)

    /** مسح السجل بالكامل. */
    @Query("DELETE FROM command_logs")
    suspend fun clearAll()

    /** إحصائية سريعة: عدد الأوامر الكلي. */
    @Query("SELECT COUNT(*) FROM command_logs")
    fun countAll(): Flow<Int>

    /** إحصائية سريعة: عدد الأوامر الناجحة. */
    @Query("SELECT COUNT(*) FROM command_logs WHERE status = 'SUCCESS'")
    fun countSuccessful(): Flow<Int>
}
