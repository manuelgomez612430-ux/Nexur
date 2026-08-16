package com.naxor.app.data

import com.google.firebase.Timestamp

data class AppMessage(
    val id: String = "",
    val title: String = "",
    val content: String = "",
    val timestamp: Timestamp? = null
)
