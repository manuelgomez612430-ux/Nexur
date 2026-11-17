package com.gomezmart.app.data

import com.google.firebase.firestore.IgnoreExtraProperties

@IgnoreExtraProperties
data class DirectChat(
    val id: String = "",
    val participants: List<String> = emptyList(),
    val participantNames: Map<String, String> = emptyMap(),
    val participantPhotos: Map<String, String> = emptyMap(),
    val participantBusinessLogos: Map<String, String> = emptyMap(),
    val participantTitles: Map<String, String> = emptyMap(),
    val participantEmails: Map<String, String> = emptyMap(),
    val participantPhones: Map<String, String> = emptyMap(),
    val unreadCount: Map<String, Int> = emptyMap(),
    val lastMessage: String = "",
    val lastMessageTime: Any? = null,
    val clearedUntil: Map<String, Long> = emptyMap(),
    val requestStatus: String = "ACCEPTED",
    val requestedBy: String = "",
    val chatType: String = "DIRECT"
)
