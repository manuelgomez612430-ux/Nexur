package com.naxor.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface LoanDao {
    // --- CLIENTS ---
    @Query("SELECT * FROM loan_clients WHERE isDeleted = 0 ORDER BY name ASC")
    fun getAllClients(): Flow<List<LoanClientEntity>>

    @Query("SELECT * FROM loan_clients WHERE isDeleted = 0")
    suspend fun getAllClientsOnce(): List<LoanClientEntity>

    @Query("SELECT * FROM loan_clients WHERE isDeleted = 0 AND (name LIKE '%' || :query || '%' OR doc LIKE '%' || :query || '%') ORDER BY name ASC")
    fun searchClients(query: String): Flow<List<LoanClientEntity>>

    @Query("SELECT * FROM loan_clients WHERE id = :id LIMIT 1")
    suspend fun getClientById(id: String): LoanClientEntity?

    @Query("SELECT * FROM loan_clients WHERE id = :id LIMIT 1")
    fun getClientByIdFlow(id: String): Flow<LoanClientEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertClient(client: LoanClientEntity)

    @Update
    suspend fun updateClient(client: LoanClientEntity)

    @Delete
    suspend fun deleteClient(client: LoanClientEntity)

    @Query("UPDATE loan_clients SET isDeleted = 1, isSynced = 0 WHERE id = :clientId")
    suspend fun markClientDeleted(clientId: String)

    @Query("UPDATE loans SET isDeleted = 1, isSynced = 0 WHERE clientId = :clientId")
    suspend fun markLoansForClientDeleted(clientId: String)

    @Query("UPDATE loan_installments SET isDeleted = 1, isSynced = 0 WHERE loanId IN (SELECT id FROM loans WHERE clientId = :clientId)")
    suspend fun markInstallmentsForClientDeleted(clientId: String)

    @Query("UPDATE loan_payments SET isDeleted = 1, isSynced = 0 WHERE loanId IN (SELECT id FROM loans WHERE clientId = :clientId)")
    suspend fun markPaymentsForClientDeleted(clientId: String)

    @Transaction
    suspend fun deleteClientFully(clientId: String) {
        markPaymentsForClientDeleted(clientId)
        markInstallmentsForClientDeleted(clientId)
        markLoansForClientDeleted(clientId)
        markClientDeleted(clientId)
    }

    // --- CLIENTS WITH DEBT (REACTIVE) ---
    @Query("""
        SELECT c.*, 
        (SELECT COALESCE(SUM(i.amount - i.amountPaid), 0.0) 
         FROM loan_installments i 
         JOIN loans l ON i.loanId = l.id 
         WHERE l.clientId = c.id AND i.isDeleted = 0 AND l.isDeleted = 0 AND l.status != 'PAID') as totalDebt,
        (SELECT COUNT(*) 
         FROM loan_installments i 
         JOIN loans l ON i.loanId = l.id 
         WHERE l.clientId = c.id AND i.isDeleted = 0 AND l.isDeleted = 0 AND i.status != 'PAID' AND i.dueDate < :now) as totalOverdueCount,
        (SELECT COUNT(*) 
         FROM loan_installments i 
         JOIN loans l ON i.loanId = l.id 
         WHERE l.clientId = c.id AND i.isDeleted = 0 AND l.isDeleted = 0 AND i.status = 'PAID') as totalPaidOnTimeCount
        FROM loan_clients c
        WHERE c.isDeleted = 0
        ORDER BY c.name ASC
    """)
    fun getAllClientsWithDebtFlow(now: Long): Flow<List<LoanClientWithDebt>>

    @Query("""
        SELECT c.*, 
        (SELECT COALESCE(SUM(i.amount - i.amountPaid), 0.0) 
         FROM loan_installments i 
         JOIN loans l ON i.loanId = l.id 
         WHERE l.clientId = c.id AND i.isDeleted = 0 AND l.isDeleted = 0 AND l.status != 'PAID') as totalDebt,
        (SELECT COUNT(*) 
         FROM loan_installments i 
         JOIN loans l ON i.loanId = l.id 
         WHERE l.clientId = c.id AND i.isDeleted = 0 AND l.isDeleted = 0 AND i.status != 'PAID' AND i.dueDate < :now) as totalOverdueCount,
        (SELECT COUNT(*) 
         FROM loan_installments i 
         JOIN loans l ON i.loanId = l.id 
         WHERE l.clientId = c.id AND i.isDeleted = 0 AND l.isDeleted = 0 AND i.status = 'PAID') as totalPaidOnTimeCount
        FROM loan_clients c
        WHERE c.isDeleted = 0 AND (c.name LIKE '%' || :query || '%' OR c.doc LIKE '%' || :query || '%')
        ORDER BY c.name ASC
    """)
    fun searchClientsWithDebtFlow(query: String, now: Long): Flow<List<LoanClientWithDebt>>

    // --- LOANS ---
    @Query("SELECT * FROM loans WHERE isDeleted = 0")
    fun getAllLoans(): Flow<List<LoanEntity>>

    @Query("SELECT * FROM loans WHERE isDeleted = 0")
    suspend fun getAllLoansOnce(): List<LoanEntity>

    @Query("SELECT * FROM loans WHERE clientId = :clientId AND isDeleted = 0")
    fun getLoansByClient(clientId: String): Flow<List<LoanEntity>>

    @Query("SELECT * FROM loans WHERE id = :id LIMIT 1")
    suspend fun getLoanById(id: String): LoanEntity?

    @Query("SELECT * FROM loans WHERE id = :id LIMIT 1")
    suspend fun getLoanByIdSync(id: String): LoanEntity?

    @Query("SELECT * FROM loans WHERE id = :id LIMIT 1")
    fun getLoanByIdTransaction(id: String): LoanEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLoan(loan: LoanEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertLoanTransaction(loan: LoanEntity)

    @Update
    suspend fun updateLoan(loan: LoanEntity)

    @Update
    suspend fun updateLoanSync(loan: LoanEntity)

    @Update
    fun updateLoanTransaction(loan: LoanEntity)

    @Delete
    suspend fun deleteLoan(loan: LoanEntity)

    // --- INSTALLMENTS ---
    @Query("SELECT * FROM loan_installments WHERE loanId = :loanId AND isDeleted = 0 ORDER BY installmentNumber ASC")
    fun getInstallmentsByLoan(loanId: String): Flow<List<LoanInstallmentEntity>>

    @Query("SELECT * FROM loan_installments WHERE loanId = :loanId AND isDeleted = 0")
    suspend fun getInstallmentsByLoanSync(loanId: String): List<LoanInstallmentEntity>

    @Query("SELECT * FROM loan_installments WHERE loanId = :loanId AND isDeleted = 0")
    fun getInstallmentsByLoanTransaction(loanId: String): List<LoanInstallmentEntity>

    @Query("SELECT * FROM loan_installments WHERE id = :id LIMIT 1")
    suspend fun getInstallmentById(id: String): LoanInstallmentEntity?

    @Query("SELECT * FROM loans WHERE clientId = :clientId AND isDeleted = 0")
    suspend fun getLoansByClientSync(clientId: String): List<LoanEntity>

    @Query("SELECT * FROM loan_installments WHERE isDeleted = 0 AND status != 'PAID' AND dueDate <= :timeLimit")
    suspend fun getPendingCollections(timeLimit: Long): List<LoanInstallmentEntity>

    @Query("SELECT * FROM loan_installments WHERE isDeleted = 0 AND dueDate >= :start AND dueDate <= :end")
    fun getInstallmentsInRange(start: Long, end: Long): Flow<List<LoanInstallmentEntity>>

    @Query("SELECT * FROM loan_installments WHERE isDeleted = 0 AND status = 'PAID' AND dueDate >= :start AND dueDate <= :end")
    fun getPaidInstallmentsInRange(start: Long, end: Long): Flow<List<LoanInstallmentEntity>>

    @Query("SELECT * FROM loan_installments WHERE isDeleted = 0 AND amountPaid > 0")
    fun getAllPaymentsFlow(): Flow<List<LoanInstallmentEntity>>

    @Query("SELECT * FROM loan_installments WHERE isDeleted = 0")
    suspend fun getAllInstallmentsOnce(): List<LoanInstallmentEntity>

    @Query("SELECT SUM(lateFeePaid) FROM loan_installments WHERE isDeleted = 0")
    fun getTotalLateFeesFlow(): Flow<Double?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInstallments(installments: List<LoanInstallmentEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertInstallmentsTransaction(installments: List<LoanInstallmentEntity>)

    @Update
    suspend fun updateInstallment(installment: LoanInstallmentEntity)

    @Update
    fun updateInstallmentTransaction(installment: LoanInstallmentEntity)

    @Query("UPDATE loan_installments SET isSynced = 1 WHERE id = :id")
    suspend fun markInstallmentSynced(id: String)

    @Query("UPDATE loans SET isSynced = 1 WHERE id = :id")
    suspend fun markLoanSynced(id: String)

    @Query("UPDATE loan_clients SET isSynced = 1 WHERE id = :id")
    suspend fun markLoanClientSynced(id: String)

    @Query("UPDATE loan_payments SET isSynced = 1 WHERE id = :id")
    suspend fun markLoanPaymentSynced(id: String)

    @Query("UPDATE loan_expenses SET isSynced = 1 WHERE id = :id")
    suspend fun markLoanExpenseSynced(id: String)

    @Query("UPDATE loan_budgets SET isSynced = 1 WHERE id = :id")
    suspend fun markLoanBudgetSynced(id: String)

    @Delete
    suspend fun deleteInstallment(installment: LoanInstallmentEntity)

    // --- EXPENSES ---
    @Query("SELECT * FROM loan_expenses WHERE isDeleted = 0 ORDER BY timestamp DESC")
    fun getAllExpenses(): Flow<List<LoanExpenseEntity>>

    @Query("SELECT * FROM loan_expenses WHERE isDeleted = 0")
    suspend fun getAllLoanExpensesOnce(): List<LoanExpenseEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExpense(expense: LoanExpenseEntity)

    @Delete
    suspend fun deleteExpense(expense: LoanExpenseEntity)

    @Update
    suspend fun updateExpense(expense: LoanExpenseEntity)

    @Query("SELECT * FROM loan_expenses WHERE isDeleted = 0 AND timestamp >= :start AND timestamp <= :end")
    fun getExpensesInRange(start: Long, end: Long): Flow<List<LoanExpenseEntity>>

    @Query("SELECT SUM(amount) FROM loan_expenses WHERE isDeleted = 0")
    fun getTotalExpensesFlow(): Flow<Double?>

    // --- PAYMENTS ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPayment(payment: LoanPaymentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPaymentSync(payment: LoanPaymentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertPaymentTransaction(payment: LoanPaymentEntity)

    @Delete
    suspend fun deletePayment(payment: LoanPaymentEntity)

    @Update
    suspend fun updatePayment(payment: LoanPaymentEntity)

    @Query("SELECT * FROM loan_payments WHERE loanId = :loanId AND isDeleted = 0 ORDER BY timestamp DESC")
    fun getPaymentsByLoan(loanId: String): Flow<List<LoanPaymentEntity>>

    @Query("SELECT * FROM loan_payments WHERE loanId = :loanId AND isDeleted = 0 ORDER BY timestamp DESC")
    suspend fun getPaymentsByLoanSync(loanId: String): List<LoanPaymentEntity>

    @Query("SELECT * FROM loan_payments WHERE loanId IN (SELECT id FROM loans WHERE clientId = :clientId AND isDeleted = 0) AND isDeleted = 0 ORDER BY timestamp DESC")
    fun getAllPaymentsByClientFlow(clientId: String): Flow<List<LoanPaymentEntity>>

    @Query("SELECT * FROM loan_payments WHERE isDeleted = 0")
    suspend fun getAllPaymentsOnce(): List<LoanPaymentEntity>

    @Query("SELECT * FROM loan_payments WHERE isDeleted = 0 AND timestamp >= :start AND timestamp <= :end")
    fun getPaymentsInRange(start: Long, end: Long): Flow<List<LoanPaymentEntity>>

    @Query("SELECT * FROM loan_payments WHERE isDeleted = 0")
    fun getAllPaymentsFlowV2(): Flow<List<LoanPaymentEntity>>

    @Query("SELECT * FROM loan_clients WHERE isDeleted = 1")
    suspend fun getDeletedClients(): List<LoanClientEntity>

    @Query("SELECT * FROM loans WHERE isDeleted = 1")
    suspend fun getDeletedLoans(): List<LoanEntity>

    @Query("SELECT * FROM loan_installments WHERE isDeleted = 1")
    suspend fun getDeletedInstallments(): List<LoanInstallmentEntity>

    @Query("SELECT * FROM loan_expenses WHERE isDeleted = 1")
    suspend fun getDeletedLoanExpenses(): List<LoanExpenseEntity>

    @Query("SELECT * FROM loan_payments WHERE isDeleted = 1")
    suspend fun getDeletedPayments(): List<LoanPaymentEntity>

    // --- UNSYNCED ---
    @Query("SELECT * FROM loan_clients WHERE isSynced = 0 AND isDeleted = 0")
    suspend fun getUnsyncedClients(): List<LoanClientEntity>

    @Query("SELECT * FROM loans WHERE isSynced = 0 AND isDeleted = 0")
    suspend fun getUnsyncedLoans(): List<LoanEntity>

    @Query("SELECT * FROM loan_installments WHERE isSynced = 0 AND isDeleted = 0")
    suspend fun getUnsyncedInstallments(): List<LoanInstallmentEntity>

    @Query("SELECT * FROM loan_expenses WHERE isSynced = 0 AND isDeleted = 0")
    suspend fun getUnsyncedLoanExpenses(): List<LoanExpenseEntity>

    @Query("SELECT * FROM loan_payments WHERE isSynced = 0 AND isDeleted = 0")
    suspend fun getUnsyncedPayments(): List<LoanPaymentEntity>

    @Query("SELECT (SELECT COUNT(*) FROM loan_clients WHERE isSynced = 0 AND isDeleted = 0) + (SELECT COUNT(*) FROM loans WHERE isSynced = 0 AND isDeleted = 0) + (SELECT COUNT(*) FROM loan_installments WHERE isSynced = 0 AND isDeleted = 0) + (SELECT COUNT(*) FROM loan_expenses WHERE isSynced = 0 AND isDeleted = 0) + (SELECT COUNT(*) FROM loan_payments WHERE isSynced = 0 AND isDeleted = 0)")
    fun getUnsyncedCount(): Flow<Int>

    // --- STATS ---
    @Query("SELECT SUM(amount) FROM loans WHERE isDeleted = 0 AND status != 'PAID'")
    fun getCapitalInStreetFlow(): Flow<Double?>

    @Query("SELECT * FROM loan_installments WHERE isDeleted = 0")
    fun getAllInstallmentsFlow(): Flow<List<LoanInstallmentEntity>>

    @Query("SELECT SUM(amountPaid) FROM loan_installments WHERE isDeleted = 0")
    fun getTotalCollectedFlow(): Flow<Double?>

    @Query("SELECT SUM(amount - amountPaid) FROM loan_installments WHERE isDeleted = 0")
    fun getTotalOutstandingBalanceFlow(): Flow<Double?>

    @Query("SELECT SUM(amount - amountPaid) FROM loan_installments WHERE isDeleted = 0 AND loanId IN (SELECT id FROM loans WHERE clientId = :clientId AND isDeleted = 0 AND status != 'PAID')")
    fun getTotalDebtByClientFlow(clientId: String): Flow<Double?>

    @Query("SELECT SUM(amount - amountPaid) FROM loan_installments WHERE isDeleted = 0 AND loanId IN (SELECT id FROM loans WHERE clientId = :clientId AND isDeleted = 0 AND status != 'PAID')")
    suspend fun getTotalDebtByClientOnce(clientId: String): Double?

    // --- BUDGET ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBudget(budget: LoanBudgetEntity)

    @Query("SELECT * FROM loan_budgets WHERE isDeleted = 0 ORDER BY timestamp DESC LIMIT 1")
    fun getLatestBudget(): Flow<LoanBudgetEntity?>

    @Query("SELECT * FROM loan_budgets WHERE isDeleted = 0 ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestBudgetOnce(): LoanBudgetEntity?

    @Query("SELECT * FROM loan_budgets WHERE isSynced = 0 AND isDeleted = 0")
    suspend fun getUnsyncedBudgets(): List<LoanBudgetEntity>

    @Update
    suspend fun updateBudget(budget: LoanBudgetEntity)
}
