package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ScheduledTaskDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: ScheduledTaskEntity)

    @Query("UPDATE scheduled_tasks SET isCompleted = 1 WHERE id = :taskId")
    suspend fun markCompleted(taskId: String)

    @Query("DELETE FROM scheduled_tasks WHERE id = :taskId")
    suspend fun deleteTask(taskId: String)

    @Query("SELECT * FROM scheduled_tasks ORDER BY scheduledTime ASC")
    fun getAllTasks(): Flow<List<ScheduledTaskEntity>>
}
