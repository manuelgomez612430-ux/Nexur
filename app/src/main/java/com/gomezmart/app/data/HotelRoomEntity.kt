package com.gomezmart.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "hotel_rooms", indices = [
    androidx.room.Index("floor"),
    androidx.room.Index("status"),
    androidx.room.Index("isDeleted")
])
data class HotelRoomEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val number: String = "",
    val floor: Int = 1, // Nuevo campo para separar por pisos
    val type: String = "SIMPLE", // SIMPLE, DOUBLE, MATRIMONIAL, SUITE
    var status: String = "FREE", // FREE, OCCUPIED, DIRTY, MAINTENANCE, RESERVED
    val baseRate: Double = 0.0,
    val description: String? = null, // Descripción Global / General
    val specificDescription: String? = null, // Descripción propia/específica
    val photoUrl: String? = null,
    val photoPath: String? = null,
    var lastCleaned: Long = System.currentTimeMillis(),
    var isSynced: Boolean = false,
    var isDeleted: Boolean = false
)
