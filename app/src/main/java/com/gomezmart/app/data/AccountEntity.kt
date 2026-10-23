package com.gomezmart.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String = "",          // e.g., "Yape Principal", "BCP Ahorros", "Bolsillo Efectivo"
    val bankName: String = "",      // e.g., "Yape", "BCP", "BBVA", "Interbank", "Efectivo", "Otro"
    val accountNumber: String = "", // e.g., last 4 digits or phone number
    var balance: Double = 0.0,      // Current balance
    val accountType: String = "BANK", // "BANK", "WALLET", "CASH", "PERSONAL"
    val origin: String = "BUSINESS",  // "BUSINESS" (Presupuesto) vs "PERSONAL" (Bolsillo Personal)
    val updatedAt: Long = System.currentTimeMillis(),
    var isSynced: Boolean = false,
    var isDeleted: Boolean = false
)
