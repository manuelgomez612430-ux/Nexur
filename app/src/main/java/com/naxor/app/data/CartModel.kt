package com.naxor.app.data

data class CartItem(
    val productId: String,
    val productName: String,
    val price: Double,
    var quantity: Int = 1,
    val category: String = ""
) {
    val total: Double get() = price * quantity
}

object CartManager {
    private val items = mutableMapOf<String, CartItem>()
    var currentStoreId: String = ""

    fun addItem(product: ProductEntity, storeId: String) {
        if (currentStoreId != storeId) {
            items.clear()
            currentStoreId = storeId
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
        }

        val existing = items[product.id]
        if (existing != null) {
            existing.quantity++
        } else {
            items[product.id] = CartItem(
                productId = product.id,
                productName = product.name,
                price = product.price,
                category = product.category
            )
        }
    }

    fun addItemFromManualData(id: String, name: String, price: Double, cat: String, sId: String) {
        if (currentStoreId != sId) {
            items.clear()
            currentStoreId = sId
        }
        val existing = items[id]
        if (existing != null) {
            existing.quantity++
        } else {
            items[id] = CartItem(id, name, price, 1, cat)
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
    fun clear() { items.clear(); currentStoreId = "" }
}
