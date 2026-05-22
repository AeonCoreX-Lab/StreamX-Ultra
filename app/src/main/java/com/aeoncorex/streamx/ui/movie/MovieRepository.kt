package com.aeoncorex.streamx.ui.movie

import android.util.Log
import com.aeoncorex.streamx.streaming.CinemetaMeta
import com.aeoncorex.streamx.streaming.CinemetaPerson
import com.aeoncorex.streamx.streaming.CinemetaRepository
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import java.net.HttpURLConnection
import java.net.URL

// ── TMDB Retrofit interface ───────────────────────────────────────────────────
interface TmdbApi {
    @GET("3/trending/all/day")
    suspend fun getTrending(@Query("api_key") apiKey: String): TmdbResponse

    @GET("3/movie/popular")
    suspend fun getPopularMovies(@Query("api_key") apiKey: String): TmdbResponse

    @GET("3/tv/top_rated")
    suspend fun getTopRatedSeries(@Query("api_key") apiKey: String): TmdbResponse

    @GET("3/discover/movie")
    suspend fun getActionMovies(
        @Query("api_key") apiKey: String,
        @Query("with_genres") genre: String = "28"
    ): TmdbResponse

    @GET("3/discover/movie")
    suspend fun getSciFiMovies(
        @Query("api_key") apiKey: String,
        @Query("with_genres") genre: String = "878"
    ): TmdbResponse

    @GET("3/search/multi")
    suspend fun searchMulti(
        @Query("api_key") apiKey: String,
        @Query("query") query: String
    ): TmdbResponse

    @GET("3/{type}/{id}")
    suspend fun getDetails(
        @Path("type") type: String,
        @Path("id") id: Int,
        @Query("api_key") apiKey: String,
        @Query("append_to_response") append: String =
            "credits,videos,recommendations,external_ids,seasons"
    ): MovieDetailResponse

    @GET("3/tv/{id}/season/{season_number}")
    suspend fun getSeasonDetails(
        @Path("id") seriesId: Int,
        @Path("season_number") seasonNumber: Int,
        @Query("api_key") apiKey: String
    ): SeasonDetailResponse

    @GET("3/person/{person_id}")
    suspend fun getPersonDetails(
        @Path("person_id") personId: Int,
        @Query("api_key") apiKey: String,
        @Query("append_to_response") appendTo: String = "combined_credits,external_ids"
    ): PersonApiResponse
}

// ═════════════════════════════════════════════════════════════════════════════
//  MovieRepository — v3   TMDB primary + Cinemeta deep fallback
// ═════════════════════════════════════════════════════════════════════════════
object MovieRepository {

    private const val TAG               = "MovieRepo"
    private const val IMAGE_BASE_URL    = "https://image.tmdb.org/t/p/w500"
    private const val BACKDROP_BASE_URL = "https://image.tmdb.org/t/p/original"
    private const val CACHE_TTL_MS      = 3_600_000L

    private val VERCEL_TMDB_ENDPOINT get() =
        "${com.aeoncorex.streamx.BuildConfig.BACKEND_BASE_URL}/api/tmdb-key"

    private var cachedKey:     String = ""
    private var cacheLoadedAt: Long   = 0L

    private val tmdbToImdbCache = mutableMapOf<Int, String>()
    private val tmdbPersonNameCache = mutableMapOf<Int, String>()

    private val api = Retrofit.Builder()
        .baseUrl("https://api.themoviedb.org/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(TmdbApi::class.java)

    private suspend fun getApiKey(): String = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (cachedKey.isNotEmpty() && cachedKey != "api_key_not_found"
            && now - cacheLoadedAt < CACHE_TTL_MS) return@withContext cachedKey

        val rustKey = try {
            StreamXCore.getTmdbKey()
        } catch (e: Throwable) { Log.w(TAG, "Rust vault: ${e.message}"); "" }

        if (rustKey.isNotEmpty() && rustKey != "api_key_not_found") {
            cachedKey = rustKey; cacheLoadedAt = now
            return@withContext cachedKey
        }

        val vercelKey = fetchKeyFromVercel()
        if (vercelKey.isNotEmpty()) {
            cachedKey = vercelKey; cacheLoadedAt = now
            return@withContext cachedKey
        }

        Log.e(TAG, "All key sources failed"); ""
    }

    private suspend fun fetchKeyFromVercel(): String = withContext(Dispatchers.IO) {
        try {
            val idToken = FirebaseAuth.getInstance().currentUser
                ?.getIdToken(false)?.await()?.token
                ?: return@withContext ""
            val conn = (URL(VERCEL_TMDB_ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer $idToken")
                connectTimeout = 8_000; readTimeout = 8_000
            }
            if (conn.responseCode == 200)
                JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    .optString("key").ifBlank { "" }
            else ""
        } catch (e: Exception) { Log.e(TAG, "Vercel key: ${e.message}"); "" }
    }

    private fun mapToMovie(dto: MovieDto) = Movie(
        id          = dto.id,
        title       = dto.title ?: dto.name ?: "Unknown",
        description = dto.overview ?: "",
        posterUrl   = if (dto.posterPath != null) IMAGE_BASE_URL + dto.posterPath else "",
        backdropUrl = if (dto.backdropPath != null) BACKDROP_BASE_URL + dto.backdropPath else "",
        rating      = String.format("%.1f", dto.rating ?: 0.0),
        year        = (dto.releaseDate ?: dto.firstAirDate ?: "").take(4),
        type        = if (dto.name != null) MovieType.SERIES else MovieType.MOVIE
    )

    private suspend fun safeApiCall(
        call: suspend (String) -> TmdbResponse
    ): List<Movie> = withContext(Dispatchers.IO) {
        val key = getApiKey()
        if (key.isEmpty()) return@withContext emptyList()
        try { call(key).results.filter { it.posterPath != null }.map { mapToMovie(it) } }
        catch (e: Exception) { Log.e(TAG, "API call: ${e.message}"); emptyList() }
    }

    suspend fun getTrending()           = safeApiCall { api.getTrending(it) }
    suspend fun getPopularMovies()      = safeApiCall { api.getPopularMovies(it) }
    suspend fun getTopSeries()          = safeApiCall { api.getTopRatedSeries(it) }
    suspend fun getActionMovies()       = safeApiCall { api.getActionMovies(it) }
    suspend fun getSciFiMovies()        = safeApiCall { api.getSciFiMovies(it) }
    suspend fun searchMovies(q: String) = safeApiCall { api.searchMulti(it, q) }

    suspend fun getFullDetails(movieId: Int, type: MovieType): FullMovieDetails? =
        withContext(Dispatchers.IO) {
            val key = getApiKey()
            val typeStr = if (type == MovieType.MOVIE) "movie" else "tv"

            val tmdb = try {
                if (key.isNotEmpty()) api.getDetails(typeStr, movieId, key) else null
            } catch (e: Exception) {
                Log.e(TAG, "TMDB detail failed: ${e.message}")
                null
            }

            val imdbId = tmdb?.external_ids?.imdbId
            if (!imdbId.isNullOrEmpty()) tmdbToImdbCache[movieId] = imdbId
            val effectiveImdbId = imdbId ?: tmdbToImdbCache[movieId]

            val cinemeta = if (!effectiveImdbId.isNullOrEmpty()) {
                try { CinemetaRepository.get(effectiveImdbId, typeStr) }
                catch (e: Exception) { Log.w(TAG, "Cinemeta: ${e.message}"); null }
            } else null

            if (tmdb == null && cinemeta != null) {
                Log.i(TAG, "TMDB down — building from Cinemeta ($effectiveImdbId)")
                return@withContext buildFromCinemetaOnly(cinemeta, movieId, type)
            }
            if (tmdb == null) return@withContext null

            val bestBackdrop = when {
                !tmdb.backdropPath.isNullOrEmpty()              -> BACKDROP_BASE_URL + tmdb.backdropPath
                !cinemeta?.background.isNullOrEmpty()           -> cinemeta!!.background
                !cinemeta?.poster.isNullOrEmpty()               -> cinemeta!!.poster
                else                                            -> ""
            }

            val bestPoster = if (!tmdb.posterPath.isNullOrEmpty())
                IMAGE_BASE_URL + tmdb.posterPath
            else cinemeta?.poster ?: ""

            val title = when {
                !tmdb.title.isNullOrEmpty()   -> tmdb.title
                !tmdb.name.isNullOrEmpty()    -> tmdb.name
                !cinemeta?.name.isNullOrEmpty() -> cinemeta!!.name
                else -> "Unknown"
            }

            val description = when {
                !tmdb.overview.isNullOrEmpty() -> tmdb.overview
                !cinemeta?.description.isNullOrEmpty() -> cinemeta!!.description
                else -> ""
            }

            val rating = when {
                tmdb.rating != null && tmdb.rating > 0 -> tmdb.rating
                cinemeta?.rating != null && cinemeta.rating > 0 -> cinemeta.rating
                else -> 0.0
            }

            val year = (tmdb.releaseDate ?: tmdb.firstAirDate ?: cinemeta?.year ?: "").take(4)

            val runtime = when {
                tmdb.runtime != null && tmdb.runtime > 0 -> "${tmdb.runtime} min"
                cinemeta?.runtimeFormatted?.isNotEmpty() == true -> cinemeta.runtimeFormatted
                else -> "N/A"
            }

            val genres = if (!tmdb.genres.isNullOrEmpty())
                tmdb.genres.map { it.name }
            else cinemeta?.genres ?: emptyList()

            val cast = tmdb.credits?.cast?.take(10)?.map {
                CastMember(
                    name     = it.name,
                    role     = it.character ?: "",
                    imageUrl = if (!it.profilePath.isNullOrEmpty()) IMAGE_BASE_URL + it.profilePath else "",
                    personId = it.id
                )
            } ?: emptyList()

            val director = tmdb.credits?.crew
                ?.find { it.job == "Director" }?.name
                ?: cinemeta?.director
                ?: "Unknown"

            val trailerKey = tmdb.videos?.results
                ?.find { it.site == "YouTube" && it.type == "Trailer" }?.key
                ?: cinemeta?.trailerStreams?.firstOrNull()?.ytId

            val recommendations = tmdb.recommendations?.results
                ?.take(10)?.map { mapToMovie(it) }
                ?: emptyList()

            val seasons = tmdb.seasons ?: if (type == MovieType.SERIES && cinemeta != null) {
                cinemeta.videos.map { it.season }.distinct().sorted().map { sNum ->
                    SeasonDto(
                        seasonNumber = sNum,
                        episodeCount = cinemeta.videos.count { it.season == sNum },
                        name = "Season $sNum"
                    )
                }
            } else emptyList()

            val logo        = cinemeta?.logo ?: ""
            val awards      = cinemeta?.awards ?: ""
            val country     = cinemeta?.country ?: ""
            val status      = cinemeta?.status ?: ""
            val imdbRating  = cinemeta?.rating ?: 0.0

            val basic = Movie(
                id          = tmdb.id,
                title       = title,
                description = description,
                posterUrl   = bestPoster,
                backdropUrl = bestBackdrop,
                rating      = String.format("%.1f", rating),
                year        = year,
                type        = type,
                logo        = logo
            )

            FullMovieDetails(
                basic            = basic,
                runtime          = runtime,
                genres           = genres,
                cast             = cast,
                director         = director,
                trailerKey       = trailerKey,
                recommendations  = recommendations,
                seasons          = seasons,
                imdbId           = imdbId,
                logo             = logo,
                awards           = awards,
                country          = country,
                status           = status,
                imdbRating       = imdbRating,
                cinemetaEnriched = cinemeta != null
            )
        }

    private fun buildFromCinemetaOnly(
        meta: CinemetaMeta,
        movieId: Int,
        type: MovieType
    ): FullMovieDetails {
        val basic = Movie(
            id          = movieId,
            title       = meta.name,
            description = meta.description,
            posterUrl   = meta.poster,
            backdropUrl = meta.bestBackdrop,
            rating      = if (meta.rating > 0) String.format("%.1f", meta.rating) else "N/A",
            year        = meta.year.take(4),
            type        = type,
            logo        = meta.logo
        )

        val cast = meta.cast.map { name ->
            CastMember(name = name, role = "", imageUrl = "", personId = 0)
        }

        val seasons = if (type == MovieType.SERIES && meta.videos.isNotEmpty()) {
            meta.videos.map { it.season }.distinct().sorted().map { sNum ->
                SeasonDto(
                    seasonNumber = sNum,
                    episodeCount = meta.videos.count { it.season == sNum },
                    name = "Season $sNum"
                )
            }
        } else emptyList()

        return FullMovieDetails(
            basic            = basic,
            runtime          = meta.runtimeFormatted,
            genres           = meta.genres,
            cast             = cast,
            director         = meta.director.ifEmpty { "Unknown" },
            trailerKey       = meta.trailerStreams.firstOrNull()?.ytId,
            recommendations  = emptyList(),
            seasons          = seasons,
            imdbId           = meta.imdbId,
            logo             = meta.logo,
            awards           = meta.awards,
            country          = meta.country,
            status           = meta.status,
            imdbRating       = meta.rating,
            cinemetaEnriched = true
        )
    }

    // ═══════════════════════════════════════════════════════════════
    //  fetchPersonDetails — TMDB primary, Cinemeta fallback
    // ═══════════════════════════════════════════════════════════════
    suspend fun fetchPersonDetails(personId: Int): PersonDetails? =
        withContext(Dispatchers.IO) {
            val key = getApiKey()

            // ── 1. Try TMDB first ─────────────────────────────────
            val tmdbPerson = if (key.isNotEmpty()) {
                try {
                    val r = api.getPersonDetails(personId, key)
                    tmdbPersonNameCache[personId] = r.name
                    r
                } catch (e: Exception) {
                    Log.e(TAG, "TMDB person failed: ${e.localizedMessage}")
                    null
                }
            } else null

            // ── 2. TMDB success → build from TMDB ─────────────────
            if (tmdbPerson != null) {
                val r = tmdbPerson
                val knownForMovies = r.credits?.cast
                    ?.sortedByDescending { it.popularity ?: 0.0 }
                    ?.take(12)
                    ?.map { item ->
                        Movie(
                            id          = item.id,
                            title       = item.title ?: item.name ?: "Unknown",
                            description = "",
                            posterUrl   = if (!item.posterPath.isNullOrBlank())
                                            "$IMAGE_BASE_URL${item.posterPath}" else "",
                            backdropUrl = "",
                            rating      = String.format("%.1f", item.rating ?: 0.0),
                            year        = (item.releaseDate ?: item.firstAirDate)?.take(4) ?: "",
                            type        = if (item.mediaType == "tv") MovieType.SERIES else MovieType.MOVIE
                        )
                    } ?: emptyList()

                return@withContext PersonDetails(
                    id             = r.id,
                    name           = r.name,
                    biography      = r.biography ?: "",
                    birthday       = r.birthday,
                    deathday       = r.deathday,
                    placeOfBirth   = r.placeOfBirth,
                    gender         = r.gender ?: 0,
                    knownFor       = r.knownFor ?: "Acting",
                    popularity     = r.popularity ?: 0.0,
                    profileUrl     = if (!r.profilePath.isNullOrBlank())
                                       "$IMAGE_BASE_URL${r.profilePath}" else "",
                    knownForMovies = knownForMovies,
                    socialLinks    = PersonSocials(
                        instagramId = r.externalIds?.instagramId,
                        twitterId   = r.externalIds?.twitterId,
                        facebookId  = r.externalIds?.facebookId,
                        imdbId      = r.externalIds?.imdbId
                    )
                )
            }

            // ── 3. TMDB failed → try Cinemeta fallback ──────────────
            val cachedName = tmdbPersonNameCache[personId]

            if (!cachedName.isNullOrEmpty()) {
                Log.i(TAG, "TMDB person down — trying Cinemeta for: $cachedName")
                try {
                    val cinemetaPerson = CinemetaRepository.getPerson(cachedName)
                    if (cinemetaPerson != null) {
                        return@withContext buildPersonFromCinemeta(cinemetaPerson, personId)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Cinemeta person fallback failed: ${e.message}")
                }
            }

            Log.e(TAG, "Person $personId: All sources failed")
            null
        }

    private fun buildPersonFromCinemeta(
        cp: CinemetaPerson,
        personId: Int
    ): PersonDetails {
        val knownForMovies = cp.knownMovies.map { movie ->
            Movie(
                id          = movie.id.hashCode(),
                title       = movie.title,
                description = "",
                posterUrl   = movie.poster,
                backdropUrl = "",
                rating      = if (movie.rating > 0) String.format("%.1f", movie.rating) else "N/A",
                year        = movie.year.take(4),
                type        = MovieType.MOVIE
            )
        }

        return PersonDetails(
            id             = personId,
            name           = cp.name,
            biography      = cp.description,
            birthday       = cp.birthDate.ifEmpty { null },
            deathday       = cp.deathDate.ifEmpty { null },
            placeOfBirth   = cp.birthPlace.ifEmpty { null },
            gender         = when (cp.gender.lowercase()) {
                "female" -> 1
                "male"   -> 2
                else     -> 0
            },
            knownFor       = cp.knownFor.ifEmpty { "Acting" },
            popularity     = cp.popularity,
            profileUrl     = cp.photo,
            knownForMovies = knownForMovies,
            socialLinks    = PersonSocials()
        )
    }

    suspend fun getEpisodes(seriesId: Int, seasonNumber: Int): List<EpisodeDto> =
        withContext(Dispatchers.IO) {
            val key = getApiKey()

            if (key.isNotEmpty()) {
                try {
                    val resp = api.getSeasonDetails(seriesId, seasonNumber, key)
                    if (resp.episodes != null) return@withContext resp.episodes
                } catch (e: Exception) {
                    Log.e(TAG, "TMDB episodes failed: ${e.localizedMessage}")
                }
            }

            val imdbId = tmdbToImdbCache[seriesId]
            if (!imdbId.isNullOrEmpty()) {
                try {
                    val meta = CinemetaRepository.get(imdbId, "series")
                    if (meta != null) {
                        return@withContext meta.videos
                            .filter { it.season == seasonNumber }
                            .sortedBy { it.episode }
                            .map { v ->
                                EpisodeDto(
                                    id               = v.id.hashCode(),
                                    name             = v.title,
                                    overview         = v.overview,
                                    episodeNumber    = v.episode,
                                    stillPath        = null,
                                    runtime          = null,
                                    externalStillUrl = v.thumb
                                )
                            }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Cinemeta episodes fallback failed: ${e.localizedMessage}")
                }
            }

            emptyList()
        }
}
