package com.naxor.app.data

import androidx.room.Embedded
import androidx.room.Relation

data class LoanClientWithDebt(
    @Embedded val client: LoanClientEntity,
    val totalDebt: Double,
    val totalOverdueCount: Int,
    val totalPaidOnTimeCount: Int
)
