package com.gomezmart.app.data

import com.google.firebase.firestore.IgnoreExtraProperties

@IgnoreExtraProperties
data class MarketplaceProduct(
    val id: String = "",
    val name: String = "",
    val category: String = "",
    val price: Double = 0.0,
    val description: String = "",
    val photoUrl: String? = null,
    val storeId: String = "",
    val storeName: String = "",
    val storeAddress: String = "",
    val storeLogoUrl: String? = null,
    val storeType: String = "PRODUCTS",
    val averageRating: Double = 0.0,
    val reviewCount: Int = 0
)
