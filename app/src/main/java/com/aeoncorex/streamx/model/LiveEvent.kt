package com.aeoncorex.streamx.model

import com.google.firebase.Timestamp

data class LiveEvent(
    val id: String = "",
    val title: String = "",
    val thumbnailUrl: String = "",
    val streamUrl: String = "",
    val startTime: Timestamp = Timestamp.now(),
    val endTime: Timestamp = Timestamp.now(),
    val isLive: Boolean = false
)