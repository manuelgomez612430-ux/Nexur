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
}
