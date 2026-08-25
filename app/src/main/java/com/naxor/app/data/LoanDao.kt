package com.naxor.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface LoanDao {
    // --- CLIENTS ---
    @Query("SELECT * FROM loan_clients WHERE isDeleted = 0 ORDER BY name ASC")
    fun getAllClients(): Flow<List<LoanClientEntity>>

    @Query("SELECT * FROM loan_clients WHERE isDeleted = 0 AND (name LIKE '%' || :query || '%' OR doc LIKE '%' || :query || '%') ORDER BY name ASC")
    fun searchClients(query: String): Flow<List<LoanClientEntity>>

    @Query("SELECT * FROM loan_clients WHERE id = :id LIMIT 1")
    suspend fun getClientById(id: String): LoanClientEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertClient(client: LoanClientEntity)

    @Update
    suspend fun updateClient(client: LoanClientEntity)

    // --- LOANS ---
    @Query("SELECT * FROM loans WHERE isDeleted = 0")
    fun getAllLoans(): Flow<List<LoanEntity>>

    @Query("SELECT * FROM loans WHERE clientId = :clientId AND isDeleted = 0")
    fun getLoansByClient(clientId: String): Flow<List<LoanEntity>>

    @Query("SELECT * FROM loans WHERE id = :id LIMIT 1")
    suspend fun getLoanById(id: String): LoanEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLoan(loan: LoanEntity)

    @Update
    suspend fun updateLoan(loan: LoanEntity)

    // --- INSTALLMENTS ---
    @Query("SELECT * FROM loan_installments WHERE loanId = :loanId AND isDeleted = 0 ORDER BY installmentNumber ASC")
    fun getInstallmentsByLoan(loanId: String): Flow<List<LoanInstallmentEntity>>

    @Query("SELECT * FROM loan_installments WHERE isDeleted = 0 AND status != 'PAID' AND dueDate <= :timeLimit")
    suspend fun getPendingCollections(timeLimit: Long): List<LoanInstallmentEntity>

    @Query("SELECT * FROM loan_installments WHERE isDeleted = 0 AND dueDate >= :start AND dueDate <= :end")
    fun getInstallmentsInRange(start: Long, end: Long): Flow<List<LoanInstallmentEntity>>

    @Query("SELECT * FROM loan_installments WHERE isDeleted = 0 AND status = 'PAID' AND dueDate >= :start AND dueDate <= :end")
    fun getPaidInstallmentsInRange(start: Long, end: Long): Flow<List<LoanInstallmentEntity>>

    @Query("SELECT * FROM loan_installments WHERE isDeleted = 0 AND amountPaid > 0")
    fun getAllPaymentsFlow(): Flow<List<LoanInstallmentEntity>>

    @Query("SELECT SUM(lateFeePaid) FROM loan_installments WHERE isDeleted = 0")
    fun getTotalLateFeesFlow(): Flow<Double?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInstallments(installments: List<LoanInstallmentEntity>)

    @Update
    suspend fun updateInstallment(installment: LoanInstallmentEntity)

    // --- EXPENSES ---
    @Query("SELECT * FROM loan_expenses WHERE isDeleted = 0 ORDER BY timestamp DESC")
    fun getAllExpenses(): Flow<List<LoanExpenseEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExpense(expense: LoanExpenseEntity)

    @Query("SELECT SUM(amount) FROM loan_expenses WHERE isDeleted = 0")
    fun getTotalExpensesFlow(): Flow<Double?>

    // --- STATS ---
    @Query("SELECT SUM(amount) FROM loans WHERE isDeleted = 0 AND status != 'PAID'")
    fun getCapitalInStreetFlow(): Flow<Double?>

    @Query("SELECT SUM(amountPaid) FROM loan_installments WHERE isDeleted = 0")
    fun getTotalCollectedFlow(): Flow<Double?>
}
