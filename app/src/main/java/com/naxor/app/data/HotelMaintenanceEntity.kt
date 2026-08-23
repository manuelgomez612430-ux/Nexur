package com.naxor.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "hotel_maintenance")
data class HotelMaintenanceEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val roomId: String,
    val description: String,
    val photoPath: String? = null,
    val status: String = "PENDING", // PENDING, FIXED
    val reportedBy: String? = "Personal de Limpieza",
    val timestamp: Long = System.currentTimeMillis(),
    var isSynced: Boolean = false,
    var isDeleted: Boolean = false
)
