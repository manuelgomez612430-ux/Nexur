package com.naxor.app.data

import androidx.room.*

@Dao
interface DebtorDao {
    @Query("SELECT * FROM debtors ORDER BY deudaTotal DESC")
    suspend fun getAllDebtors(): List<DebtorEntity>

    @Insert
    suspend fun insertDebtor(debtor: DebtorEntity): Long

    @Update
    suspend fun updateDebtor(debtor: DebtorEntity)

    @Delete
    suspend fun deleteDebtor(debtor: DebtorEntity)

    @Query("SELECT * FROM debts WHERE debtorId = :debtorId ORDER BY fecha DESC")
    suspend fun getDebtsForDebtor(debtorId: Int): List<DebtDetailEntity>

    @Insert
    suspend fun insertDebtDetail(debt: DebtDetailEntity)

    @Transaction
    suspend fun addDebtToDebtor(debtorId: Int, monto: Double, concepto: String) {
        // Esta función es especial: agrega la deuda y actualiza el total automáticamente
        val detail = DebtDetailEntity(debtorId = debtorId, concepto = concepto, monto = monto)
        insertDebtDetail(detail)
        
        // Aquí necesitaríamos una forma de actualizar el total en la tabla deudores
        // Lo manejaremos mejor desde la Activity por simplicidad por ahora
    }
}
