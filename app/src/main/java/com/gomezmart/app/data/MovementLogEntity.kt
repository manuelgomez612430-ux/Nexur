package com.gomezmart.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "movement_logs")
data class MovementLogEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val type: String = "", // PRODUCT_CREATED, PRODUCT_UPDATED, PRODUCT_DELETED, SALE, EXPENSE
    val title: String = "",
    val description: String = "",
    val value: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val colorHex: String = "",
    val iconRes: Int = 0,
    // Referencia opcional al registro original (ej: id del pago) para poder deshacer la operación
    val referenceId: String? = null,
    var isSynced: Boolean = false,
    var isDeleted: Boolean = false
)
