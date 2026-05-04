package com.aeoncorex.streamx.ui.movie

import com.google.gson.annotations.SerializedName

// --- SHARED DATA MODELS ---
data class StreamLink(
    val title: String,
    val magnet: String,
    val quality: String,
    val seeds: Int,
    val peers: Int,
    val size: String,
    val source: String
)

// --- TMDB MODELS ---
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

// --- DETAILS ---
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
    val seasons: List<SeasonDto>?,
    val external_ids: ExternalIds?
)

data class ExternalIds(
    @SerializedName("imdb_id") val imdbId: String?
)

data class SeasonDetailResponse(
    val id: Int,
    val episodes: List<EpisodeDto>?
)

data class EpisodeDto(
    val id: Int,
    val name: String?,
    val overview: String?,
    @SerializedName("episode_number") val episodeNumber: Int,
    @SerializedName("still_path") val stillPath: String?,
    val runtime: Int?
)

data class Genre(val id: Int, val name: String)
data class Credits(val cast: List<CastDto>, val crew: List<CrewDto>)
data class Videos(val results: List<VideoDto>)
data class SeasonDto(
    @SerializedName("season_number") val seasonNumber: Int,
    @SerializedName("episode_count") val episodeCount: Int,
    val name: String
)

data class CastDto(val id: Int, val name: String, val character: String?, @SerializedName("profile_path") val profilePath: String?)
data class CrewDto(val id: Int, val name: String, val job: String?)
data class VideoDto(val key: String, val site: String, val type: String)

data class FullMovieDetails(
    val basic: Movie,
    val runtime: String,
    val genres: List<String>,
    val cast: List<CastMember>,
    val director: String,
    val trailerKey: String?,
    val recommendations: List<Movie>,
    val seasons: List<SeasonDto> = emptyList(),
    val imdbId: String?
)

data class CastMember(val name: String, val role: String, val imageUrl: String, val personId: Int = 0)

// ── Person / Actor detail models ──────────────────────────────────
data class PersonDetails(
    val id:             Int,
    val name:           String,
    val biography:      String,
    val birthday:       String?,
    val deathday:       String?,
    val placeOfBirth:   String?,
    val gender:         Int,           // 1=Female, 2=Male
    val knownFor:       String,        // e.g. "Acting"
    val popularity:     Double,
    val profileUrl:     String,
    val knownForMovies: List<Movie>,   // top known-for titles
    val socialLinks:    PersonSocials,
)

data class PersonSocials(
    val instagramId: String? = null,
    val twitterId:   String? = null,
    val facebookId:  String? = null,
    val imdbId:      String? = null,
)

// TMDB Person API response
data class PersonResponse(
    val id: Int,
    val name: String,
    val biography: String?,
    @com.google.gson.annotations.SerializedName("birthday")         val birthday: String?,
    @com.google.gson.annotations.SerializedName("deathday")         val deathday: String?,
    @com.google.gson.annotations.SerializedName("place_of_birth")   val placeOfBirth: String?,
    val gender: Int?,
    @com.google.gson.annotations.SerializedName("known_for_department") val knownFor: String?,
    val popularity: Double?,
    @com.google.gson.annotations.SerializedName("profile_path")     val profilePath: String?,
)

data class PersonCombinedCredits(
    val cast: List<PersonCastItem>,
)

data class PersonCastItem(
    val id:            Int,
    val title:         String?,
    val name:          String?,
    @com.google.gson.annotations.SerializedName("poster_path")      val posterPath: String?,
    @com.google.gson.annotations.SerializedName("vote_average")     val rating: Double?,
    @com.google.gson.annotations.SerializedName("media_type")       val mediaType: String?,
    @com.google.gson.annotations.SerializedName("release_date")     val releaseDate: String?,
    @com.google.gson.annotations.SerializedName("first_air_date")   val firstAirDate: String?,
    val popularity: Double?,
)

data class PersonExternalIds(
    @com.google.gson.annotations.SerializedName("instagram_id") val instagramId: String?,
    @com.google.gson.annotations.SerializedName("twitter_id")   val twitterId:   String?,
    @com.google.gson.annotations.SerializedName("facebook_id")  val facebookId:  String?,
    @com.google.gson.annotations.SerializedName("imdb_id")      val imdbId:      String?,
)
