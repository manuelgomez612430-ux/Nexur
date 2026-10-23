package com.gomezmart.app.data

data class StoreReview(
    val id: String = "",
    val userId: String = "",
    val userName: String = "",
    val userPhotoUrl: String? = null,
    val comment: String = "",
    val rating: Int = 5,
    val timestamp: Long = System.currentTimeMillis()
)
