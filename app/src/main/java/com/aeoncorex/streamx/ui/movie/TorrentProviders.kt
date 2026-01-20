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

// Note: TorrentResult is in TorrentModels.kt

interface TorrentApi {
    @GET("api/get-torrents")
    suspend fun getSeriesTorrents(@Query("imdb_id") imdbId: String, @Query("limit") limit: Int = 100): EztvResponse

    @GET("/")
    suspend fun getAnimeTorrents(
        @Query("page") page: String = "rss",
        @Query("q") query: String,
        @Query("c") category: String = "1_2",
        @Query("s") sort: String = "seeders"
    ): String
}

object TorrentProviders {
    // EZTV Client
    private val eztvApi = Retrofit.Builder()
        .baseUrl("https://eztv.re/") // Note: This URL is often blocked. Consider using proxies if empty.
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(TorrentApi::class.java)

    // Nyaa Client
    private val nyaaApi = Retrofit.Builder()
        .baseUrl("https://nyaa.si/")
        .addConverterFactory(ScalarsConverterFactory.create())
        .build()
        .create(TorrentApi::class.java)

    suspend fun fetchSeries(imdbId: String, season: Int, episode: Int): List<TorrentResult> {
        return try {
            val cleanId = imdbId.replace("tt", "")
            val response = eztvApi.getSeriesTorrents(cleanId)
            
            response.torrents?.filter {
                // Strict check to ensure we get the specific episode
                // EZTV often returns strings like "1", "01" etc.
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
            Log.e("Provider", "EZTV Error: ${e.message}")
            emptyList()
        }
    }

    suspend fun fetchAnime(queryName: String, episode: Int): List<TorrentResult> {
        return withContext(Dispatchers.IO) {
            try {
                // Improved Search Query: "Title EpisodeNumber"
                // Pad episode with 0 if needed (e.g., 5 -> 05)
                val epStr = if(episode < 10) "0$episode" else "$episode"
                val xml = nyaaApi.getAnimeTorrents(query = "$queryName $epStr")
                
                val doc = Jsoup.parse(xml, "", org.jsoup.parser.Parser.xmlParser())
                
                doc.select("item").mapNotNull { item ->
                    val title = item.select("title").text()
                    val magnet = item.select("link").text()
                    
                    // Improved Size Parsing
                    val desc = item.select("description").text()
                    // Nyaa Description format often: "150 MiB | ..."
                    val sizeExtract = desc.split("|").firstOrNull()?.trim() ?: "Unknown"

                    if (magnet.startsWith("magnet")) {
                        TorrentResult(
                            title = title,
                            magnet = magnet,
                            seeds = 20, // Nyaa RSS lacks seed count in XML, default to generic valid number
                            peers = 0,
                            size = sizeExtract,
                            source = "NYAA"
                        )
                    } else null
                }
            } catch (e: Exception) {
                Log.e("Provider", "Nyaa Error: ${e.message}")
                emptyList()
            }
        }
    }

    private fun formatSize(bytes: Long): String {
        val mb = bytes / (1024 * 1024)
        return if (mb > 1000) String.format("%.2f GB", mb / 1024.0) else "$mb MB"
    }
}
