package com.gomezmart.app.data

import androidx.room.*

@Dao
interface MovementLogDao {
    @Query("SELECT * FROM movement_logs ORDER BY timestamp DESC")
    suspend fun getAllLogs(): List<MovementLogEntity>

    @Query("SELECT * FROM movement_logs ORDER BY timestamp DESC")
    fun getAllLogsFlow(): kotlinx.coroutines.flow.Flow<List<MovementLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: MovementLogEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(logs: List<MovementLogEntity>)

    @Delete
    suspend fun delete(log: MovementLogEntity)

    @Query("SELECT * FROM movement_logs WHERE isSynced = 0")
    suspend fun getUnsyncedLogs(): List<MovementLogEntity>

    @Query("SELECT * FROM movement_logs ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentLogs(limit: Int): List<MovementLogEntity>

    @Query("SELECT * FROM movement_logs WHERE id = :id LIMIT 1")
    suspend fun getLogById(id: String): MovementLogEntity?

    @Query("SELECT * FROM movement_logs WHERE isDeleted = 0 ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentLogsActive(limit: Int): List<MovementLogEntity>

    @Query("SELECT * FROM movement_logs WHERE isDeleted = 1")
    suspend fun getDeletedLogs(): List<MovementLogEntity>

    @Query("UPDATE movement_logs SET isDeleted = 1, isSynced = 0 WHERE id = :id")
    suspend fun markLogDeleted(id: String)

    @Delete
    suspend fun deleteLog(log: MovementLogEntity)

    @Query("DELETE FROM movement_logs")
    suspend fun deleteAll()

    @Query("SELECT * FROM movement_logs ORDER BY timestamp DESC LIMIT 20")
    fun getLastMovements(): androidx.lifecycle.LiveData<List<MovementLogEntity>>

    @Query("SELECT * FROM movement_logs ORDER BY timestamp DESC LIMIT 20")
    suspend fun getLastMovementsOnce(): List<MovementLogEntity>

    @Query("SELECT COUNT(*) FROM movement_logs WHERE isSynced = 0")
    fun getUnsyncedCount(): androidx.lifecycle.LiveData<Int>

    @Query("UPDATE movement_logs SET isSynced = 1 WHERE id = :id")
    suspend fun markSynced(id: String)
}
