package com.naxor.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "debtors")
data class DebtorEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val nombre: String,
    val telefono: String,
    var deudaTotal: Double,
    val ultimaActualizacion: Long = System.currentTimeMillis(),
    val isSynced: Boolean = true
)

@Entity(tableName = "debts")
data class DebtDetailEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val debtorId: Int,
    val concepto: String,
    val monto: Double,
    val fecha: Long = System.currentTimeMillis(),
    val isSynced: Boolean = true
)
