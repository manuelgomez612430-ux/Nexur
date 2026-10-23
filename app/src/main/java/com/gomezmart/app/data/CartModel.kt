package com.gomezmart.app.data

import android.content.Context
import com.google.firebase.firestore.Exclude
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class CartItem(
    val productId: String = "",
    val productName: String = "",
    val price: Double = 0.0,
    var quantity: Int = 1,
    val category: String = "",
    val photoUrl: String? = null
) {
    @get:Exclude
    val total: Double get() = price * quantity
}

object CartManager {
    private val items = mutableMapOf<String, CartItem>()
    var currentStoreId: String = ""
    var currentStoreName: String = ""
    var currentStoreLogo: String = ""

    fun addItem(product: ProductEntity, storeId: String, storeName: String = "", storeLogo: String = "") {
        if (currentStoreId != storeId) {
            items.clear()
            currentStoreId = storeId
            currentStoreName = storeName
            currentStoreLogo = storeLogo
        } else {
            // [SIEMPRE ACTUALIZAR] Asegurar que el nombre y logo se guarden si estaban vacíos
            if (storeName.isNotEmpty()) currentStoreName = storeName
            if (storeLogo.isNotEmpty()) currentStoreLogo = storeLogo
        }
        
        val existing = items[product.id]
        if (existing != null) {
            existing.quantity++
        } else {
            items[product.id] = CartItem(
                productId = product.id,
                productName = product.nombre,
                price = product.precioVenta,
                category = product.categoria
            )
        }
    }

    fun addItemFromMarketplace(product: MarketplaceProduct) {
        if (currentStoreId != product.storeId) {
            items.clear()
            currentStoreId = product.storeId
            currentStoreName = product.storeName
            currentStoreLogo = product.storeLogoUrl ?: ""
        } else {
            // [SIEMPRE ACTUALIZAR] Asegurar que el nombre y logo se guarden si estaban vacíos
            if (product.storeName.isNotEmpty()) currentStoreName = product.storeName
            if (!product.storeLogoUrl.isNullOrEmpty()) currentStoreLogo = product.storeLogoUrl
        }

        val existing = items[product.id]
        if (existing != null) {
            existing.quantity++
        } else {
            items[product.id] = CartItem(
                productId = product.id,
                productName = product.name,
                price = product.price,
                category = product.category,
                photoUrl = product.photoUrl
            )
        }
    }

    fun addItemFromManualData(id: String, name: String, price: Double, cat: String, sId: String, sName: String = "", sLogo: String = "", photo: String? = null) {
        if (currentStoreId != sId) {
            items.clear()
            currentStoreId = sId
            currentStoreName = sName
            currentStoreLogo = sLogo
        } else {
            // [SIEMPRE ACTUALIZAR] Asegurar que el nombre y logo se guarden si estaban vacíos
            if (sName.isNotEmpty()) currentStoreName = sName
            if (sLogo.isNotEmpty()) currentStoreLogo = sLogo
        }
        val existing = items[id]
        if (existing != null) {
            existing.quantity++
        } else {
            items[id] = CartItem(id, name, price, 1, cat, photo)
        }
    }

    fun removeItem(productId: String) {
        items.remove(productId)
    }

    fun updateQuantity(productId: String, delta: Int) {
        items[productId]?.let {
            it.quantity += delta
            if (it.quantity <= 0) items.remove(productId)
        }
    }

    fun getItems(): List<CartItem> = items.values.toList()
    fun getTotalCount(): Int = items.values.sumOf { it.quantity }
    fun getTotalPrice(): Double = items.values.sumOf { it.total }
    fun clear() { 
        items.clear()
        currentStoreId = ""
        currentStoreName = ""
        currentStoreLogo = ""
    }

    fun save(context: Context) {
        val prefs = context.getSharedPreferences("CartPrefs", Context.MODE_PRIVATE)
        val gson = Gson()
        val json = gson.toJson(items.values.toList())
        prefs.edit()
            .putString("cart_items", json)
            .putString("store_id", currentStoreId)
            .putString("store_name", currentStoreName)
            .putString("store_logo", currentStoreLogo)
            .apply()
    }

    fun load(context: Context) {
        val prefs = context.getSharedPreferences("CartPrefs", Context.MODE_PRIVATE)
        val json = prefs.getString("cart_items", null)
        currentStoreId = prefs.getString("store_id", "") ?: ""
        currentStoreName = prefs.getString("store_name", "") ?: ""
        currentStoreLogo = prefs.getString("store_logo", "") ?: ""
        if (json != null) {
            val gson = Gson()
            val type = object : TypeToken<List<CartItem>>() {}.type
            val list = gson.fromJson<List<CartItem>>(json, type)
            items.clear()
            list.forEach { items[it.productId] = it }
        }
    }
}
