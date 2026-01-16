package com.aeoncorex.streamx.ui.movie

import com.google.gson.annotations.SerializedName

// --- EXISTING MODELS ---
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

// --- NEW MODELS FOR DETAILS & CREDITS ---

data class MovieDetailResponse(
    val id: Int,
    val title: String?,
    val name: String?,
    val overview: String?,
    @SerializedName("poster_path") val posterPath: String?,
    @SerializedName("backdrop_path") val backdropPath: String?,
    @SerializedName("vote_average") val rating: Double?,
    @SerializedName("release_date") val releaseDate: String?,
    @SerializedName("first_air_date") val firstAirDate: String?,
    val runtime: Int?,
    val genres: List<Genre>?,
    val credits: Credits?,
    val videos: Videos?,
    val recommendations: TmdbResponse?
)

data class Genre(val id: Int, val name: String)
data class Credits(val cast: List<CastDto>, val crew: List<CrewDto>)
data class Videos(val results: List<VideoDto>)

data class CastDto(
    val id: Int,
    val name: String,
    val character: String?,
    @SerializedName("profile_path") val profilePath: String?
)

data class CrewDto(
    val id: Int,
    val name: String,
    val job: String?
)

data class VideoDto(
    val key: String,
    val site: String,
    val type: String
)

// UI Model for Detail Screen
data class FullMovieDetails(
    val basic: Movie,
    val runtime: String,
    val genres: List<String>,
    val cast: List<CastMember>,
    val director: String,
    val trailerKey: String?,
    val recommendations: List<Movie>
)

data class CastMember(
    val name: String,
    val role: String,
    val imageUrl: String
)
