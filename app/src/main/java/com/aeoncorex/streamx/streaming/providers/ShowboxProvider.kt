package com.aeoncorex.streamx.streaming.providers

import android.util.Log
import com.aeoncorex.streamx.streaming.HttpClient
import com.aeoncorex.streamx.streaming.ProviderRequest
import com.aeoncorex.streamx.streaming.StreamResult
import com.aeoncorex.streamx.streaming.StreamType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup

// ─────────────────────────────────────────────────────────────────────────────
//  ShowboxProvider.kt
//  English movies + series. Uses Showbox search → feb.8man.workers.dev API.
//  Returns direct quality-labelled download links (no JS execution needed).
//
//  Ported from: vega-providers/dist/showbox/stream.js + posts.js
// ─────────────────────────────────────────────────────────────────────────────
object ShowboxProvider {

    private const val TAG        = "ShowboxProvider"
    private const val SEARCH_URL = "https://www.showbox.media/index/search"
    private const val DETAIL_URL = "https://www.showbox.media"
    private const val API_BASE   = "https://feb.8man.workers.dev"

    suspend fun fetch(req: ProviderRequest): List<StreamResult> = withContext(Dispatchers.IO) {
        try {
            // Step 1: Search for the movie/series
            val title   = req.title
            val searchQ = HttpClient.getJson("$SEARCH_URL?keyword=${title.replace(" ", "+")}")
                ?: return@withContext emptyList()

            val searchJson = JSONObject(searchQ)
            val list = searchJson.optJSONObject("data")?.optJSONArray("list")
                ?: return@withContext emptyList()

            // Step 2: Find best match
            var fid: String? = null
            for (i in 0 until list.length()) {
                val item    = list.getJSONObject(i)
                val iTitle  = item.optString("title", "").lowercase()
                val type    = item.optString("box_type", "")
                val isMovie = !req.isSeries && type == "1"
                val isSeries = req.isSeries && type == "2"
                if ((isMovie || isSeries) && iTitle.contains(title.lowercase().take(5))) {
                    val id = item.optString("id")
                    if (!req.isSeries) {
                        // Movie: fid directly
                        fid = id
                        break
                    } else {
                        // Series: need episode fid
                        fid = getSeriesFid(id, req.season, req.episode)
                        if (fid != null) break
                    }
                }
            }

            if (fid.isNullOrEmpty()) {
                Log.d(TAG, "Showbox: no match for '${req.title}'")
                return@withContext emptyList()
            }

            // Step 3: Fetch from feb.8man.workers.dev
            val apiUrl  = "$API_BASE/?fid=$fid"
            Log.d(TAG, "Showbox API: $apiUrl")
            val apiResp = HttpClient.getJson(apiUrl) ?: return@withContext emptyList()
            val apiDoc  = Jsoup.parse(JSONObject(apiResp).optString("html", ""))

            buildList {
                apiDoc.select(".file_quality").forEach { el ->
                    val server = el.selectFirst("p.name")?.text()  ?: "Showbox"
                    val size   = el.selectFirst("p.size")?.text()  ?: ""
                    val speed  = el.selectFirst("p.speed")?.text() ?: ""
                    val link   = el.attr("data-url")
                    if (link.isEmpty()) return@forEach

                    val quality = when {
                        server.contains("4K", true)   || server.contains("2160", true) -> "4K"
                        server.contains("1080", true)                                   -> "1080p"
                        server.contains("720", true)                                    -> "720p"
                        server.contains("480", true)                                    -> "480p"
                        else                                                             -> "HD"
                    }
                    add(StreamResult(
                        url    = link,
                        quality = quality,
                        type   = StreamType.MKV,
                        source = "Showbox",
                        label  = "$quality — Showbox [$server $size $speed]".trim()
                    ))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Showbox error: ${e.message}")
            emptyList()
        }
    }

    private fun getSeriesFid(showId: String, season: Int, episode: Int): String? {
        return try {
            val seaResp = HttpClient.getJson(
                "https://www.showbox.media/index/episode?id=$showId&season=$season"
            ) ?: return null
            val episodes = JSONObject(seaResp).optJSONObject("data")?.optJSONArray("list")
                ?: return null
            for (i in 0 until episodes.length()) {
                val ep = episodes.getJSONObject(i)
                if (ep.optInt("episode") == episode) {
                    return ep.optString("id")
                }
            }
            null
        } catch (e: Exception) { null }
    }
}
