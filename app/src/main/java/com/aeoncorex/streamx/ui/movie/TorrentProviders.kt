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

// Models
data class TorrentResult(
    val title: String,
    val magnet: String,
    val seeds: Int,
    val peers: Int,
    val size: String,
    val source: String
)

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
        .baseUrl("https://eztv.re/")
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
                // Strict Matching for Season/Episode
                it.season == season.toString() && it.episode == episode.toString()
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
                // Search: "Naruto 01" to match episode
                val xml = nyaaApi.getAnimeTorrents(query = "$queryName $episode")
                val doc = Jsoup.parse(xml, "", org.jsoup.parser.Parser.xmlParser())
                
                doc.select("item").mapNotNull { item ->
                    val title = item.select("title").text()
                    val magnet = item.select("link").text()
                    val size = item.select("size").text() // Nyaa RSS usually doesn't have size tag standardly, might need regex on description
                    
                    // Simple regex to find size in description if needed, or default "Unknown"
                    // Nyaa RSS description format: <![CDATA[ 133 MiB | 1280x720 | ... ]]>
                    val desc = item.select("description").text()
                    val sizeExtract = desc.split("|").firstOrNull()?.trim() ?: "Unk"

                    if (magnet.startsWith("magnet")) {
                        TorrentResult(
                            title = title,
                            magnet = magnet,
                            seeds = 20, // Nyaa RSS doesn't give seed count easily in XML, using default/placeholder or need advanced parsing
                            peers = 0,
                            size = sizeExtract,
                            source = "NYAA"
                        )
                    } else null
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    private fun formatSize(bytes: Long): String {
        val mb = bytes / (1024 * 1024)
        return if (mb > 1000) String.format("%.2f GB", mb / 1024.0) else "$mb MB"
    }
}
