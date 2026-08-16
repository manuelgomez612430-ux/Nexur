package com.naxor.app.data

import androidx.room.*

@Dao
interface ExpenseDao {
    @Query("SELECT * FROM expenses WHERE isDeleted = 0 ORDER BY fecha DESC")
    suspend fun getAllExpenses(): List<ExpenseEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(expense: ExpenseEntity)

    @Update
    suspend fun update(expense: ExpenseEntity)

    @Delete
    suspend fun delete(expense: ExpenseEntity)

    @Query("SELECT SUM(monto) FROM expenses WHERE isDeleted = 0")
    suspend fun getTotalExpenses(): Double?

    @Query("DELETE FROM expenses")
    suspend fun deleteAll()

    @Query("SELECT * FROM expenses WHERE isSynced = 0 AND isDeleted = 0")
    suspend fun getAllUnsyncedExpenses(): List<ExpenseEntity>

    @Query("SELECT * FROM expenses WHERE isSynced = 0 AND isDeleted = 1")
    suspend fun getDeletedExpenses(): List<ExpenseEntity>

    @Query("SELECT DISTINCT categoria FROM expenses WHERE isDeleted = 0 ORDER BY categoria ASC")
    suspend fun getUniqueCategories(): List<String>
}
