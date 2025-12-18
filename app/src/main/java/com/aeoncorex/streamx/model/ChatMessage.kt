package com.aeoncorex.streamx.model

import com.google.firebase.Timestamp

data class ChatMessage(
    val id: String = "",
    val text: String = "",
    val senderName: String = "Anonymous",
    val timestamp: Timestamp = Timestamp.now()
)