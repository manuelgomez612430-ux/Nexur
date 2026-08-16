package com.naxor.app.data

import androidx.room.*

@Dao
interface DebtorDao {
    @Query("SELECT * FROM debtors WHERE isDeleted = 0 ORDER BY deudaTotal DESC")
    suspend fun getAllDebtors(): List<DebtorEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDebtor(debtor: DebtorEntity)

    @Update
    suspend fun updateDebtor(debtor: DebtorEntity)

    @Delete
    suspend fun deleteDebtor(debtor: DebtorEntity)

    @Query("SELECT * FROM debts WHERE debtorId = :debtorId AND isDeleted = 0 ORDER BY fecha DESC")
    suspend fun getDebtsForDebtor(debtorId: String): List<DebtDetailEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDebtDetail(debt: DebtDetailEntity)

    @Transaction
    suspend fun addDebtToDebtor(debtorId: String, monto: Double, concepto: String) {
        // Esta función es especial: agrega la deuda y actualiza el total automáticamente
        val detail = DebtDetailEntity(debtorId = debtorId, concepto = concepto, monto = monto)
        insertDebtDetail(detail)
    }

    @Query("SELECT * FROM debtors WHERE isSynced = 0 AND isDeleted = 0")
    suspend fun getAllUnsyncedDebtors(): List<DebtorEntity>

    @Query("SELECT * FROM debts WHERE isSynced = 0 AND isDeleted = 0")
    suspend fun getAllUnsyncedDebtDetails(): List<DebtDetailEntity>

    @Query("SELECT * FROM debtors WHERE isSynced = 0 AND isDeleted = 1")
    suspend fun getDeletedDebtors(): List<DebtorEntity>

    @Query("SELECT * FROM debts WHERE isSynced = 0 AND isDeleted = 1")
    suspend fun getDeletedDebtDetails(): List<DebtDetailEntity>

    @Update
    suspend fun updateDebtDetail(debt: DebtDetailEntity)

    @Delete
    suspend fun deleteDebtDetail(debt: DebtDetailEntity)

    @Query("SELECT COUNT(*) FROM debtors WHERE isSynced = 0")
    fun getUnsyncedDebtorsCount(): androidx.lifecycle.LiveData<Int>

    @Query("SELECT COUNT(*) FROM debts WHERE isSynced = 0")
    fun getUnsyncedDebtDetailsCount(): androidx.lifecycle.LiveData<Int>
}
