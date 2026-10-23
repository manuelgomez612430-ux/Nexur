package com.gomezmart.app.data

data class QuickAction(
    val id: String,
    val name: String,
    val color: String,
    val action: () -> Unit
)
