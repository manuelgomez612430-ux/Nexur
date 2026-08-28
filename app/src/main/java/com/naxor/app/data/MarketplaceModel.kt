package com.naxor.app.data

data class MarketplaceProduct(
    val id: String,
    val name: String,
    val category: String,
    val price: Double,
    val description: String,
    val photoUrl: String?,
    val storeId: String,
    val storeName: String,
    val storeAddress: String,
    val storeLogoUrl: String? = null,
    val storeType: String = "PRODUCTS"
)
