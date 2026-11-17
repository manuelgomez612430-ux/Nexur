package com.gomezmart.app.data

import com.google.firebase.Timestamp
import com.google.firebase.firestore.Exclude

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
    val deliveryReference: String = "",
    val deliveryLat: Double = 0.0,
    val deliveryLng: Double = 0.0,
    val deliveryPersonId: String = "", // UID del repartidor (Seller o Colaborador) que lleva el pedido
    val lastMessage: String = "",
    val lastMessageTime: Any? = null
)

@IgnoreExtraProperties
data class ChatMessage(
    val id: String = "",
    val senderId: String = "",
    val message: String = "",
    val timestamp: Any? = null,
    val isRead: Boolean = false,
    val type: String = "TEXT", // TEXT, PRODUCT_CARD, IMAGE
    val productId: String = "",
    val productPhoto: String = "",
    val productPrice: Double = 0.0,
    val productCategory: String = "",
    val productStoreId: String = "",
    val productStoreName: String = "",
    val productStoreType: String = "PRODUCTS",
    val imageUrl: String = "",
    val linkedOrderId: String = "", // Para botones de acción en el chat
    val deliveryLat: Double = 0.0,
    val deliveryLng: Double = 0.0,
    val deliveryAddress: String = "",
    
    // Campos locales para envío inmediato (No se guardan en DB o se ignoran)
    @get:Exclude
    var localPath: String? = null,
    @get:Exclude
    var isUploading: Boolean = false
)
