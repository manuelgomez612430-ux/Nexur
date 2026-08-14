package com.naxor.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cash_sessions")
data class CashSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val startTime: Long = System.currentTimeMillis(),
    var endTime: Long? = null,
    val initialAmount: Double,
    var totalSales: Double = 0.0,
    var totalExpenses: Double = 0.0,
    var actualAmount: Double? = null, // Lo que el usuario cuenta físicamente
    var note: String? = null,
    var isOpen: Boolean = true
)
