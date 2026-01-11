package com.aeoncorex.streamx.model

data class Movie(
    val id: String = "",
    val title: String = "",
    val description: String = "",
    val posterUrl: String = "",
    val coverUrl: String = "",
    val streamUrl: String = "",
    val category: String = "",
    val year: String = "",
    val rating: String = "IMDb 8.5",
    val isFeatured: Boolean = false // Added for Hero Banner control
)
