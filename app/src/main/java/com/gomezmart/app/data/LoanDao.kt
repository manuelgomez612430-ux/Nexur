package com.gomezmart.app.data

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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertClients(clients: List<LoanClientEntity>)

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
         WHERE l.clientId = c.id AND i.isDeleted = 0 AND l.isDeleted = 0 AND l.status IN ('ACTIVE', 'OVERDUE')) as totalDebt,
        (SELECT COUNT(*) 
         FROM loan_installments i 
         JOIN loans l ON i.loanId = l.id 
         WHERE l.clientId = c.id AND i.isDeleted = 0 AND l.isDeleted = 0 AND l.status IN ('ACTIVE', 'OVERDUE') AND i.dueDate < :now) as totalOverdueCount,
        (SELECT COUNT(*) 
         FROM loan_installments i 
         JOIN loans l ON i.loanId = l.id 
         WHERE l.clientId = c.id AND i.isDeleted = 0 AND l.isDeleted = 0 AND i.status = 'PAID') as totalPaidOnTimeCount,
        (SELECT CASE
            WHEN COUNT(*) = 0 THEN 'NONE'
            WHEN SUM(CASE WHEN COALESCE(fs.isPersonal, l.isPersonal) = 1 THEN 1 ELSE 0 END) = COUNT(*) THEN 'PERSONAL'
            WHEN SUM(CASE WHEN COALESCE(fs.isPersonal, l.isPersonal) = 1 THEN 1 ELSE 0 END) = 0 THEN 'BUDGET'
            ELSE 'MIXED' END
         FROM loans l
         LEFT JOIN loan_funding_sources fs ON fs.loanId = l.id
         WHERE l.clientId = c.id AND l.isDeleted = 0 AND l.status IN ('ACTIVE', 'OVERDUE')) as fundingSource
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
         WHERE l.clientId = c.id AND i.isDeleted = 0 AND l.isDeleted = 0 AND l.status IN ('ACTIVE', 'OVERDUE')) as totalDebt,
        (SELECT COUNT(*) 
         FROM loan_installments i 
         JOIN loans l ON i.loanId = l.id 
         WHERE l.clientId = c.id AND i.isDeleted = 0 AND l.isDeleted = 0 AND l.status IN ('ACTIVE', 'OVERDUE') AND i.dueDate < :now) as totalOverdueCount,
        (SELECT COUNT(*) 
         FROM loan_installments i 
         JOIN loans l ON i.loanId = l.id 
         WHERE l.clientId = c.id AND i.isDeleted = 0 AND l.isDeleted = 0 AND i.status = 'PAID') as totalPaidOnTimeCount,
        (SELECT CASE
            WHEN COUNT(*) = 0 THEN 'NONE'
            WHEN SUM(CASE WHEN COALESCE(fs.isPersonal, l.isPersonal) = 1 THEN 1 ELSE 0 END) = COUNT(*) THEN 'PERSONAL'
            WHEN SUM(CASE WHEN COALESCE(fs.isPersonal, l.isPersonal) = 1 THEN 1 ELSE 0 END) = 0 THEN 'BUDGET'
            ELSE 'MIXED' END
         FROM loans l
         LEFT JOIN loan_funding_sources fs ON fs.loanId = l.id
         WHERE l.clientId = c.id AND l.isDeleted = 0 AND l.status IN ('ACTIVE', 'OVERDUE')) as fundingSource
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
    suspend fun insertLoans(loans: List<LoanEntity>)

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

    @Query("SELECT * FROM loan_installments WHERE loanId = :loanId")
    suspend fun getAllInstallmentsByLoanSync(loanId: String): List<LoanInstallmentEntity>

    @Query("SELECT * FROM loan_installments WHERE loanId = :loanId AND isDeleted = 0")
    fun getInstallmentsByLoanTransaction(loanId: String): List<LoanInstallmentEntity>

    @Query("SELECT * FROM loan_installments WHERE id = :id LIMIT 1")
    suspend fun getInstallmentById(id: String): LoanInstallmentEntity?

    @Query("SELECT * FROM loans WHERE clientId = :clientId AND isDeleted = 0")
    suspend fun getLoansByClientSync(clientId: String): List<LoanEntity>

    @Query("SELECT * FROM loans WHERE clientId = :clientId AND isDeleted = 0")
    fun getLoansByClientSyncNoSuspend(clientId: String): List<LoanEntity>

    @Query("SELECT * FROM loan_installments WHERE loanId = :loanId AND isDeleted = 0")
    fun getInstallmentsByLoanSyncNoSuspend(loanId: String): List<LoanInstallmentEntity>

    @Query("SELECT * FROM loan_installments WHERE isDeleted = 0 AND status != 'PAID' AND dueDate <= :timeLimit")
    suspend fun getPendingCollections(timeLimit: Long): List<LoanInstallmentEntity>

    @Query("SELECT * FROM loan_installments WHERE isDeleted = 0 AND status != 'PAID' ORDER BY CASE WHEN dueDate > 0 THEN dueDate ELSE 9999999999999 END ASC")
    fun getAllPendingInstallmentsFlow(): Flow<List<LoanInstallmentEntity>>

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

    @Query("DELETE FROM loan_installments WHERE loanId = :loanId AND status != 'PAID'")
    fun deleteUnpaidInstallmentsTransaction(loanId: String)

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

    @Query("SELECT * FROM loan_expenses WHERE isDeleted = 0 AND isPersonal = 0 AND timestamp >= :start AND timestamp <= :end")
    fun getExpensesInRange(start: Long, end: Long): Flow<List<LoanExpenseEntity>>

    @Query("SELECT SUM(amount) FROM loan_expenses WHERE isDeleted = 0 AND isPersonal = 0")
    fun getTotalExpensesFlow(): Flow<Double?>

    // --- PAYMENTS ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPayment(payment: LoanPaymentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPayments(payments: List<LoanPaymentEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPaymentSync(payment: LoanPaymentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertPaymentTransaction(payment: LoanPaymentEntity)

    @Delete
    suspend fun deletePayment(payment: LoanPaymentEntity)

    @Update
    suspend fun updatePayment(payment: LoanPaymentEntity)

    @Query("SELECT * FROM loan_payments WHERE id = :id LIMIT 1")
    suspend fun getPaymentById(id: String): LoanPaymentEntity?

    /**
     * Deshace (anula) un cobro registrado por error.
     * - Marca el pago como eliminado (para que se sincronice el borrado con la nube).
     * - Recalcula las cuotas afectadas a partir de los pagos vigentes (PENDING / PARTIAL / PAID).
     * - Reactiva el préstamo si estaba marcado como PAID.
     */
    @Transaction
    suspend fun reversePayment(paymentId: String): Boolean {
        val payment = getPaymentById(paymentId) ?: return false
        if (payment.isDeleted) return false
        // No se pueden anular aumentos de deuda (type == 1)
        if (payment.type == 1) return false

        // 1) Marcar el pago como eliminado (soft delete) para propagarlo a la nube
        updatePayment(payment.copy(isDeleted = true, isSynced = false))

        // 2) Recalcular las cuotas del préstamo a partir de los pagos que siguen vigentes.
        //    Esto garantiza que el saldo, el importe pagado y el estado sean siempre
        //    consistentes, aunque el pago apuntara a una cuota anterior ya consolidada.
        reconcilePaymentsForLoan(payment.loanId)

        return true
    }

    /**
     * Deshace varios cobros a la vez (por ejemplo, un cobro que fue registrado
     * partido en varios registros). Devuelve el monto total revertido.
     */
    @Transaction
    suspend fun reversePayments(paymentIds: List<String>): Double {
        var reverted = 0.0
        paymentIds.forEach { id ->
            val payment = getPaymentById(id) ?: return@forEach
            if (payment.isDeleted || payment.type == 1) return@forEach
            reverted += payment.amount + payment.lateFeeAmount
            reversePayment(id)
        }
        return reverted
    }

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

    // --- ORIGEN DE FONDOS (ALMACENAMIENTO PROTEGIDO) ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFundingSource(source: LoanFundingSourceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFundingSources(sources: List<LoanFundingSourceEntity>)

    @Query("SELECT * FROM loan_funding_sources WHERE loanId = :loanId LIMIT 1")
    suspend fun getFundingSource(loanId: String): LoanFundingSourceEntity?

    @Query("SELECT * FROM loan_funding_sources")
    suspend fun getAllFundingSources(): List<LoanFundingSourceEntity>

    @Query("DELETE FROM loan_funding_sources")
    suspend fun deleteAllFundingSources()

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

    @Query("SELECT SUM(amount - amountPaid) FROM loan_installments WHERE isDeleted = 0 AND loanId IN (SELECT id FROM loans WHERE clientId = :clientId AND isDeleted = 0 AND status IN ('ACTIVE', 'OVERDUE'))")
    fun getTotalDebtByClientFlow(clientId: String): Flow<Double?>

    @Query("SELECT SUM(amount - amountPaid) FROM loan_installments WHERE isDeleted = 0 AND loanId IN (SELECT id FROM loans WHERE clientId = :clientId AND isDeleted = 0 AND status IN ('ACTIVE', 'OVERDUE'))")
    suspend fun getTotalDebtByClientOnce(clientId: String): Double?

    /**
     * Recalcula y consolida el plan de pagos de un préstamo a partir de sus pagos.
     * Además respeta el ORIGEN DE FONDOS protegido (tabla loan_funding_sources).
     */
    @Transaction
    suspend fun reconcilePaymentsForLoan(loanId: String) {
        val loan = getLoanById(loanId) ?: return
        val payments = getPaymentsByLoanSync(loanId).filter { !it.isDeleted && it.type != 1 }
        val totalPaid = payments.sumOf { it.amount }

        val allInsts = getInstallmentsByLoanSync(loanId).filter { !it.isDeleted }
        if (allInsts.isEmpty()) return

        // 1) CONSOLIDAR EL PLAN DE PAGOS
        //    a) Si el préstamo declara N cuotas pero solo hay M activas (la nube dejó
        //       cuotas huérfanas), se recuperan las eliminadas hasta completar N.
        //    b) Si TODAS las cuotas activas tienen el mismo número significa que el
        //       plan se generó varias veces: se renumeran 1..N para recuperarlo.
        //    c) Si hay números repetidos puntualmente, se conserva la mejor de cada uno.
        val expectedCount = loan.installmentsCount.coerceAtLeast(1)
        var working = allInsts
        if (working.size < expectedCount) {
            val recoverable = getAllInstallmentsByLoanSync(loanId)
                .filter { it.isDeleted }
                .sortedWith(compareBy({ it.dueDate }, { it.amountPaid }))
                .take(expectedCount - working.size)
            recoverable.forEach { updateInstallment(it.copy(isDeleted = false, isSynced = false)) }
            working = working + recoverable
        }

        val distinctNumbers = working.map { it.installmentNumber }.distinct()
        val consolidated: List<LoanInstallmentEntity> = if (distinctNumbers.size == 1 && working.size > 1) {
            working.sortedWith(compareBy({ it.dueDate }, { it.amountPaid })).mapIndexed { index, inst ->
                inst.copy(installmentNumber = index + 1)
            }
        } else {
            val byNumber = working.groupBy { it.installmentNumber }
            val duplicatedIds = mutableSetOf<String>()
            byNumber.forEach { (_, group) ->
                if (group.size > 1) {
                    val keeper = group.maxWithOrNull(
                        compareBy<LoanInstallmentEntity> { it.amountPaid }
                            .thenBy { it.amount }
                            .thenBy { it.dueDate }
                    )
                    group.forEach { inst -> if (inst.id != keeper?.id) duplicatedIds.add(inst.id) }
                }
            }
            if (duplicatedIds.isNotEmpty()) {
                working.filter { it.id in duplicatedIds }.forEach {
                    updateInstallment(it.copy(isDeleted = true, isSynced = false))
                }
            }
            working.filter { it.id !in duplicatedIds }
        }

        val installments = consolidated.sortedBy { it.installmentNumber }
        if (installments.isEmpty()) return

        val targetTotal = if (loan.totalToPay > 0.0) loan.totalToPay else loan.amount

        // 2) NORMALIZAR IMPORTES: si la suma de las cuotas no cuadra con el total del
        //    préstamo, se redistribuyen con precisión (el último absorbe la diferencia).
        val currentSum = installments.sumOf { it.amount }
        val normalized = if (Math.abs(currentSum - targetTotal) > 0.01) {
            val base = targetTotal / installments.size
            installments.mapIndexed { index, inst ->
                if (index == installments.lastIndex) {
                    inst.copy(amount = targetTotal - base * (installments.size - 1))
                } else {
                    inst.copy(amount = base)
                }
            }
        } else {
            installments
        }

        // 3) DISTRIBUIR LO PAGADO entre las cuotas en orden, respetando su importe.
        var remainingPaidToDistribute = totalPaid

        for (inst in normalized) {
            val paidForInst = Math.min(inst.amount, remainingPaidToDistribute.coerceAtLeast(0.0))
            remainingPaidToDistribute -= paidForInst

            val newStatus = if (paidForInst >= inst.amount - 0.01) "PAID" else if (paidForInst > 0.0) "PARTIAL" else "PENDING"
            updateInstallment(inst.copy(amountPaid = paidForInst, status = newStatus, isSynced = false))
        }

        // 4) ESTADO DEL PRÉSTAMO: pagado si se cubrió todo, activo si ya no está cancelado.
        val newStatus = when {
            totalPaid >= targetTotal - 0.01 && loan.status != "PAID" -> "PAID"
            totalPaid < targetTotal - 0.01 && loan.status == "PAID" -> "ACTIVE"
            else -> loan.status
        }
        if (newStatus != loan.status) {
            updateLoan(loan.copy(status = newStatus, isSynced = false))
        }
    }

    @Transaction
    suspend fun sanitizeAllLoans() {
        val loans = getAllLoansOnce()

        // CONGELAR EL ORIGEN DE FONDOS: se crea el registro protegido con el valor
        // actual de cada préstamo. A partir de aquí, ninguna sincronización con la
        // nube podrá cambiar el origen que el usuario configuró.
        loans.filter { !it.isDeleted }.forEach { l ->
            val existing = getFundingSource(l.id)
            if (existing == null) {
                insertFundingSource(
                    LoanFundingSourceEntity(
                        loanId = l.id,
                        isPersonal = l.isPersonal,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        }
        val byClient = loans.groupBy { it.clientId }
        for ((_, clientLoans) in byClient) {
            val active = clientLoans.filter { !it.isDeleted && (it.status == "ACTIVE" || it.status == "OVERDUE") }
            if (active.isNotEmpty()) {
                val primary = active.minByOrNull { it.createdAt } ?: active.first()

                active.filter { it.id != primary.id }.forEach { old ->
                    updateLoan(old.copy(status = "REFINANCED", isDeleted = true, isSynced = false))
                }

                val correctTotal = if (primary.interestRate > 0.0) primary.totalToPay else primary.amount
                
                val sanitizedLoan = primary.copy(
                    totalToPay = correctTotal,
                    lateFeeAmount = 0.0,
                    isDeleted = false,
                    isSynced = false
                )
                updateLoan(sanitizedLoan)

                reconcilePaymentsForLoan(primary.id)
            }
        }
    }

    /**
     * Fusiona los pagos que fueron partidos en varios registros dentro de la misma
     * operación (mismo préstamo y misma marca de tiempo). Esto evita que el historial
     * muestre varios abonos diminutos en lugar de un único cobro real.
     */
    @Transaction
    suspend fun consolidateSplitPayments() {
        val payments = getAllPaymentsOnce()
        // Se agrupan por préstamo y segundo: los pagos partidos en la misma operación
        // se crean con milisegundos ligeramente distintos.
        payments.groupBy { it.loanId to (it.timestamp / 1000L) }.forEach { (_, group) ->
            if (group.size > 1) {
                val keeper = group.first()
                val totalAmount = group.sumOf { it.amount }
                val totalFee = group.sumOf { it.lateFeeAmount }
                group.drop(1).forEach { updatePayment(it.copy(isDeleted = true, isSynced = false)) }
                updatePayment(keeper.copy(
                    amount = totalAmount,
                    lateFeeAmount = totalFee,
                    isSynced = false
                ))
            }
        }
    }

    @Transaction
    suspend fun purgeAllClientsLoans() {
        val clients = getAllClientsOnce()
        for (client in clients) {
            purgeAndConsolidateClientLoans(client.id)
        }
    }

    @androidx.room.Transaction
    suspend fun purgeAndConsolidateClientLoans(clientId: String) {
        val loans = getLoansByClientSync(clientId)
        val activeLoans = loans.filter { !it.isDeleted && (it.status == "ACTIVE" || it.status == "OVERDUE") }
        if (activeLoans.isNotEmpty()) {
            val primary = activeLoans.maxByOrNull { it.createdAt } ?: activeLoans.first()
            
            activeLoans.filter { it.id != primary.id }.forEach { old ->
                updateLoan(old.copy(status = "REFINANCED", isDeleted = true, isSynced = false))
            }

            val correctedTotal = if (primary.interestRate <= 0.0) primary.amount else primary.totalToPay
            val correctedLoan = primary.copy(
                totalToPay = correctedTotal,
                isDeleted = false,
                isSynced = false
            )
            updateLoan(correctedLoan)

            val allInsts = getInstallmentsByLoanSync(primary.id)
            val paidInsts = allInsts.filter { it.status == "PAID" && !it.isDeleted }
            val paidTotal = paidInsts.sumOf { it.amountPaid }

            deleteUnpaidInstallmentsTransaction(primary.id)

            val remainingTotal = (correctedTotal - paidTotal).coerceAtLeast(0.0)

            if (primary.installmentsCount <= 1 || primary.frequency == "NONE") {
                val singleInst = LoanInstallmentEntity(
                    loanId = primary.id,
                    installmentNumber = paidInsts.size + 1,
                    amount = remainingTotal,
                    dueDate = 0L
                )
                insertInstallments(listOf(singleInst))
            } else {
                val remainingCount = (primary.installmentsCount - paidInsts.size).coerceAtLeast(1)
                val quotaAmt = remainingTotal / remainingCount
                val schedule = mutableListOf<LoanInstallmentEntity>()
                val cal = java.util.Calendar.getInstance().apply {
                    timeInMillis = if (primary.startDate > 0L) primary.startDate else System.currentTimeMillis()
                }
                cal.set(java.util.Calendar.HOUR_OF_DAY, 23)
                cal.set(java.util.Calendar.MINUTE, 59)
                cal.set(java.util.Calendar.SECOND, 59)
                cal.set(java.util.Calendar.MILLISECOND, 999)

                for (i in 1..remainingCount) {
                    if (i > 1) {
                        when(primary.frequency) {
                            "DAILY" -> cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
                            "WEEKLY" -> cal.add(java.util.Calendar.WEEK_OF_YEAR, 1)
                            "MONTHLY" -> cal.add(java.util.Calendar.MONTH, 1)
                        }
                    }
                    schedule.add(LoanInstallmentEntity(
                        loanId = primary.id,
                        installmentNumber = paidInsts.size + i,
                        amount = quotaAmt,
                        dueDate = cal.timeInMillis
                    ))
                }
                if (schedule.isNotEmpty()) {
                    insertInstallments(schedule)
                }
            }
        }
    }

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
