package com.naxor.app.data

import androidx.room.*

@Dao
interface CashDao {
    @Query("SELECT * FROM cash_sessions WHERE isOpen = 1 LIMIT 1")
    suspend fun getOpenSession(): CashSessionEntity?

    @Query("SELECT * FROM cash_sessions ORDER BY startTime DESC")
    suspend fun getAllSessions(): List<CashSessionEntity>

    @Insert
    suspend fun insert(session: CashSessionEntity)

    @Update
    suspend fun update(session: CashSessionEntity)

    @Query("SELECT COUNT(*) FROM cash_sessions WHERE isSynced = 0")
    fun getUnsyncedCount(): androidx.lifecycle.LiveData<Int>

    @Query("SELECT * FROM cash_sessions WHERE isSynced = 0 AND isDeleted = 0")
    suspend fun getAllUnsyncedSessions(): List<CashSessionEntity>

    @Query("SELECT * FROM cash_sessions WHERE isSynced = 0 AND isDeleted = 1")
    suspend fun getDeletedSessions(): List<CashSessionEntity>
}
