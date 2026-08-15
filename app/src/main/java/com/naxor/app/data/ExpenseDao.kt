package com.naxor.app.data

import androidx.room.*

@Dao
interface ExpenseDao {
    @Query("SELECT * FROM expenses ORDER BY fecha DESC")
    suspend fun getAllExpenses(): List<ExpenseEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(expense: ExpenseEntity)

    @Update
    suspend fun update(expense: ExpenseEntity)

    @Delete
    suspend fun delete(expense: ExpenseEntity)

    @Query("SELECT SUM(monto) FROM expenses")
    suspend fun getTotalExpenses(): Double?

    @Query("DELETE FROM expenses")
    suspend fun deleteAll()

    @Query("SELECT * FROM expenses WHERE isSynced = 0")
    suspend fun getAllUnsyncedExpenses(): List<ExpenseEntity>
}
