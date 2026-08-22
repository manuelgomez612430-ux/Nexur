package com.naxor.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "hotel_rooms")
data class HotelRoomEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val number: String,
    val floor: Int = 1, // Nuevo campo para separar por pisos
    val type: String, // SIMPLE, DOUBLE, MATRIMONIAL, SUITE
    var status: String = "FREE", // FREE, OCCUPIED, DIRTY, MAINTENANCE
    val baseRate: Double,
    val description: String? = null,
    var lastCleaned: Long = System.currentTimeMillis(),
    var isSynced: Boolean = false,
    var isDeleted: Boolean = false
)
