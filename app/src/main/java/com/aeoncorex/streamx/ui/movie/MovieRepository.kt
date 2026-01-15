package com.aeoncorex.streamx.ui.movie

import android.util.Log
import com.aeoncorex.streamx.BuildConfig // BuildConfig ইমপোর্ট করা জরুরি
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

// --- API Interface ---
interface TmdbApi {
    @GET("3/trending/all/day")
    suspend fun getTrending(@Query("api_key") apiKey: String): TmdbResponse

    @GET("3/movie/popular")
    suspend fun getPopularMovies(@Query("api_key") apiKey: String): TmdbResponse

    @GET("3/tv/top_rated")
    suspend fun getTopRatedSeries(@Query("api_key") apiKey: String): TmdbResponse
    
    @GET("3/discover/movie")
    suspend fun getActionMovies(@Query("api_key") apiKey: String, @Query("with_genres") genre: String = "28"): TmdbResponse

    @GET("3/discover/movie")
    suspend fun getSciFiMovies(@Query("api_key") apiKey: String, @Query("with_genres") genre: String = "878"): TmdbResponse
}

object MovieRepository {
    // --- SECURE KEY USAGE ---
    // BuildConfig থেকে কী নেওয়া হচ্ছে। হার্ডকোডেড নয়।
    private val API_KEY = BuildConfig.TMDB_API_KEY 
    
    private const val IMAGE_BASE_URL = "https://image.tmdb.org/t/p/w500"
    private const val BACKDROP_BASE_URL = "https://image.tmdb.org/t/p/original"

    // Free Unlimited Streaming Source (Embed Pattern)
    private const val STREAM_BASE_URL = "https://vidsrc.xyz/embed/movie/" 
    private const val TV_STREAM_BASE_URL = "https://vidsrc.xyz/embed/tv/"

    private val api = Retrofit.Builder()
        .baseUrl("https://api.themoviedb.org/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(TmdbApi::class.java)

    // --- Helper to execute calls safely ---
    private suspend fun safeApiCall(call: suspend () -> TmdbResponse): List<Movie> = withContext(Dispatchers.IO) {
        if (API_KEY.isEmpty()) {
            Log.e("MovieRepo", "TMDB_API_KEY is missing! Check local.properties or GitHub Secrets.")
            return@withContext emptyList()
        }
        try {
            call().results.map { dto ->
                Movie(
                    id = dto.id,
                    title = dto.title ?: dto.name ?: "Unknown",
                    description = dto.overview ?: "No description available.",
                    posterUrl = if (dto.posterPath != null) IMAGE_BASE_URL + dto.posterPath else "",
                    backdropUrl = if (dto.backdropPath != null) BACKDROP_BASE_URL + dto.backdropPath else "",
                    rating = String.format("%.1f", dto.rating ?: 0.0),
                    year = (dto.releaseDate ?: dto.firstAirDate ?: "").take(4),
                    type = if (dto.name != null) MovieType.SERIES else MovieType.MOVIE
                )
            }
        } catch (e: Exception) {
            Log.e("MovieRepo", "Error: ${e.message}")
            emptyList()
        }
    }

    suspend fun getTrending(): List<Movie> = safeApiCall { api.getTrending(API_KEY) }
    suspend fun getPopularMovies(): List<Movie> = safeApiCall { api.getPopularMovies(API_KEY) }
    suspend fun getTopSeries(): List<Movie> = safeApiCall { api.getTopRatedSeries(API_KEY) }
    suspend fun getActionMovies(): List<Movie> = safeApiCall { api.getActionMovies(API_KEY) }
    suspend fun getSciFiMovies(): List<Movie> = safeApiCall { api.getSciFiMovies(API_KEY) }

    // --- Stream Link Generator ---
    fun getStreamUrl(movieId: Int, type: MovieType): String {
        return if (type == MovieType.MOVIE) {
            "$STREAM_BASE_URL$movieId"
        } else {
            // Defaulting to S1E1 for demo logic
            "${TV_STREAM_BASE_URL}$movieId/1/1"
        }
    }
}
