package com.aeoncorex.streamx.ui.movie

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import java.net.HttpURLConnection
import java.net.URL

// ── TMDB Retrofit interface (unchanged) ───────────────────────────
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
}

// ═══════════════════════════════════════════════════════════════════
//  MovieRepository
//  ───────────────
//  TMDB API key loading — 3 layers, no BuildConfig dependency:
//
//  Layer 1 (Rust JNI Vault) ── Fast, no network, obfuscated in .so
//      ↓ only if vault returns empty/"not_found"
//  Layer 2 (Vercel /api/tmdb-key) ── Network, Firebase token verify
//      ↓ only if network fails or Vercel unreachable
//  Layer 3 ── Error logged, empty results returned gracefully
//
//  Key is cached in memory after first successful load.
//  Cache is valid for 1 hour, then re-fetches from vault.
// ═══════════════════════════════════════════════════════════════════
object MovieRepository {

    // ── Vercel backend URL (same base as other endpoints) ─────────
    private const val VERCEL_TMDB_ENDPOINT =
        "https://YOUR_APP_NAME.vercel.app/api/tmdb-key"

    // ── In-memory key cache ───────────────────────────────────────
    private var cachedKey:     String = ""
    private var cacheLoadedAt: Long   = 0L
    private const val CACHE_TTL_MS    = 3_600_000L   // 1 hour

    /**
     * 3-layer TMDB key resolution.
     * Runs on IO dispatcher — always safe to call from a coroutine.
     */
    private suspend fun getApiKey(): String = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()

        // Serve from cache if fresh
        if (cachedKey.isNotEmpty() && cachedKey != "api_key_not_found"
            && now - cacheLoadedAt < CACHE_TTL_MS) {
            return@withContext cachedKey
        }

        // ── Layer 1: Rust JNI Vault ───────────────────────────────
        val rustKey = try {
            StreamXCore.getTmdbKey()
        } catch (e: Throwable) {
            Log.w("MovieRepo", "Rust vault threw: ${e.message}")
            ""
        }

        if (rustKey.isNotEmpty() && rustKey != "api_key_not_found") {
            Log.d("MovieRepo", "✓ Key loaded from Rust JNI vault")
            cachedKey     = rustKey
            cacheLoadedAt = now
            return@withContext cachedKey
        }

        Log.w("MovieRepo", "Rust vault empty/missing → trying Vercel fallback")

        // ── Layer 2: Vercel /api/tmdb-key ─────────────────────────
        val vercelKey = fetchKeyFromVercel()
        if (vercelKey.isNotEmpty()) {
            Log.d("MovieRepo", "✓ Key loaded from Vercel fallback")
            cachedKey     = vercelKey
            cacheLoadedAt = now
            return@withContext cachedKey
        }

        // ── Layer 3: Total failure ─────────────────────────────────
        Log.e("MovieRepo", "All key sources failed — no TMDB key available")
        ""
    }

    /**
     * Fetches TMDB key from Vercel backend.
     * Requires a valid Firebase ID Token — authenticates the app user.
     * Returns empty string on any failure (graceful degradation).
     */
    private suspend fun fetchKeyFromVercel(): String = withContext(Dispatchers.IO) {
        try {
            // Get Firebase ID token (all signed-in users have one)
            val idToken = FirebaseAuth.getInstance().currentUser
                ?.getIdToken(false)
                ?.await()
                ?.token
                ?: run {
                    Log.w("MovieRepo", "No Firebase user — cannot fetch key from Vercel")
                    return@withContext ""
                }

            val conn = (URL(VERCEL_TMDB_ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer $idToken")
                connectTimeout = 8_000
                readTimeout    = 8_000
            }

            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                JSONObject(body).optString("key").ifBlank { "" }
            } else {
                Log.w("MovieRepo", "Vercel /api/tmdb-key returned ${conn.responseCode}")
                ""
            }
        } catch (e: Exception) {
            Log.e("MovieRepo", "Vercel key fetch failed: ${e.message}")
            ""
        }
    }

    // ── Retrofit client ───────────────────────────────────────────
    private const val IMAGE_BASE_URL    = "https://image.tmdb.org/t/p/w500"
    private const val BACKDROP_BASE_URL = "https://image.tmdb.org/t/p/original"

    private val api = Retrofit.Builder()
        .baseUrl("https://api.themoviedb.org/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(TmdbApi::class.java)

    // ── Helpers ───────────────────────────────────────────────────
    private fun mapToMovie(dto: MovieDto) = Movie(
        id          = dto.id,
        title       = dto.title ?: dto.name ?: "Unknown",
        description = dto.overview ?: "No description available.",
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
        if (key.isEmpty()) {
            Log.e("MovieRepo", "No API key — returning empty results")
            return@withContext emptyList()
        }
        try {
            call(key).results.filter { it.posterPath != null }.map { mapToMovie(it) }
        } catch (e: Exception) {
            Log.e("MovieRepo", "API call failed: ${e.message}")
            emptyList()
        }
    }

    // ── Public API ────────────────────────────────────────────────
    suspend fun getTrending()           = safeApiCall { api.getTrending(it) }
    suspend fun getPopularMovies()      = safeApiCall { api.getPopularMovies(it) }
    suspend fun getTopSeries()          = safeApiCall { api.getTopRatedSeries(it) }
    suspend fun getActionMovies()       = safeApiCall { api.getActionMovies(it) }
    suspend fun getSciFiMovies()        = safeApiCall { api.getSciFiMovies(it) }
    suspend fun searchMovies(q: String) = safeApiCall { api.searchMulti(it, q) }

    suspend fun getFullDetails(movieId: Int, type: MovieType): FullMovieDetails? =
        withContext(Dispatchers.IO) {
            val key = getApiKey()
            if (key.isEmpty()) return@withContext null
            try {
                val typeStr = if (type == MovieType.MOVIE) "movie" else "tv"
                val res     = api.getDetails(typeStr, movieId, key)

                val basic = Movie(
                    id          = res.id,
                    title       = res.title ?: res.name ?: "",
                    description = res.overview ?: "",
                    posterUrl   = IMAGE_BASE_URL + res.posterPath,
                    backdropUrl = BACKDROP_BASE_URL + res.backdropPath,
                    rating      = String.format("%.1f", res.rating ?: 0.0),
                    year        = (res.releaseDate ?: res.firstAirDate ?: "").take(4),
                    type        = type
                )

                FullMovieDetails(
                    basic           = basic,
                    runtime         = if (res.runtime != null) "${res.runtime} min" else "N/A",
                    genres          = res.genres?.map { it.name } ?: emptyList(),
                    cast            = res.credits?.cast?.take(10)?.map {
                        CastMember(it.name, it.character ?: "", IMAGE_BASE_URL + it.profilePath)
                    } ?: emptyList(),
                    director        = res.credits?.crew?.find { it.job == "Director" }?.name ?: "Unknown",
                    trailerKey      = res.videos?.results
                        ?.find { it.site == "YouTube" && it.type == "Trailer" }?.key,
                    recommendations = res.recommendations?.results?.take(10)
                        ?.map { mapToMovie(it) } ?: emptyList(),
                    seasons         = res.seasons ?: emptyList(),
                    imdbId          = res.external_ids?.imdbId
                )
            } catch (e: Exception) {
                Log.e("MovieRepo", "Detail error: ${e.localizedMessage}")
                null
            }
        }

    suspend fun getEpisodes(seriesId: Int, seasonNumber: Int): List<EpisodeDto> =
        withContext(Dispatchers.IO) {
            val key = getApiKey()
            if (key.isEmpty()) return@withContext emptyList()
            try {
                api.getSeasonDetails(seriesId, seasonNumber, key).episodes ?: emptyList()
            } catch (e: Exception) {
                Log.e("MovieRepo", "Episodes error: ${e.localizedMessage}")
                emptyList()
            }
        }
}