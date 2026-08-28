package com.naxor.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "hotel_tools")
data class HotelToolEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val code: String = "",
    val name: String = "",
    val registrationDate: Long = System.currentTimeMillis(),
    val maxUsageMonths: Int = 0, // Deprecated, use days
    val maxUsageDays: Int = 0,
    val expiryDate: Long = 0L,
    val category: String? = null,
    val status: String = "ACTIVE",
    var isSynced: Boolean = false,
    var isDeleted: Boolean = false
)
