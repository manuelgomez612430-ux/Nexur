package com.naxor.app.data

import com.google.firebase.Timestamp
import com.google.firebase.firestore.PropertyName

data class AppMessage(
    val id: String = "",
    val title: String = "",
    
    @get:PropertyName("content")
    @set:PropertyName("content")
    var content: String = "",
    
    val timestamp: Timestamp? = null
)

