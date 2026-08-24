package com.naxor.app.data

import androidx.room.*

@Dao
interface MovementLogDao {
    @Query("SELECT * FROM movement_logs ORDER BY timestamp DESC")
    suspend fun getAllLogs(): List<MovementLogEntity>

    @Query("SELECT * FROM movement_logs ORDER BY timestamp DESC")
    fun getAllLogsFlow(): kotlinx.coroutines.flow.Flow<List<MovementLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: MovementLogEntity)

    @Query("SELECT * FROM movement_logs WHERE isSynced = 0")
    suspend fun getUnsyncedLogs(): List<MovementLogEntity>

    @Query("DELETE FROM movement_logs")
    suspend fun deleteAll()

    @Query("SELECT * FROM movement_logs ORDER BY timestamp DESC LIMIT 20")
    fun getLastMovements(): androidx.lifecycle.LiveData<List<MovementLogEntity>>

    @Query("SELECT * FROM movement_logs ORDER BY timestamp DESC LIMIT 20")
    suspend fun getLastMovementsOnce(): List<MovementLogEntity>

    @Query("SELECT COUNT(*) FROM movement_logs WHERE isSynced = 0")
    fun getUnsyncedCount(): androidx.lifecycle.LiveData<Int>
}
