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
    val size: String
)

data class StreamLink(
    val title: String,
    val magnet: String,
    val quality: String,
    val seeds: Int,
    val source: String
)

// --- API INTERFACE ---
interface YtsApi {
    @GET("api/v2/list_movies.json")
    suspend fun listMovies(
        @Query("query_term") imdbId: String,
        @Query("limit") limit: Int = 1
    ): YtsResponse
}

// --- REPOSITORY ---
object TorrentRepository {
    private val api = Retrofit.Builder()
        .baseUrl("https://yts.mx/")
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
        "udp://tracker.leechers-paradise.org:6969"
    )

    /**
     * Unified function to get stream links for Movie, Series, or Anime
     */
    suspend fun getStreamLinks(
        type: MovieType,
        title: String,
        imdbId: String?,
        season: Int = 0,
        episode: Int = 0,
        isAnime: Boolean = false
    ): List<StreamLink> = withContext(Dispatchers.IO) {
        val allLinks = mutableListOf<StreamLink>()

        try {
            // 1. Anime Handling (Nyaa)
            if (isAnime) {
                // Query e.g., "One Piece 1070"
                val animeResults = TorrentProviders.fetchAnime(title, episode)
                allLinks.addAll(animeResults.map {
                    StreamLink(it.title, it.magnet, "HD", it.seeds, "NYAA")
                })
            }
            // 2. Movies (YTS)
            else if (type == MovieType.MOVIE && imdbId != null) {
                val ytsResults = fetchYtsLinks(imdbId, title)
                allLinks.addAll(ytsResults)
            }
            // 3. Series (EZTV)
            else if (type == MovieType.SERIES && imdbId != null) {
                val eztvResults = TorrentProviders.fetchSeries(imdbId, season, episode)
                allLinks.addAll(eztvResults.map {
                    StreamLink(it.title, it.magnet, "HD", it.seeds, "EZTV")
                })
            }

        } catch (e: Exception) {
            Log.e("TorrentRepo", "Error fetching streams: ${e.message}")
        }

        // Sort by Seeds (Highest first) to ensure best playback
        return@withContext allLinks.sortedByDescending { it.seeds }
    }

    private suspend fun fetchYtsLinks(imdbId: String, title: String): List<StreamLink> {
        return try {
            val response = api.listMovies(imdbId)
            val movie = response.data?.movies?.firstOrNull()
            movie?.torrents?.map {
                StreamLink(
                    title = title,
                    magnet = constructMagnet(it.hash, title),
                    quality = it.quality,
                    seeds = it.seeds,
                    source = "YTS"
                )
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
