package com.naxor.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "customers")
data class CustomerEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val nombre: String,
    val telefono: String,
    val direccion: String? = null,
    val notas: String? = null,
    val esVip: Boolean = false,
    val fechaRegistro: Long = System.currentTimeMillis(),
    val isSynced: Boolean = true
)
