package com.naxor.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "hotel_bookings")
data class HotelBookingEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val roomId: String,
    val guestName: String,
    val guestDoc: String,
    val guestPhone: String,
    val guestOrigin: String? = null, // De dónde viene
    val guestNationality: String = "NACIONAL", // NATIONAL o FOREIGNER
    val checkInDate: Long,
    val checkOutDate: Long,
    val arrivalTime: String? = null,
    val deposit: Double = 0.0,
    val initialNightsPaid: Int = 0, // Noches pagadas al momento del check-in
    val totalAmount: Double,
    var status: String = "CONFIRMED", // CONFIRMED, CHECKED_IN, CHECKED_OUT, CANCELLED
    val notes: String? = null,
    var timestamp: Long = System.currentTimeMillis(),
    var isSynced: Boolean = false,
    var isDeleted: Boolean = false
)
