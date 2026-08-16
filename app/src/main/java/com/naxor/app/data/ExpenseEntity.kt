package com.naxor.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "expenses")
data class ExpenseEntity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val concepto: String,
    val monto: Double,
    val categoria: String,
    var fecha: Long = System.currentTimeMillis(),
    val esFijo: Boolean = false, // Nuevo campo para punto de equilibrio
    var isSynced: Boolean = false,
    var isDeleted: Boolean = false
)
