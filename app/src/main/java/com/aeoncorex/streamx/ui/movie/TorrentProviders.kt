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
import retrofit2.http.Url

interface TorrentApi {
    @GET
    suspend fun getSeriesTorrents(@Url url: String, @Query("imdb_id") imdbId: String, @Query("limit") limit: Int = 100): EztvResponse

    @GET("/")
    suspend fun getAnimeTorrents(
        @Query("page") page: String = "rss",
        @Query("q") query: String,
        @Query("c") category: String = "1_2",
        @Query("s") sort: String = "seeders"
    ): String
    
    // BitSearch API (Actually scraping, but structure helps)
    @GET("/")
    suspend fun searchBitSearch(@Query("q") query: String): String
}

object TorrentProviders {
    // Mirrors for EZTV
    private val EZTV_MIRRORS = listOf(
        "https://eztv.re/api/get-torrents",
        "https://eztvx.to/api/get-torrents",
        "https://eztv1.xyz/api/get-torrents"
    )

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
        
    private val bitSearchApi = Retrofit.Builder()
        .baseUrl("https://bitsearch.to/")
        .addConverterFactory(ScalarsConverterFactory.create())
        .build()
        .create(TorrentApi::class.java)

    suspend fun fetchSeries(imdbId: String, season: Int, episode: Int): List<StreamLink> {
        val cleanId = imdbId.replace("tt", "")
        
        for (url in EZTV_MIRRORS) {
            try {
                val response = eztvApi.getSeriesTorrents(url, cleanId)
                val items = response.torrents?.filter {
                    // Strict filtering
                    (it.season == "$season" && it.episode == "$episode") ||
                    (it.season.toIntOrNull() == season && it.episode.toIntOrNull() == episode)
                }?.map {
                    StreamLink(
                        title = it.title,
                        magnet = it.magnet_url,
                        quality = if(it.title.contains("1080p")) "1080p" else "720p",
                        seeds = it.seeds,
                        peers = it.peers,
                        size = formatSize(it.size_bytes),
                        source = "EZTV"
                    )
                }
                if (!items.isNullOrEmpty()) return items
            } catch (e: Exception) {
                Log.e("EZTV", "Mirror failed: $url")
            }
        }
        return emptyList()
    }

    suspend fun fetchAnime(queryName: String, episode: Int): List<StreamLink> {
        return withContext(Dispatchers.IO) {
            try {
                val epStr = if(episode < 10) "0$episode" else "$episode"
                val xml = nyaaApi.getAnimeTorrents(query = "$queryName $epStr")
                val doc = Jsoup.parse(xml, "", org.jsoup.parser.Parser.xmlParser())
                
                doc.select("item").mapNotNull { item ->
                    val title = item.select("title").text()
                    val magnet = item.select("link").text()
                    val desc = item.select("description").text()
                    
                    // Regex for size: "150.5 MiB" or "1.2 GiB"
                    val sizeRegex = Regex("(\\d+(\\.\\d+)?\\s*(MiB|GiB|MB|GB))")
                    val sizeMatch = sizeRegex.find(desc)
                    val size = sizeMatch?.value ?: "Unknown"

                    if (magnet.startsWith("magnet")) {
                        StreamLink(
                            title = title,
                            magnet = magnet,
                            quality = if(title.contains("1080")) "1080p" else "HD",
                            seeds = 20, // Nyaa RSS doesn't give seed count in standard XML
                            peers = 5,
                            size = size,
                            source = "NYAA"
                        )
                    } else null
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
    
    // NEW: BitSearch Scraper (Backup for everything)
    suspend fun fetchBitSearch(query: String): List<StreamLink> {
        return withContext(Dispatchers.IO) {
            try {
                val html = bitSearchApi.searchBitSearch(query)
                val doc = Jsoup.parse(html)
                
                doc.select("li.search-result").mapNotNull { element ->
                    val title = element.select("h5.title a").text()
                    val magnet = element.select("a.dl-magnet").attr("href")
                    val stats = element.select("div.stats div")
                    
                    if (magnet.startsWith("magnet")) {
                        val size = stats.getOrNull(1)?.text() ?: "Unknown"
                        val seeds = stats.getOrNull(2)?.text()?.toIntOrNull() ?: 0
                        val peers = stats.getOrNull(3)?.text()?.toIntOrNull() ?: 0
                        
                        if(seeds > 0) {
                            StreamLink(
                                title = title,
                                magnet = magnet,
                                quality = "HD",
                                seeds = seeds,
                                peers = peers,
                                size = size,
                                source = "BitSearch"
                            )
                        } else null
                    } else null
                }
            } catch (e: Exception) {
                Log.e("BitSearch", "Scrape failed: ${e.message}")
                emptyList()
            }
        }
    }

    private fun formatSize(bytes: Long): String {
        val mb = bytes / (1024 * 1024)
        return if (mb > 1000) String.format("%.2f GB", mb / 1024.0) else "$mb MB"
    }
}
