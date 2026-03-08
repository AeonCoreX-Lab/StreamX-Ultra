package com.aeoncorex.streamx.ui.movie

import android.util.Log
import com.aeoncorex.streamx.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

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

    @GET("3/search/multi")
    suspend fun searchMulti(@Query("api_key") apiKey: String, @Query("query") query: String): TmdbResponse

    @GET("3/{type}/{id}")
    suspend fun getDetails(
        @Path("type") type: String,
        @Path("id") id: Int,
        @Query("api_key") apiKey: String,
        @Query("append_to_response") append: String = "credits,videos,recommendations,external_ids,seasons"
    ): MovieDetailResponse

    @GET("3/tv/{id}/season/{season_number}")
    suspend fun getSeasonDetails(
        @Path("id") seriesId: Int,
        @Path("season_number") seasonNumber: Int,
        @Query("api_key") apiKey: String
    ): SeasonDetailResponse
}

object MovieRepository {
    private var API_KEY_CACHE: String = ""

    // --- FIX: MULTI-LAYER KEY FETCHING ---
    // Tries Rust JNI first, falls back to BuildConfig injected from GitHub Secrets
    private fun getApiKey(): String {
        if (API_KEY_CACHE.isNotEmpty() && API_KEY_CACHE != "api_key_not_found") return API_KEY_CACHE
        
        return try {
            val rustKey = StreamXCore.getTmdbKey()
            if (rustKey == "api_key_not_found" || rustKey.isEmpty()) {
                Log.w("MovieRepo", "Rust Key missing, falling back to BuildConfig")
                API_KEY_CACHE = BuildConfig.TMDB_API_KEY
            } else {
                Log.d("MovieRepo", "Successfully loaded key from Rust JNI")
                API_KEY_CACHE = rustKey
            }
            API_KEY_CACHE
        } catch (e: Throwable) {
            Log.e("MovieRepo", "Native key load crashed: ${e.message}, falling back to BuildConfig")
            API_KEY_CACHE = BuildConfig.TMDB_API_KEY
            API_KEY_CACHE
        }
    }
    
    private const val IMAGE_BASE_URL = "https://image.tmdb.org/t/p/w500"
    private const val BACKDROP_BASE_URL = "https://image.tmdb.org/t/p/original"
    
    private val api = Retrofit.Builder()
        .baseUrl("https://api.themoviedb.org/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(TmdbApi::class.java)

    private fun mapToMovie(dto: MovieDto): Movie {
        return Movie(
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

    private suspend fun safeApiCall(call: suspend (String) -> TmdbResponse): List<Movie> = withContext(Dispatchers.IO) {
        val currentKey = getApiKey()
        if (currentKey.isEmpty() || currentKey == "api_key_not_found") {
            Log.e("MovieRepo", "Invalid API Key. Both Rust and BuildConfig failed.")
            return@withContext emptyList()
        }
        try {
            call(currentKey).results.filter { it.posterPath != null }.map { mapToMovie(it) }
        } catch (e: Exception) {
            Log.e("MovieRepo", "API Call Failed: ${e.message}")
            emptyList()
        }
    }

    suspend fun getTrending() = safeApiCall { api.getTrending(it) }
    suspend fun getPopularMovies() = safeApiCall { api.getPopularMovies(it) }
    suspend fun getTopSeries() = safeApiCall { api.getTopRatedSeries(it) }
    suspend fun getActionMovies() = safeApiCall { api.getActionMovies(it) }
    suspend fun getSciFiMovies() = safeApiCall { api.getSciFiMovies(it) }
    suspend fun searchMovies(query: String) = safeApiCall { api.searchMulti(it, query) }

    suspend fun getFullDetails(movieId: Int, type: MovieType): FullMovieDetails? = withContext(Dispatchers.IO) {
        val currentKey = getApiKey()
        if (currentKey == "api_key_not_found" || currentKey.isEmpty()) return@withContext null

        try {
            val typeStr = if (type == MovieType.MOVIE) "movie" else "tv"
            val res = api.getDetails(typeStr, movieId, currentKey)
            
            val basic = Movie(
                id = res.id,
                title = res.title ?: res.name ?: "",
                description = res.overview ?: "",
                posterUrl = IMAGE_BASE_URL + res.posterPath,
                backdropUrl = BACKDROP_BASE_URL + res.backdropPath,
                rating = String.format("%.1f", res.rating ?: 0.0),
                year = (res.releaseDate ?: res.firstAirDate ?: "").take(4),
                type = type
            )

            val castList = res.credits?.cast?.take(10)?.map { 
                CastMember(it.name, it.character ?: "", IMAGE_BASE_URL + it.profilePath) 
            } ?: emptyList()

            FullMovieDetails(
                basic = basic,
                runtime = if (res.runtime != null) "${res.runtime} min" else "N/A",
                genres = res.genres?.map { it.name } ?: emptyList(),
                cast = castList,
                director = res.credits?.crew?.find { it.job == "Director" }?.name ?: "Unknown",
                trailerKey = res.videos?.results?.find { it.site == "YouTube" && it.type == "Trailer" }?.key,
                recommendations = res.recommendations?.results?.take(10)?.map { mapToMovie(it) } ?: emptyList(),
                seasons = res.seasons ?: emptyList(),
                imdbId = res.external_ids?.imdbId 
            )
        } catch (e: Exception) {
            Log.e("MovieRepo", "Detail Error: ${e.localizedMessage}")
            null
        }
    }

    suspend fun getEpisodes(seriesId: Int, seasonNumber: Int): List<EpisodeDto> = withContext(Dispatchers.IO) {
        val currentKey = getApiKey()
        if (currentKey == "api_key_not_found" || currentKey.isEmpty()) return@withContext emptyList()
        try {
            val res = api.getSeasonDetails(seriesId, seasonNumber, currentKey)
            res.episodes ?: emptyList()
        } catch (e: Exception) {
            Log.e("MovieRepo", "Episodes Error: ${e.localizedMessage}")
            emptyList()
        }
    }
}
