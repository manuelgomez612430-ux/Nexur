package com.naxor.app.data

data class UserLocation(
    val id: String = "",
    val title: String = "", // e.g., "Mi Casa"
    val address: String = "", // Manual or Geocoded
    val reference: String = "", // e.g., "Frente al parque"
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val isDefault: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)
