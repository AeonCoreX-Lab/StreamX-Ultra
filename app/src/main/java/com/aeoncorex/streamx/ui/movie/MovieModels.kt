package com.aeoncorex.streamx.ui.movie

import com.google.gson.annotations.SerializedName

data class TmdbResponse(val results: List<MovieDto>)

data class MovieDto(
    val id: Int,
    val title: String?,
    val name: String?,
    @SerializedName("poster_path") val posterPath: String?,
    @SerializedName("backdrop_path") val backdropPath: String?,
    @SerializedName("overview") val overview: String?,
    @SerializedName("vote_average") val rating: Double?,
    @SerializedName("release_date") val releaseDate: String?,
    @SerializedName("first_air_date") val firstAirDate: String?
)

data class Movie(
    val id: Int,
    val title: String,
    val description: String,
    val posterUrl: String,
    val backdropUrl: String,
    val rating: String,
    val year: String,
    val type: MovieType
)

enum class MovieType { MOVIE, SERIES }
