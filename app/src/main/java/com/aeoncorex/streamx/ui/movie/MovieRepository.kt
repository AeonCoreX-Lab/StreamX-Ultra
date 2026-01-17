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

// --- API INTERFACES ---
interface OmdbApi {
    @GET("/")
    suspend fun getDetails(
        @Query("apikey") apiKey: String,
        @Query("i") imdbId: String
    ): OmdbResponse
}

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
        @Query("append_to_response") append: String = "credits,videos,recommendations,external_ids"
    ): MovieDetailResponse
}

object MovieRepository {
    private const val TMDB_BASE_URL = "https://api.themoviedb.org/"
    private const val OMDB_BASE_URL = "https://www.omdbapi.com/"
    private const val IMAGE_BASE_URL = "https://image.tmdb.org/t/p/w500"

    // SECURE KEYS FROM BUILD CONFIG (Make sure to set these in local.properties)
    private val TMDB_KEY = BuildConfig.TMDB_API_KEY
    // private val OMDB_KEY = BuildConfig.OMDB_API_KEY // Use this if you added it to BuildConfig
    private val OMDB_KEY = "YOUR_OMDB_KEY_HERE" // Temporary fallback if not in BuildConfig

    private val tmdbApi: TmdbApi by lazy {
        Retrofit.Builder().baseUrl(TMDB_BASE_URL)
            .addConverterFactory(GsonConverterFactory.create()).build().create(TmdbApi::class.java)
    }

    private val omdbApi: OmdbApi by lazy {
        Retrofit.Builder().baseUrl(OMDB_BASE_URL)
            .addConverterFactory(GsonConverterFactory.create()).build().create(OmdbApi::class.java)
    }

    // --- FETCH METHODS ---
    suspend fun getTrending() = fetch { tmdbApi.getTrending(TMDB_KEY) }
    suspend fun getPopularMovies() = fetch { tmdbApi.getPopularMovies(TMDB_KEY) }
    suspend fun getTopSeries() = fetch { tmdbApi.getTopRatedSeries(TMDB_KEY) }
    suspend fun getActionMovies() = fetch { tmdbApi.getActionMovies(TMDB_KEY) }
    suspend fun getSciFiMovies() = fetch { tmdbApi.getSciFiMovies(TMDB_KEY) }
    suspend fun searchMovies(query: String) = fetch { tmdbApi.searchMulti(TMDB_KEY, query) }

    private suspend fun fetch(call: suspend () -> TmdbResponse): List<Movie> = withContext(Dispatchers.IO) {
        try {
            call().results.filter { it.posterPath != null }.map { mapToMovie(it) }
        } catch (e: Exception) { emptyList() }
    }

    // --- FULL DETAILS LOGIC (TMDB + OMDB) ---
    suspend fun getFullDetails(id: Int, typeStr: String): FullMovieDetails? = withContext(Dispatchers.IO) {
        try {
            val type = if (typeStr.uppercase() == "MOVIE") "movie" else "tv"
            
            // 1. Fetch TMDB Data (includes external_ids for IMDB)
            val tmdbData = tmdbApi.getDetails(type, id, TMDB_KEY)
            
            // 2. Fetch OMDb Data using IMDB ID
            val imdbId = tmdbData.externalIds?.imdbId
            var omdbData: OmdbResponse? = null
            if (!imdbId.isNullOrEmpty()) {
                try {
                    omdbData = omdbApi.getDetails(OMDB_KEY, imdbId)
                } catch (e: Exception) { Log.e("Repo", "OMDb Fail: ${e.message}") }
            }

            // 3. Generate Multiple Servers
            val servers = generateServers(id, if(type=="movie") MovieType.MOVIE else MovieType.SERIES)

            // 4. Map everything
            FullMovieDetails(
                basic = mapToMovie(MovieDto(
                    id = tmdbData.id, title = tmdbData.title, name = tmdbData.name,
                    posterPath = tmdbData.posterPath, backdropPath = tmdbData.backdropPath,
                    overview = tmdbData.overview, rating = tmdbData.rating,
                    releaseDate = tmdbData.releaseDate, firstAirDate = tmdbData.firstAirDate
                )),
                runtime = "${tmdbData.runtime ?: "N/A"} min",
                genres = tmdbData.genres?.map { it.name } ?: emptyList(),
                cast = tmdbData.credits?.cast?.take(10)?.map { 
                    CastMember(it.name, it.character ?: "", if(it.profilePath!=null) IMAGE_BASE_URL + it.profilePath else "") 
                } ?: emptyList(),
                director = tmdbData.credits?.crew?.find { it.job == "Director" }?.name ?: "Unknown",
                trailerKey = tmdbData.videos?.results?.find { it.site == "YouTube" && it.type == "Trailer" }?.key,
                recommendations = tmdbData.recommendations?.results?.map { mapToMovie(it) } ?: emptyList(),
                
                // OMDb Fields
                imdbRating = omdbData?.imdbRating ?: "N/A",
                metascore = omdbData?.metaScore ?: "N/A",
                awards = omdbData?.awards ?: "No Awards",
                ageRating = omdbData?.rated ?: "N/A",
                boxOffice = omdbData?.boxOffice ?: "N/A",
                servers = servers
            )
        } catch (e: Exception) {
            Log.e("Repo", "Error getting full details: ${e.message}")
            null
        }
    }

    // --- SERVER GENERATOR (UNLIMITED SOURCES) ---
    private fun generateServers(tmdbId: Int, type: MovieType): List<StreamServer> {
        val list = mutableListOf<StreamServer>()
        val typePath = if (type == MovieType.MOVIE) "movie" else "tv"
        
        // You can add as many as you want here
        list.add(StreamServer("VidSrc (Fast)", "https://vidsrc.xyz/embed/$typePath?tmdb=$tmdbId"))
        
        if (type == MovieType.MOVIE) {
            list.add(StreamServer("SuperEmbed (4K)", "https://multiembed.mov/directstream.php?video_id=$tmdbId&tmdb=1"))
        } else {
            list.add(StreamServer("SuperEmbed (S1E1)", "https://multiembed.mov/directstream.php?video_id=$tmdbId&tmdb=1&s=1&e=1"))
        }

        list.add(StreamServer("2Embed (Backup)", "https://www.2embed.cc/embed/$tmdbId"))
        list.add(StreamServer("Smashy (Auto)", "https://embed.smashystream.com/playere.php?tmdb=$tmdbId"))

        return list
    }

    private fun mapToMovie(dto: MovieDto): Movie {
        val type = if (dto.title != null) MovieType.MOVIE else MovieType.SERIES
        return Movie(
            id = dto.id,
            title = dto.title ?: dto.name ?: "Unknown",
            description = dto.overview ?: "",
            posterUrl = if (dto.posterPath != null) IMAGE_BASE_URL + dto.posterPath else "",
            backdropUrl = if (dto.backdropPath != null) IMAGE_BASE_URL + dto.backdropPath else "",
            rating = String.format("%.1f", dto.rating ?: 0.0),
            year = (dto.releaseDate ?: dto.firstAirDate ?: "").take(4),
            type = type
        )
    }
}
