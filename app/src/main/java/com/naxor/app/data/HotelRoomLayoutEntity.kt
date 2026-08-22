package com.naxor.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "hotel_room_layouts")
data class HotelRoomLayoutEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val roomId: String? = null, // ID de la habitación vinculada, null si es solo pared
    val floorId: Int = 1, // Piso al que pertenece
    val type: String, // ROOM, WALL, DOOR
    var x: Float,
    var y: Float,
    var width: Float,
    var height: Float,
    var rotation: Float = 0f,
    var isHollow: Boolean = false,
    var strokeWidth: Float = 4f,
    var isSynced: Boolean = false,
    var isDeleted: Boolean = false
)
