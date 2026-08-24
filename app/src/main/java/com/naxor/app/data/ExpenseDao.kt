package com.naxor.app.data

import androidx.room.*

@Dao
interface ExpenseDao {
    @Query("SELECT * FROM expenses WHERE (isDeleted = 0) ORDER BY CASE WHEN isPaid = 0 THEN 0 ELSE 1 END, fecha DESC")
    suspend fun getAllExpenses(): List<ExpenseEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(expense: ExpenseEntity)

    @Update
    suspend fun update(expense: ExpenseEntity)

    @Delete
    suspend fun delete(expense: ExpenseEntity)

    @Query("SELECT SUM(monto) FROM expenses WHERE isDeleted = 0 AND isPaid = 1")
    suspend fun getTotalExpenses(): Double?

    @Query("DELETE FROM expenses")
    suspend fun deleteAll()

    @Query("SELECT * FROM expenses WHERE isSynced = 0 AND isDeleted = 0")
    suspend fun getAllUnsyncedExpenses(): List<ExpenseEntity>

    @Query("SELECT * FROM expenses WHERE isSynced = 0 AND isDeleted = 1")
    suspend fun getDeletedExpenses(): List<ExpenseEntity>

    @Query("SELECT SUM(monto) FROM expenses WHERE isPaid = 1 AND isDeleted = 0 AND fecha >= :startTime")
    suspend fun getExpensesAmountFrom(startTime: Long): Double?

    @Query("SELECT SUM(monto) FROM expenses WHERE isPaid = 1 AND isDeleted = 0 AND fecha >= :startTime AND fecha <= :endTime")
    suspend fun getExpensesAmountInRange(startTime: Long, endTime: Long): Double?

    @Query("SELECT * FROM expenses WHERE isPaid = 0 AND isDeleted = 0 AND fechaProgramada <= :timeLimit ORDER BY fechaProgramada ASC")
    suspend fun getPendingExpenses(timeLimit: Long): List<ExpenseEntity>

    @Query("SELECT COUNT(*) FROM expenses WHERE isSynced = 0")
    fun getUnsyncedCount(): androidx.lifecycle.LiveData<Int>

    @Query("SELECT DISTINCT categoria FROM expenses WHERE isDeleted = 0 ORDER BY categoria ASC")
    suspend fun getUniqueCategories(): List<String>
}
