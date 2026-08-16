package com.naxor.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "providers")
data class ProviderEntity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val nombre: String = "",
    val contacto: String = "",
    val telefono: String = "",
    val categoria: String = "",
    val notas: String? = null,
    var isSynced: Boolean = false,
    var isDeleted: Boolean = false
)
