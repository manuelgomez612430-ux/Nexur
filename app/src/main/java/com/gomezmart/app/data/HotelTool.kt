package com.gomezmart.app.data

data class HotelTool(
    val id: String,
    val name: String,
    val emoji: String,
    val action: () -> Unit,
    val hasBadge: Boolean = false
)
