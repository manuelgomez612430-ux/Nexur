package com.gomezmart.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "commitments")
data class CommitmentEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val description: String = "",
    val amount: Double = 0.0,
    val dueDate: Long = 0L,
    val recurringDay: Int = 0, // 1-31
    val frequency: String = "FIXED", // FIXED, DAILY, MONTHLY
    val isPaid: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    var isSynced: Boolean = false,
    var isDeleted: Boolean = false
)
