package com.naxor.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "expenses")
data class ExpenseEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val concepto: String,
    val monto: Double,
    val categoria: String,
    val fecha: Long = System.currentTimeMillis(),
    val esFijo: Boolean = false // Nuevo campo para punto de equilibrio
)
