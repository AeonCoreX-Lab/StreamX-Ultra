package com.aeoncorex.streamx.ui.movie

import android.util.Log
import kotlinx.coroutines.*
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.Url
import java.net.URLEncoder

// --- MODELS ---
data class YtsResponse(val data: YtsData?)
data class YtsData(val movies: List<YtsMovie>?)
data class YtsMovie(val id: Int, val title: String, val torrents: List<YtsTorrent>?)
data class YtsTorrent(
    val url: String,
    val hash: String,
    val quality: String,
    val seeds: Int,
    val peers: Int,
    val size: String
)

// Note: StreamLink is now defined in MovieModels.kt

// --- API INTERFACE (Dynamic URL) ---
interface YtsApi {
    @GET
    suspend fun listMovies(
        @Url url: String,
        @Query("query_term") query: String,
        @Query("limit") limit: Int = 20
    ): YtsResponse
}

// --- REPOSITORY ---
object TorrentRepository {
    private val YTS_MIRRORS = listOf(
        "https://yts.mx/api/v2/list_movies.json",
        "https://yts.lt/api/v2/list_movies.json",
        "https://yts.rs/api/v2/list_movies.json",
        "https://yts.bz/api/v2/list_movies.json"
    )

    private val api = Retrofit.Builder()
        .baseUrl("https://yts.bz/")
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
        "udp://9.rarbg.to:2710"
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
        
        coroutineScope {
            val jobs = mutableListOf<Deferred<List<StreamLink>>>()

            // 1. Anime (NYAA)
            if (isAnime) {
                jobs.add(async {
                    try { TorrentProviders.fetchAnime(title, episode).map {
                        StreamLink(it.title, it.magnet, "HD", it.seeds, it.peers, it.size, "NYAA")
                    }} catch (e: Exception) { emptyList() }
                })
            }

            // 2. Series (EZTV)
            if (type == MovieType.SERIES && imdbId != null) {
                jobs.add(async {
                    try { TorrentProviders.fetchSeries(imdbId, season, episode).map {
                        StreamLink(it.title, it.magnet, "HD", it.seeds, it.peers, it.size, "EZTV")
                    }} catch (e: Exception) { emptyList() }
                })
            }

            // 3. Movies (YTS Mirrors)
            if (type == MovieType.MOVIE) {
                jobs.add(async { fetchYtsWithMirrors(imdbId, title) })
            }

            // 4. Backup (Consumet)
            jobs.add(async {
                try {
                    val searchTitle = if(type == MovieType.SERIES) "$title season $season" else title
                    ConsumetProvider.getStreamLinks(searchTitle, type.toString())
                } catch (e: Exception) { emptyList() }
            })

            jobs.awaitAll().forEach { allLinks.addAll(it) }
        }

        return@withContext allLinks
            .distinctBy { it.magnet }
            .sortedByDescending { it.seeds }
    }

    private suspend fun fetchYtsWithMirrors(imdbId: String?, title: String): List<StreamLink> {
        for (url in YTS_MIRRORS) {
            try {
                val links = fetchYtsInternal(url, imdbId, title)
                if (links.isNotEmpty()) return links 
            } catch (e: Exception) {
                Log.e("TorrentRepo", "Mirror failed: $url, trying next...")
                continue
            }
        }
        return emptyList()
    }

    private suspend fun fetchYtsInternal(url: String, imdbId: String?, title: String): List<StreamLink> {
        var movies: List<YtsMovie>? = null

        if (!imdbId.isNullOrEmpty() && imdbId != "null") {
            val response = api.listMovies(url, imdbId)
            movies = response.data?.movies
        }

        if (movies.isNullOrEmpty()) {
            val cleanTitle = title.replace(Regex("[^a-zA-Z0-9 ]"), "")
            val response = api.listMovies(url, cleanTitle)
            movies = response.data?.movies
        }

        return movies?.flatMap { movie ->
            movie.torrents?.map { torrent ->
                StreamLink(
                    title = movie.title,
                    magnet = constructMagnet(torrent.hash, movie.title),
                    quality = torrent.quality,
                    seeds = torrent.seeds,
                    peers = torrent.peers,
                    size = torrent.size,
                    source = "YTS"
                )
            } ?: emptyList()
        } ?: emptyList()
    }

    private fun constructMagnet(hash: String, title: String): String {
        val encodedTitle = URLEncoder.encode(title, "UTF-8")
        val trackerString = TRACKERS.joinToString("") { "&tr=${URLEncoder.encode(it, "UTF-8")}" }
        return "magnet:?xt=urn:btih:$hash&dn=$encodedTitle$trackerString"
    }
}
