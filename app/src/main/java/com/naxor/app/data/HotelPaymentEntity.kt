package com.naxor.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "hotel_payments")
data class HotelPaymentEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val bookingId: String,
    val amount: Double,
    val timestamp: Long = System.currentTimeMillis(),
    var isSynced: Boolean = false,
    var isDeleted: Boolean = false
)
