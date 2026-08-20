package com.naxor.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "debtors")
data class DebtorEntity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val nombre: String = "",
    val telefono: String = "",
    var deudaTotal: Double = 0.0,
    var fechaCobro: Long = 0L, // 0 si no está programado
    var ultimaActualizacion: Long = System.currentTimeMillis(),
    var isSynced: Boolean = false,
    var isDeleted: Boolean = false
)

@Entity(tableName = "debts")
data class DebtDetailEntity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val debtorId: String = "",
    val concepto: String = "",
    val monto: Double = 0.0,
    var fecha: Long = System.currentTimeMillis(),
    var isSynced: Boolean = false,
    var isDeleted: Boolean = false
)
