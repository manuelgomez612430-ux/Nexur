package com.naxor.app.data

import java.util.UUID

data class CollaboratorEntity(
    val id: String = UUID.randomUUID().toString(),
    val email: String = "",
    val name: String = "",
    val dni: String = "",
    val role: String = "RECEPTIONIST", // Para compatibilidad con versiones anteriores
    val roles: List<String> = listOf("RECEPCIONISTA"), // RECEPCIONISTA, LIMPIADOR, SEGURIDAD, ADMINISTRACIÓN, CONTADOR
    val status: String = "PENDING", // PENDING, ACTIVE
    val dateAdded: Long = System.currentTimeMillis(),
    val permissions: Map<String, Boolean> = mapOf(
        "can_view_metrics" to false,
        "can_manage_debts" to false,
        "can_manage_rooms" to true,
        "can_edit_room_info" to false,
        "can_delete_rooms" to false,
        "can_manage_inventory" to false,
        "can_view_cash" to false,
        "can_view_history" to true,
        "can_manage_bookings" to true,
        "can_manage_failures" to true,
        "can_manage_tools" to true,
        "can_edit_map" to false
    )
)
