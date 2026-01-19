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

    // সেরা কানেক্টিভিটির জন্য আপডেটেড ট্র্যাকার লিস্ট
    private val TRACKERS = listOf(
        "udp://open.demonii.com:1337/announce",
        "udp://tracker.openbittorrent.com:80",
        "udp://tracker.coppersurfer.tk:6969",
        "udp://glotorrents.pw:6969/announce",
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://p4p.arenabg.com:1337",
        "udp://tracker.leechers-paradise.org:6969",
        "udp://eddie4.nl:6969/announce",
        "udp://shadowshq.yi.org:6969/announce",
        "udp://tracker.opentrackr.org:1337/announce"
    )

    /**
     * এটি মুভি বা সিরিজের জন্য সবথেকে ভালো ম্যাগনেট লিংকটি খুঁজে বের করবে।
     * MovieDetailsScreen থেকে এটি সরাসরি কল হবে।
     */
    suspend fun getBestMagnetLink(
        title: String,
        imdbId: String?,
        type: MovieType,
        season: Int = 0,
        episode: Int = 0
    ): String? = withContext(Dispatchers.IO) {
        try {
            val allLinks = mutableListOf<StreamLink>()

            // ১. যদি মুভি হয়, তবে YTS থেকে সার্চ করবে (YTS মুভির জন্য সেরা)
            if (type == MovieType.MOVIE && imdbId != null) {
                val ytsResults = fetchYtsLinks(imdbId, title)
                allLinks.addAll(ytsResults)
            }

            // ২. এখানে ভবিষ্যতে আপনি আরও সোর্স (যেমন: PirateBay বা অন্য API) যোগ করতে পারবেন
            // fetchOtherSources(title, season, episode)...

            if (allLinks.isNotEmpty()) {
                // সর্টিং লজিক: 
                // প্রথমে 1080p কে প্রাধান্য দেবে, তারপর 720p। 
                // একই কোয়ালিটির মধ্যে যার Seeds বেশি সেটি আগে আসবে।
                val bestStream = allLinks.sortedWith(
                    compareByDescending<StreamLink> { it.quality == "1080p" }
                        .thenByDescending { it.quality == "720p" }
                        .thenByDescending { it.seeds }
                ).first()

                Log.d("TorrentRepo", "Best Link Found: ${bestStream.quality} with ${bestStream.seeds} seeds")
                return@withContext bestStream.magnet
            }

        } catch (e: Exception) {
            Log.e("TorrentRepo", "Error fetching torrents: ${e.message}")
        }
        return@withContext null
    }

    // YTS থেকে ডাটা নিয়ে আসার প্রাইভেট ফাংশন
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

    // ম্যাগনেট লিংক তৈরির ফাংশন
    private fun constructMagnet(hash: String, title: String): String {
        val encodedTitle = URLEncoder.encode(title, "UTF-8")
        val trackerString = TRACKERS.joinToString("") { "&tr=${URLEncoder.encode(it, "UTF-8")}" }
        return "magnet:?xt=urn:btih:$hash&dn=$encodedTitle$trackerString"
    }
}
