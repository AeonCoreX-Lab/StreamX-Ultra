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
object FlixHQProvider {
    private const val TAG = "FlixHQProvider"

    suspend fun fetch(req: ProviderRequest): List<StreamResult> = withContext(Dispatchers.IO) {
        try {
            val consumet = ModflixConfig.get("consumet")
            val query    = req.title.replace(" ", "+")
            val type     = if (req.isSeries) "show" else "movie"
            val searchUrl = "$consumet/movies/flixhq/${query}?type=$type"
            val searchJson = HttpClient.getJson(searchUrl) ?: return@withContext emptyList()
            val results    = JSONObject(searchJson).optJSONArray("results") ?: return@withContext emptyList()
            val titleL     = req.title.lowercase()

            // Find best match
            var episodeId: String? = null
            var mediaId:   String? = null
            for (i in 0 until results.length()) {
                val item  = results.getJSONObject(i)
                val title = item.optString("title", "").lowercase()
                if (!title.contains(titleL.take(5))) continue

                mediaId = item.optString("id")

                if (!req.isSeries) {
                    // Movie: get episode from info
                    val infoJson = HttpClient.getJson("$consumet/movies/flixhq/info?id=$mediaId") ?: continue
                    val epArr    = JSONObject(infoJson).optJSONArray("episodes")
                    episodeId    = epArr?.optJSONObject(0)?.optString("id")
                } else {
                    // Series: find season+episode
                    val infoJson = HttpClient.getJson("$consumet/movies/flixhq/info?id=$mediaId") ?: continue
                    val epArr    = JSONObject(infoJson).optJSONArray("episodes")
                    if (epArr != null) {
                        for (j in 0 until epArr.length()) {
                            val ep = epArr.getJSONObject(j)
                            if (ep.optInt("season") == req.season && ep.optInt("number") == req.episode) {
                                episodeId = ep.optString("id"); break
                            }
                        }
                    }
                }
                if (episodeId != null) break
            }
            if (episodeId == null || mediaId == null) return@withContext emptyList()

            // Get servers
            val serversJson = HttpClient.getJson("$consumet/movies/flixhq/servers?episodeId=$episodeId&mediaId=$mediaId")
                ?: return@withContext emptyList()
            val servers     = JSONObject(serversJson).optJSONArray("servers") ?: return@withContext emptyList()

            buildList {
                for (i in 0 until servers.length()) {
                    val server    = servers.getJSONObject(i).optString("name")
                    val watchUrl  = "$consumet/movies/flixhq/watch?server=$server&episodeId=$episodeId&mediaId=$mediaId"
                    val watchJson = HttpClient.getJson(watchUrl) ?: continue
                    val watchData = JSONObject(watchJson)
                    val subs      = mutableListOf<SubtitleTrack>()
                    watchData.optJSONArray("subtitles")?.let { arr ->
                        for (j in 0 until arr.length()) {
                            val sub = arr.getJSONObject(j)
                            subs.add(SubtitleTrack(
                                url      = sub.optString("url"),
                                language = sub.optString("lang", "en").take(2),
                                title    = sub.optString("lang", "Unknown"),
                                mimeType = "text/vtt"
                            ))
                        }
                    }
                    watchData.optJSONArray("sources")?.let { arr ->
                        for (j in 0 until arr.length()) {
                            val src  = arr.getJSONObject(j)
                            val url  = src.optString("url").takeIf { it.isNotEmpty() } ?: continue
                            val qual = src.optString("quality", "auto")
                            add(StreamResult(
                                url       = url, quality = qual,
                                type      = if (src.optBoolean("isM3U8")) StreamType.HLS else StreamType.MP4,
                                source    = "FlixHQ ($server)",
                                label     = "$qual — FlixHQ [$server]",
                                subtitles = subs
                            ))
                        }
                    }
                }
            }
        } catch (e: Exception) { Log.w(TAG, e.message ?: "error"); emptyList() }
    }
}
