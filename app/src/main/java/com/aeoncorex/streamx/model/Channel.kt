package com.aeoncorex.streamx.model

data class Channel(
    val id: String = "",
    val name: String = "",
    val logoUrl: String = "",
    val streamUrl: String = "",
    val category: String = ""
    val isFeatured: Boolean = false
)