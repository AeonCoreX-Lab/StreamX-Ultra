package com.aeoncorex.streamx.ui.movie

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

// --- API INTERFACES ---
interface TorrentApi {
    // EZTV for Series
    @GET("api/get-torrents")
    suspend fun getSeriesTorrents(
        @Query("imdb_id") imdbId: String, // ID without 'tt'
        @Query("limit") limit: Int = 50   // Limit বাড়ানো হলো যাতে সিজন/এপিসোড মিস না হয়
    ): EztvResponse

    // Nyaa via RSS for Anime (Returns XML String)
    @GET("/")
    suspend fun getAnimeTorrents(
        @Query("page") page: String = "rss",
        @Query("q") query: String,
        @Query("c") category: String = "1_2",
        @Query("s") sort: String = "seeders",
        @Query("o") order: String = "desc"
    ): String
}

object TorrentProviders {
    private val eztvApi = Retrofit.Builder()
        .baseUrl("https://eztv.re/") 
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(TorrentApi::class.java)

    private val nyaaApi = Retrofit.Builder()
        .baseUrl("https://nyaa.si/")
        .addConverterFactory(ScalarsConverterFactory.create()) 
        .build()
        .create(TorrentApi::class.java)

    // --- 1. SERIES PROVIDER (EZTV) ---
    suspend fun fetchSeries(imdbId: String, season: Int, episode: Int): List<TorrentResult> {
        return try {
            // 'tt' বাদ দেওয়া (EZTV শুধু নম্বর চায়)
            val cleanId = imdbId.replace("tt", "")
            
            Log.d("StreamX_EZTV", "Fetching Series: $cleanId S${season}E${episode}")

            val response = eztvApi.getSeriesTorrents(cleanId)
            
            response.torrents?.filter { 
                // String থেকে Int কনভার্শন সেফটি
                val tSeason = it.season.toIntOrNull() ?: 0
                val tEpisode = it.episode.toIntOrNull() ?: 0
                tSeason == season && tEpisode == episode 
            }?.map { 
                TorrentResult(
                    title = it.title,
                    magnet = it.magnet_url,
                    seeds = it.seeds,
                    peers = it.peers,
                    size = formatSize(it.size_bytes),
                    source = "EZTV"
                )
            } ?: emptyList()
        } catch (e: Exception) {
            Log.e("TorrentProvider", "EZTV Error: ${e.message}")
            emptyList()
        }
    }

    // --- 2. ANIME PROVIDER (NYAA) ---
    suspend fun fetchAnime(queryName: String, episode: Int): List<TorrentResult> {
        return withContext(Dispatchers.IO) {
            try {
                // Anime সার্চ লজিক: "Naruto 01" অথবা "Naruto 1"
                // অনেক সময় "S01E01" ফরম্যাটে এনিমে থাকে না, শুধু এপিসোড নম্বর থাকে
                val searchQuery = "$queryName $episode"
                val xmlString = nyaaApi.getAnimeTorrents(query = searchQuery)
                
                val doc = Jsoup.parse(xmlString, "", org.jsoup.parser.Parser.xmlParser())
                val items = doc.select("item")
                
                items.mapNotNull { item ->
                    val title = item.select("title").text()
                    val magnet = item.select("link").text() 
                    
                    if (magnet.startsWith("magnet:?")) {
                         TorrentResult(
                            title = title,
                            magnet = magnet,
                            seeds = 50, // Default value as RSS doesn't give seeders inside item always
                            peers = 0,
                            size = "Unknown",
                            source = "NYAA"
                        )
                    } else null
                }
            } catch (e: Exception) {
                Log.e("TorrentProvider", "Nyaa Error: ${e.message}")
                emptyList()
            }
        }
    }

    private fun formatSize(bytes: Long): String {
        val mb = bytes / (1024 * 1024)
        return if (mb > 1000) String.format("%.1f GB", mb / 1024.0) else "$mb MB"
    }
}
