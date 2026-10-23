package com.gomezmart.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Registro dedicado del ORIGEN DE FONDOS elegido por el usuario para un préstamo.
 *
 * Este dato se guarda en una tabla APARTE de `loans` de forma deliberada:
 * la tabla `loans` se sincroniza con la nube y puede recibir versiones antiguas
 * que revertirían el origen configurado. Esta tabla NUNCA se sobrescribe por la
 * sincronización, por lo que la elección del usuario es permanente.
 */
@Entity(tableName = "loan_funding_sources")
data class LoanFundingSourceEntity(
    @PrimaryKey
    val loanId: String,
    val isPersonal: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
)
