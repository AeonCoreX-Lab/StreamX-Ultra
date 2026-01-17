package com.aeoncorex.streamx.ui.movie

import com.google.gson.annotations.SerializedName

// --- EXISTING BASIC MODELS ---
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

// --- UPDATED DETAILS MODELS (TMDB + OMDB + SERVERS) ---

data class FullMovieDetails(
    val basic: Movie,
    val runtime: String,
    val genres: List<String>,
    val cast: List<CastMember>,
    val director: String,
    val trailerKey: String?,
    val recommendations: List<Movie>,
    // OMDb Specifics
    val imdbRating: String,
    val metascore: String,
    val awards: String,
    val ageRating: String, // Rated (PG-13, R etc.)
    val boxOffice: String,
    // Streaming Sources
    val servers: List<StreamServer>
)

data class StreamServer(
    val name: String,
    val url: String, // The embed URL
    val quality: String = "HD",
    val type: String = "EMBED" // "EMBED" or "DIRECT"
)

data class CastMember(val name: String, val role: String, val imageUrl: String)

// --- API RESPONSE MODELS ---

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
    val recommendations: TmdbResponse?,
    @SerializedName("external_ids") val externalIds: ExternalIds?
)

data class ExternalIds(@SerializedName("imdb_id") val imdbId: String?)

// OMDb API Structure
data class OmdbResponse(
    @SerializedName("imdbRating") val imdbRating: String?,
    @SerializedName("Metascore") val metaScore: String?,
    @SerializedName("Awards") val awards: String?,
    @SerializedName("BoxOffice") val boxOffice: String?,
    @SerializedName("Rated") val rated: String?
)

data class Genre(val id: Int, val name: String)
data class Credits(val cast: List<CastDto>, val crew: List<CrewDto>)
data class Videos(val results: List<VideoDto>)
data class CastDto(val id: Int, val name: String, val character: String?, @SerializedName("profile_path") val profilePath: String?)
data class CrewDto(val id: Int, val name: String, val job: String?)
data class VideoDto(val key: String, val site: String, val type: String)
