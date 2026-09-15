package com.naxor.app.data

import com.google.firebase.Timestamp

import com.google.firebase.firestore.IgnoreExtraProperties

@IgnoreExtraProperties
data class OrderModel(
    val id: String = "",
    val buyerId: String = "",
    val buyerName: String = "",
    val buyerPhone: String = "",
    val storeId: String = "",
    val storeName: String = "",
    val items: List<CartItem> = emptyList(),
    val totalAmount: Double = 0.0,
    val deliveryFee: Double = 0.0,
    val status: String = "PENDING", // PENDING, ACCEPTED, EN_ROUTE, DELIVERED, CANCELLED
    val timestamp: Any? = null,
    val deliveryAddress: String = "",
    val deliveryLat: Double = 0.0,
    val deliveryLng: Double = 0.0,
    val lastMessage: String = "",
    val lastMessageTime: Any? = null
)

@IgnoreExtraProperties
data class ChatMessage(
    val id: String = "",
    val senderId: String = "",
    val message: String = "",
    val timestamp: Any? = null,
    val isRead: Boolean = false
)
