package com.naxor.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "loan_clients")
data class LoanClientEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val doc: String = "",
    val phone: String = "",
    val address: String? = null,
    val score: Int = 100, // Score de puntualidad 0-100
    val photoPath: String? = null,
    val docPhotoPath: String? = null,
    var isSynced: Boolean = false,
    var isDeleted: Boolean = false
)

@Entity(tableName = "loans")
data class LoanEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val clientId: String = "",
    val amount: Double = 0.0, // Capital inicial
    val interestRate: Double = 0.0, // Porcentaje (ej: 20%)
    val totalToPay: Double = 0.0, // Capital + Interés
    val installmentsCount: Int = 0, // Número de cuotas
    val frequency: String = "DAILY", // DAILY, WEEKLY, BIWEEKLY, MONTHLY
    val startDate: Long = System.currentTimeMillis(),
    var status: String = "ACTIVE", // ACTIVE, PAID, OVERDUE, REFINANCED
    val lateFeeAmount: Double = 0.0, // Monto fijo por día de mora
    val graceDays: Int = 0, // Días de gracia antes de cobrar mora
    val collateralDescription: String? = null,
    val collateralPhotoPath: String? = null,
    var isSynced: Boolean = false,
    var isDeleted: Boolean = false
)

@Entity(tableName = "loan_installments")
data class LoanInstallmentEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val loanId: String = "",
    val installmentNumber: Int = 0,
    val amount: Double = 0.0,
    var amountPaid: Double = 0.0,
    var lateFeePaid: Double = 0.0, // Interés de mora pagado
    val dueDate: Long = 0,
    var status: String = "PENDING", // PENDING, PARTIAL, PAID
    var isSynced: Boolean = false,
    var isDeleted: Boolean = false
)

@Entity(tableName = "loan_expenses")
data class LoanExpenseEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val concept: String = "", // Gasolina, Cobrador, Moto, etc.
    val category: String = "Otros", // NUEVO: Categoría seleccionable
    val amount: Double = 0.0,
    val timestamp: Long = System.currentTimeMillis(),
    var isSynced: Boolean = false,
    var isDeleted: Boolean = false
)

@Entity(tableName = "loan_payments")
data class LoanPaymentEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val loanId: String = "",
    val installmentId: String? = null, // Nulo si es un pago general o aumento
    val amount: Double = 0.0,
    val lateFeeAmount: Double = 0.0,
    val type: Int = 0, // 0 = PAGO (Entrada), 1 = AUMENTO (Salida)
    val timestamp: Long = System.currentTimeMillis(),
    var isSynced: Boolean = false,
    var isDeleted: Boolean = false
)

@Entity(tableName = "loan_budgets")
data class LoanBudgetEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val amount: Double = 0.0,
    val period: String = "MONTHLY", // WEEKLY, MONTHLY
    val startDate: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    var isSynced: Boolean = false,
    var isDeleted: Boolean = false
)
