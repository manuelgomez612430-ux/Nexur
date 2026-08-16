package com.naxor.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "movement_logs")
data class MovementLogEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val type: String, // PRODUCT_CREATED, PRODUCT_UPDATED, PRODUCT_DELETED, SALE, EXPENSE
    val title: String,
    val description: String,
    val value: String,
    val timestamp: Long = System.currentTimeMillis(),
    val colorHex: String,
    val iconRes: Int,
    var isSynced: Boolean = false
)
