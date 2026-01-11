package com.aeoncorex.streamx.model

data class Movie(
    val id: String,
    val title: String,
    val description: String,
    val posterUrl: String, // Vertical Image (Netflix style)
    val coverUrl: String, // Wide Image for Hero Banner
    val streamUrl: String,
    val category: String,
    val year: String,
    val rating: String = "IMDb 8.5"
)
