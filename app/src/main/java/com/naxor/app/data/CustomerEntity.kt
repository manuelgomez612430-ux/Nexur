package com.naxor.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "customers")
data class CustomerEntity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val nombre: String,
    val telefono: String,
    val direccion: String? = null,
    val notas: String? = null,
    val esVip: Boolean = false,
    var fechaRegistro: Long = System.currentTimeMillis(),
    var isSynced: Boolean = false,
    var isDeleted: Boolean = false
)
