package com.naxor.app.data

import androidx.room.*

@Dao
interface ExpenseDao {
    @Query("SELECT * FROM expenses ORDER BY fecha DESC")
    suspend fun getAllExpenses(): List<ExpenseEntity>

    @Insert
    suspend fun insert(expense: ExpenseEntity)

    @Delete
    suspend fun delete(expense: ExpenseEntity)

    @Query("SELECT SUM(monto) FROM expenses")
    suspend fun getTotalExpenses(): Double?

    @Query("DELETE FROM expenses")
    suspend fun deleteAll()
}
