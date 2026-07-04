package com.aeoncorex.streamx.ui.movie

import com.google.gson.annotations.SerializedName

// ═════════════════════════════════════════════════════════════════════════════
//  MovieModels.kt — v3
//  ─────────────────────────────────────────────────────────────────────────
//  • EpisodeDto  — added externalStillUrl for Cinemeta full thumbnail URLs
//  • FullMovieDetails / Movie / Person models unchanged
// ═════════════════════════════════════════════════════════════════════════════

// ── Shared ────────────────────────────────────────────────────────────────────
data class StreamLink(
    val title:   String,
    val magnet:  String,
    val quality: String,
    val seeds:   Int,
    val peers:   Int,
    val size:    String,
    val source:  String,
    /**
     * False only for results surfaced through IndexerNative's
     * searchDubbed() untagged fallback path — meaning no site returned
     * a result carrying a recognized dub-language tag for the requested
     * title, so this is a title/IMDB-matched result shown as a
     * best-effort rather than a confirmed dub. Defaults to true for
     * every other provider (YTS, EZTV, BitSearch, etc.), which don't
     * have this distinction — they either found a match or didn't.
     */
    val isConfirmedDub: Boolean = true
)

// ── TMDB list/search response ─────────────────────────────────────────────────
data class TmdbResponse(val results: List<MovieDto>)

data class MovieDto(
    val id:                                          Int,
    val title:                                       String?,
    val name:                                        String?,
    @SerializedName("poster_path")   val posterPath:   String?,
    @SerializedName("backdrop_path") val backdropPath: String?,
    @SerializedName("overview")      val overview:     String?,
    @SerializedName("vote_average")  val rating:       Double?,
    @SerializedName("release_date")  val releaseDate:  String?,
    @SerializedName("first_air_date")val firstAirDate: String?
)

// ── Core app model ────────────────────────────────────────────────────────────
enum class MovieType { MOVIE, SERIES }

data class Movie(
    val id:          Int,
    val title:       String,
    val description: String,
    val posterUrl:   String,
    val backdropUrl: String,
    val rating:      String,
    val year:        String,
    val type:        MovieType,
    /** Transparent PNG logo from Cinemeta (empty if unavailable) */
    val logo:        String  = ""
)

// ── TMDB detail response (Retrofit/Gson) ──────────────────────────────────────
data class MovieDetailResponse(
    val id:                                              Int,
    val title:                                           String?,
    val name:                                            String?,
    val overview:                                        String?,
    @SerializedName("poster_path")    val posterPath:    String?,
    @SerializedName("backdrop_path")  val backdropPath:  String?,
    @SerializedName("vote_average")   val rating:        Double?,
    @SerializedName("release_date")   val releaseDate:   String?,
    @SerializedName("first_air_date") val firstAirDate:  String?,
    val runtime:                                         Int?,
    val genres:                                          List<Genre>?,
    val credits:                                         Credits?,
    val videos:                                          Videos?,
    val recommendations:                                 TmdbResponse?,
    val seasons:                                         List<SeasonDto>?,
    val external_ids:                                    ExternalIds?
)

data class ExternalIds(
    @SerializedName("imdb_id") val imdbId: String?
)

data class SeasonDetailResponse(
    val id:       Int,
    val episodes: List<EpisodeDto>?
)

/**
 * EpisodeDto — v3
 * Added externalStillUrl so Cinemeta full URLs work alongside TMDB still_path.
 */
data class EpisodeDto(
    val id:                                               Int,
    val name:                                             String?,
    val overview:                                         String?,
    @SerializedName("episode_number") val episodeNumber:  Int,
    @SerializedName("still_path")     val stillPath:      String?,
    val runtime:                                          Int?,
    /** Full external image URL (e.g. from Cinemeta). Takes priority over TMDB stillPath. */
    val externalStillUrl: String? = null
) {
    private companion object {
        const val STILL_BASE = "https://image.tmdb.org/t/p/w300"
    }

    /** Full URL for the episode thumbnail. Empty string if unavailable. */
    val stillUrl: String
        get() = when {
            !externalStillUrl.isNullOrEmpty() -> externalStillUrl
            !stillPath.isNullOrEmpty() -> "$STILL_BASE$stillPath"
            else -> ""
        }

    /** Human-readable runtime: "45m", "1h 15m", or "" if unknown. */
    val formattedRuntime: String
        get() = when {
            runtime == null || runtime <= 0 -> ""
            runtime >= 60 -> "${runtime / 60}h ${runtime % 60}m"
            else          -> "${runtime}m"
        }

    /** Safe title — never null. */
    val safeName: String get() = name.orEmpty()

    /** Safe overview — never null. */
    val safeOverview: String get() = overview.orEmpty()
}

data class Genre(val id: Int, val name: String)
data class Credits(val cast: List<CastDto>, val crew: List<CrewDto>)
data class Videos(val results: List<VideoDto>)

data class SeasonDto(
    @SerializedName("season_number") val seasonNumber:  Int,
    @SerializedName("episode_count") val episodeCount:  Int,
    val name:                                           String
)

data class CastDto(
    val id:                                         Int,
    val name:                                       String,
    val character:                                  String?,
    @SerializedName("profile_path") val profilePath: String?
)

data class CrewDto(val id: Int, val name: String, val job: String?)
data class VideoDto(val key: String, val site: String, val type: String)

data class FullMovieDetails(
    val basic           : Movie,
    val runtime         : String,
    val genres          : List<String>,
    val cast            : List<CastMember>,
    val director        : String,
    val trailerKey      : String?,
    val recommendations : List<Movie>,
    val seasons         : List<SeasonDto>,
    val imdbId          : String?,

    // Cinemeta-exclusive enrichments
    val logo            : String  = "",     // Netflix-style title logo
    val awards          : String  = "",     // e.g. "Won 3 Oscars..."
    val country         : String  = "",     // e.g. "USA"
    val language        : String  = "",     // ← NEW: e.g. "English"
    val status          : String  = "",     // e.g. "Ended", "Returning Series"

    // Dual ratings — shown side-by-side in UI
    val tmdbRating      : Double  = 0.0,   // ← NEW: TMDB vote_average (green ⭐)
    val imdbRating      : Double  = 0.0,   // IMDb rating via Cinemeta  (gold ★)

    val cinemetaEnriched: Boolean = false   // true if Cinemeta data merged
)

// ── CastMember ────────────────────────────────────────────────────────────
data class CastMember(
    val name     : String,
    val role     : String,
    val imageUrl : String,
    val personId : Int     // 0 = Cinemeta-only (no navigation), >0 = TMDB
)

// ── Person / Actor detail ─────────────────────────────────────────────────────
data class PersonDetails(
    val id:              Int,
    val name:            String,
    val biography:       String,
    val birthday:        String?,
    val deathday:        String?,
    val placeOfBirth:    String?,
    val gender:          Int,
    val knownFor:        String,
    val popularity:      Double,
    val profileUrl:      String,
    val knownForMovies:  List<Movie>,
    val socialLinks:     PersonSocials
)

data class PersonSocials(
    val instagramId: String? = null,
    val twitterId:   String? = null,
    val facebookId:  String? = null,
    val imdbId:      String? = null
)

// TMDB Person API response models
data class PersonApiResponse(
    val id:                                                              Int,
    val name:                                                            String,
    val biography:                                                       String?,
    val birthday:                                                        String?,
    val deathday:                                                        String?,
    @SerializedName("place_of_birth")        val placeOfBirth:          String?,
    val gender:                                                          Int?,
    @SerializedName("known_for_department")  val knownFor:              String?,
    val popularity:                                                      Double?,
    @SerializedName("profile_path")          val profilePath:           String?,
    @SerializedName("combined_credits")      val credits:               PersonCombinedCredits?,
    @SerializedName("external_ids")          val externalIds:           PersonExternalIds?
)

data class PersonResponse(
    val id:                                                              Int,
    val name:                                                            String,
    val biography:                                                       String?,
    val birthday:                                                        String?,
    val deathday:                                                        String?,
    @SerializedName("place_of_birth")        val placeOfBirth:          String?,
    val gender:                                                          Int?,
    @SerializedName("known_for_department")  val knownFor:              String?,
    val popularity:                                                      Double?,
    @SerializedName("profile_path")          val profilePath:           String?
)

data class PersonCombinedCredits(val cast: List<PersonCastItem>)

data class PersonCastItem(
    val id:                                                              Int,
    val title:                                                           String?,
    val name:                                                            String?,
    @SerializedName("poster_path")    val posterPath:                   String?,
    @SerializedName("vote_average")   val rating:                       Double?,
    @SerializedName("media_type")     val mediaType:                    String?,
    @SerializedName("release_date")   val releaseDate:                  String?,
    @SerializedName("first_air_date") val firstAirDate:                 String?,
    val popularity:                                                      Double?
)

data class PersonExternalIds(
    @SerializedName("instagram_id")   val instagramId: String?,
    @SerializedName("twitter_id")     val twitterId:   String?,
    @SerializedName("facebook_id")    val facebookId:  String?,
    @SerializedName("imdb_id")        val imdbId:      String?
)
