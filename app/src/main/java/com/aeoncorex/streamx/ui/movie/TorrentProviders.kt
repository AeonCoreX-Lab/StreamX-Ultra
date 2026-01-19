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
        @Query("limit") limit: Int = 30
    ): EztvResponse

    // Nyaa via RSS for Anime (Returns XML String)
    @GET("/")
    suspend fun getAnimeTorrents(
        @Query("page") page: String = "rss",
        @Query("q") query: String,
        @Query("c") category: String = "1_2", // English Translated Anime
        @Query("s") sort: String = "seeders",
        @Query("o") order: String = "desc"
    ): String
}

object TorrentProviders {
    private val eztvApi = Retrofit.Builder()
        .baseUrl("https://eztv.re/") // Or https://eztv.wf/
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(TorrentApi::class.java)

    private val nyaaApi = Retrofit.Builder()
        .baseUrl("https://nyaa.si/")
        .addConverterFactory(ScalarsConverterFactory.create()) // For XML
        .build()
        .create(TorrentApi::class.java)

    // --- 1. SERIES PROVIDER (EZTV) ---
    suspend fun fetchSeries(imdbId: String, season: Int, episode: Int): List<TorrentResult> {
        return try {
            val cleanId = imdbId.replace("tt", "")
            val response = eztvApi.getSeriesTorrents(cleanId)
            
            response.torrents?.filter { 
                // Filter specifically for the requested Season/Episode
                it.season.toIntOrNull() == season && it.episode.toIntOrNull() == episode 
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

    // --- 2. ANIME PROVIDER (NYAA via RSS & Jsoup) ---
    suspend fun fetchAnime(queryName: String, episode: Int): List<TorrentResult> {
        return withContext(Dispatchers.IO) {
            try {
                // Formatting Query: "One Piece 1071" or "Naruto S01E05"
                val searchQuery = "$queryName $episode"
                val xmlString = nyaaApi.getAnimeTorrents(query = searchQuery)
                
                // Parse XML using Jsoup
                val doc = Jsoup.parse(xmlString, "", org.jsoup.parser.Parser.xmlParser())
                val items = doc.select("item")
                
                items.mapNotNull { item ->
                    val title = item.select("title").text()
                    val magnet = item.select("link").text() // Nyaa RSS puts magnet in link
                    
                    if (magnet.startsWith("magnet:?")) {
                         TorrentResult(
                            title = title,
                            magnet = magnet,
                            seeds = 100, // RSS doesn't guarantee seed count, assuming high
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
