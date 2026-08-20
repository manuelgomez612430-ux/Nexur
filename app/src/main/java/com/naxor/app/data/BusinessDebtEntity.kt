package com.naxor.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.*

@Entity(tableName = "business_debts")
data class BusinessDebtEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val acreedor: String = "", // A quién le debemos (Persona, Banco, Proveedor)
    val concepto: String = "", // Por qué le debemos (Préstamo, Mercadería, Alquiler)
    var montoTotal: Double = 0.0,
    var montoPagado: Double = 0.0,
    var fechaVencimiento: Long = 0L,
    val categoria: String = "VARIOS", // PRESTAMO, MERCADERIA, SERVICIOS
    var isPaid: Boolean = false,
    var timestamp: Long = System.currentTimeMillis(),
    var isSynced: Boolean = false,
    var isDeleted: Boolean = false
)
