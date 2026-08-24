package com.naxor.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "hotel_tools")
data class HotelToolEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val code: String, // Código obligatorio
    val name: String,
    val registrationDate: Long = System.currentTimeMillis(),
    val maxUsageMonths: Int = 0, // 0 = sin límite
    val category: String? = null,
    val status: String = "ACTIVE", // ACTIVE, RETIRED
    var isSynced: Boolean = false,
    var isDeleted: Boolean = false
)
