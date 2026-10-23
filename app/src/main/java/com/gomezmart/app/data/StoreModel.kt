package com.gomezmart.app.data

data class StoreModel(
    val id: String = "",
    val name: String = "Tienda GoMezMart",
    val category: String = "Tienda",
    val address: String = "Dirección no disponible",
    val phone: String = "",
    val logoUrl: String? = null,
    val coverUrl: String? = null,
    val description: String = "",
    val ownerName: String = "",
    val creationDate: Long = 0L,
    val businessType: String = "PRODUCTS",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val city: String = "",
    val postalCode: String = "",
    val extraSections: List<StoreSection> = emptyList(),
    val theme: StoreTheme = StoreTheme(),
    val paymentMethods: List<PaymentMethod> = emptyList()
)

data class PaymentMethod(
    val type: String = "BANK", // "YAPE", "PLIN", "BANK"
    val title: String = "",    // Ej: "BCP Ahorros"
    val accountInfo: String = "" // El número de cuenta
)

data class StoreTheme(
    val primaryColor: String = "#7C3AED", // GoMezMart Purple
    val textColor: String = "#0F172A",    // Slate 900
    val backgroundColor: String = "#FFFFFF",
    val fontStyle: String = "MODERN",     // MODERN, CLASSIC, BOLD
    val decorations: String = "CIRCLES"   // NONE, CIRCLES, LINES, MIXED
)

data class StoreSection(
    val id: String = "",
    val title: String = "",
    val description: String = "",
    val imageUrls: List<String> = emptyList(), // Soporte para múltiples fotos
    val imageLocalPaths: List<String> = emptyList(),
    val aspectRatio: String = "16:9" // "1:1", "16:9", "FREE"
)
