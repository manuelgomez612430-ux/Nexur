package com.naxor.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "hotel_charges")
data class HotelChargeEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val bookingId: String,
    val concept: String,
    val amount: Double,
    val timestamp: Long = System.currentTimeMillis(),
    var isSynced: Boolean = false,
    var isDeleted: Boolean = false
)
