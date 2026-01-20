package com.aeoncorex.streamx.ui.movie

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.net.URLEncoder

// --- MODELS ---
data class YtsResponse(val data: YtsData?)
data class YtsData(val movies: List<YtsMovie>?)
data class YtsMovie(val id: Int, val title: String, val torrents: List<YtsTorrent>?)
data class YtsTorrent(
    val url: String,
    val hash: String,
    val quality: String, // 720p, 1080p, 2160p
    val seeds: Int,
    val peers: Int,
    val size: String // e.g. "1.5 GB"
)

// UI তে দেখানোর জন্য Unified Model
data class StreamLink(
    val title: String,
    val magnet: String,
    val quality: String,
    val seeds: Int,
    val size: String, // Added Size field for UI
    val source: String
)

// --- API INTERFACE ---
interface YtsApi {
    @GET("api/v2/list_movies.json")
    suspend fun listMovies(
        @Query("query_term") query: String,
        @Query("limit") limit: Int = 20
    ): YtsResponse
}

// --- REPOSITORY ---
object TorrentRepository {
    private const val YTS_BASE_URL = "https://yts.mx/" 
    
    private val api = Retrofit.Builder()
        .baseUrl(YTS_BASE_URL)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(YtsApi::class.java)

    private val TRACKERS = listOf(
        "udp://open.demonii.com:1337/announce",
        "udp://tracker.openbittorrent.com:80",
        "udp://tracker.coppersurfer.tk:6969",
        "udp://glotorrents.pw:6969/announce",
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://p4p.arenabg.com:1337",
        "udp://tracker.leechers-paradise.org:6969",
        "udp://9.rarbg.to:2710",
        "udp://exodus.desync.com:6969"
    )

    suspend fun getStreamLinks(
        type: MovieType,
        title: String,
        imdbId: String?,
        season: Int = 0,
        episode: Int = 0,
        isAnime: Boolean = false
    ): List<StreamLink> = withContext(Dispatchers.IO) {
        val allLinks = mutableListOf<StreamLink>()
        Log.d("StreamX", "Searching: $title | Type: $type")

        try {
            // 1. Anime (NYAA)
            if (isAnime) {
                val results = TorrentProviders.fetchAnime(title, episode)
                allLinks.addAll(results.map {
                    StreamLink(it.title, it.magnet, "HD", it.seeds, it.size, "NYAA")
                })
            }
            // 2. Series (EZTV)
            else if (type == MovieType.SERIES) {
                if (imdbId != null) {
                    val results = TorrentProviders.fetchSeries(imdbId, season, episode)
                    allLinks.addAll(results.map {
                        StreamLink(it.title, it.magnet, "HD", it.seeds, it.size, "EZTV")
                    })
                }
            }
            // 3. Movies (YTS)
            else {
                val ytsLinks = fetchYtsLinks(imdbId, title)
                allLinks.addAll(ytsLinks)
            }

        } catch (e: Exception) {
            Log.e("TorrentRepo", "Error: ${e.message}")
        }

        return@withContext allLinks.sortedByDescending { it.seeds }
    }

    private suspend fun fetchYtsLinks(imdbId: String?, title: String): List<StreamLink> {
        return try {
            var movies: List<YtsMovie>? = null

            // Try via IMDB ID
            if (!imdbId.isNullOrEmpty()) {
                val response = api.listMovies(imdbId)
                movies = response.data?.movies
            }

            // Fallback via Title
            if (movies.isNullOrEmpty()) {
                val cleanTitle = title.replace(Regex("[^a-zA-Z0-9 ]"), "")
                val response = api.listMovies(cleanTitle)
                movies = response.data?.movies
            }

            movies?.flatMap { movie ->
                movie.torrents?.map { torrent ->
                    StreamLink(
                        title = movie.title,
                        magnet = constructMagnet(torrent.hash, movie.title),
                        quality = torrent.quality,
                        seeds = torrent.seeds,
                        size = torrent.size, // YTS provides size directly
                        source = "YTS"
                    )
                } ?: emptyList()
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun constructMagnet(hash: String, title: String): String {
        val encodedTitle = URLEncoder.encode(title, "UTF-8")
        val trackerString = TRACKERS.joinToString("") { "&tr=${URLEncoder.encode(it, "UTF-8")}" }
        return "magnet:?xt=urn:btih:$hash&dn=$encodedTitle$trackerString"
    }
}
