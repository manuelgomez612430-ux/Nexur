package com.naxor.app.data

import androidx.room.*

@Dao
interface BusinessDebtDao {
    @Query("SELECT * FROM business_debts WHERE isDeleted = 0 AND isPaid = 0 ORDER BY fechaVencimiento ASC")
    suspend fun getPendingDebts(): List<BusinessDebtEntity>

    @Query("SELECT * FROM business_debts WHERE isDeleted = 0 AND isPaid = 1 ORDER BY timestamp DESC")
    suspend fun getPaidDebts(): List<BusinessDebtEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(debt: BusinessDebtEntity)

    @Update
    suspend fun update(debt: BusinessDebtEntity)

    @Query("SELECT * FROM business_debts WHERE isSynced = 0")
    suspend fun getUnsynced(): List<BusinessDebtEntity>

    @Query("SELECT * FROM business_debts WHERE isDeleted = 0 AND fechaVencimiento > 0 AND fechaVencimiento <= :timeLimit AND isPaid = 0")
    suspend fun getUpcomingPayments(timeLimit: Long): List<BusinessDebtEntity>

    @Query("SELECT SUM(montoTotal - montoPagado) FROM business_debts WHERE isDeleted = 0 AND isPaid = 0")
    suspend fun getTotalOwed(): Double?
}
