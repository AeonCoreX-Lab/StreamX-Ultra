package com.aeoncorex.streamx.streaming.providers

import android.util.Log
import com.aeoncorex.streamx.streaming.HttpClient
import com.aeoncorex.streamx.streaming.ModflixConfig
import com.aeoncorex.streamx.streaming.ProviderRequest
import com.aeoncorex.streamx.streaming.StreamResult
import com.aeoncorex.streamx.streaming.StreamType
import com.aeoncorex.streamx.streaming.SubtitleTrack
import com.aeoncorex.streamx.streaming.extractors.HubCloudExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup

// ═════════════════════════════════════════════════════════════════════════════
//  MoreProviders.kt — All remaining providers
//  Ported faithfully from vega-providers dist/*.js
// ═════════════════════════════════════════════════════════════════════════════

// ─────────────────────────────────────────────────────────────────────────────
object AnimetsuProvider {
    private const val TAG      = "AnimetsuProvider"
    private const val API_BASE = "https://backend.animetsu.to"
    private const val M3U8_PROXY = "https://m3u8.8man.workers.dev"
    private val SERVERS = listOf("pahe", "zoro")

    suspend fun fetch(req: ProviderRequest): List<StreamResult> = coroutineScope {
        try {
            // Search
            val searchJson = withContext(Dispatchers.IO) {
                HttpClient.getJson("$API_BASE/api/anime/search?q=${req.title.replace(" ", "+")}&page=1",
                    mapOf("Referer" to "https://animetsu.to/"))
            } ?: return@coroutineScope emptyList()

            val titleL = req.title.lowercase()
            var animeId: String? = null
            val searchData = JSONObject(searchJson).optJSONArray("data")
                ?: JSONObject(searchJson).optJSONArray("results")
            searchData?.let { arr ->
                for (i in 0 until arr.length()) {
                    val item  = arr.getJSONObject(i)
                    val title = (item.optString("title") + item.optString("englishTitle")).lowercase()
                    if (title.contains(titleL.take(5))) {
                        animeId = item.optString("id").takeIf { it.isNotEmpty() }
                            ?: item.optInt("id").toString()
                        break
                    }
                }
            }
            if (animeId == null) return@coroutineScope emptyList()

            val epNum = if (req.isSeries) req.episode else 1

            // Fetch sub + dub streams in parallel from all servers
            SERVERS.flatMap { server ->
                listOf("sub", "dub").map { subType ->
                    async(Dispatchers.IO) {
                        try {
                            val url  = "$API_BASE/api/anime/tiddies?server=$server&id=$animeId&num=$epNum&subType=$subType"
                            val json = HttpClient.getJson(url, mapOf("Referer" to "https://animetsu.to/")) ?: return@async emptyList<StreamResult>()
                            val data = JSONObject(json)
                            val sources = data.optJSONArray("sources") ?: return@async emptyList<StreamResult>()
                            buildList {
                                for (i in 0 until sources.length()) {
                                    val s    = sources.getJSONObject(i)
                                    val srcUrl = s.optString("url").takeIf { it.isNotEmpty() } ?: continue
                                    // Proxy through m3u8 worker
                                    val proxied = "$M3U8_PROXY?url=${java.net.URLEncoder.encode(srcUrl, "UTF-8")}"
                                    val qual    = s.optString("quality", "HD")
                                    val lang    = if (subType == "dub") "English" else "Japanese"
                                    add(StreamResult(
                                        url      = proxied, quality = qual, type = StreamType.HLS,
                                        source   = "Animetsu ($server-$subType)",
                                        language = lang,
                                        label    = "$qual — Animetsu [$server ${subType.uppercase()}]",
                                        headers  = mapOf("Referer" to "https://animetsu.to/")
                                    ))
                                }
                            }
                        } catch (e: Exception) { emptyList() }
                    }
                }
            }.awaitAll().flatten()
        } catch (e: Exception) { Log.w(TAG, e.message ?: "error"); emptyList() }
    }
}
