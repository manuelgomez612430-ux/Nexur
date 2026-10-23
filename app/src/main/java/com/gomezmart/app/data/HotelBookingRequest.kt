package com.gomezmart.app.data

import com.google.firebase.Timestamp

data class HotelBookingRequest(
    val id: String = "",
    val roomId: String = "",
    val roomName: String = "",
    val guestName: String = "",
    val guestDoc: String = "",
    val guestPhone: String = "",
    val guestOrigin: String = "",
    val guestNationality: String = "NATIONAL",
    val stayNights: Int = 1,
    val bookingDate: Long = 0L,
    val status: String = "PENDING", // PENDING, ACCEPTED, REJECTED
    val buyerId: String = "",
    val timestamp: Any? = null
)
