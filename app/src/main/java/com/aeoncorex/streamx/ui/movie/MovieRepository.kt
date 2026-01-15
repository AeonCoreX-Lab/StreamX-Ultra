package com.aeoncorex.streamx.ui.movie

import android.util.Log
import com.aeoncorex.streamx.BuildConfig 
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
    // build.gradle থেকে জেনারেট হওয়া BuildConfig.TMDB_API_KEY ব্যবহার হচ্ছে
    private val API_KEY = BuildConfig.TMDB_API_KEY 
    
    private const val IMAGE_BASE_URL = "https://image.tmdb.org/t/p/w500"
    private const val BACKDROP_BASE_URL = "https://image.tmdb.org/t/p/original"

    // Streaming Sources
    private const val STREAM_BASE_URL = "https://vidsrc.xyz/embed/movie/" 
    private const val TV_STREAM_BASE_URL = "https://vidsrc.xyz/embed/tv/"

    private val api = Retrofit.Builder()
        .baseUrl("https://api.themoviedb.org/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(TmdbApi::class.java)

    private suspend fun safeApiCall(call: suspend () -> TmdbResponse): List<Movie> = withContext(Dispatchers.IO) {
        if (API_KEY.isEmpty() || API_KEY == "null") {
            Log.e("MovieRepo", "API KEY MISSING! Check build.gradle or Environment Variables.")
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
            Log.e("MovieRepo", "API Error: ${e.localizedMessage}")
            emptyList()
        }
    }

    suspend fun getTrending(): List<Movie> = safeApiCall { api.getTrending(API_KEY) }
    suspend fun getPopularMovies(): List<Movie> = safeApiCall { api.getPopularMovies(API_KEY) }
    suspend fun getTopSeries(): List<Movie> = safeApiCall { api.getTopRatedSeries(API_KEY) }
    suspend fun getActionMovies(): List<Movie> = safeApiCall { api.getActionMovies(API_KEY) }
    suspend fun getSciFiMovies(): List<Movie> = safeApiCall { api.getSciFiMovies(API_KEY) }

    fun getStreamUrl(movieId: Int, type: MovieType): String {
        return if (type == MovieType.MOVIE) {
            "$STREAM_BASE_URL$movieId"
        } else {
            // Note: Currently defaulting to Season 1 Episode 1. 
            // Future update: Add logic to select season/episode.
            "${TV_STREAM_BASE_URL}$movieId/1/1"
        }
    }
}
