package com.aeoncorex.streamx.streaming.providers

import android.util.Log
import com.aeoncorex.streamx.streaming.HttpClient
import com.aeoncorex.streamx.streaming.ModflixConfig
import com.aeoncorex.streamx.streaming.ProviderRequest
import com.aeoncorex.streamx.streaming.StreamResult
import com.aeoncorex.streamx.streaming.StreamType
import com.aeoncorex.streamx.streaming.SubtitleTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject

// ─────────────────────────────────────────────────────────────────────────────
//  HiAnimeProvider.kt
//  Anime HLS streams + subtitle tracks via consumet.zendax.tech / zoro
//  Servers: vidcloud, vidstreaming (parallel)
//
//  Ported from: vega-providers/dist/hiAnime/stream.js + posts.js + meta.js
// ─────────────────────────────────────────────────────────────────────────────
object HiAnimeProvider {

    private const val TAG = "HiAnimeProvider"
    private val SERVERS  = listOf("vidcloud", "vidstreaming")

    suspend fun fetch(req: ProviderRequest): List<StreamResult> = coroutineScope {
        try {
            val consumet = ModflixConfig.get("consumet")

            // Step 1: Search for anime on zoro/hiAnime
            val titleEnc = req.title.replace(" ", "+")
            val searchUrl = "$consumet/anime/zoro/${titleEnc}"
            Log.d(TAG, "HiAnime search: $searchUrl")

            val searchJson = withContext(Dispatchers.IO) {
                HttpClient.getJson(searchUrl)
            } ?: return@coroutineScope emptyList()

            // Step 2: Find best match and get episode ID
            val episodeId = withContext(Dispatchers.IO) {
                findEpisodeId(searchJson, consumet, req)
            } ?: run {
                Log.d(TAG, "HiAnime: no episode found for '${req.title}'")
                return@coroutineScope emptyList()
            }

            Log.d(TAG, "HiAnime episode ID: $episodeId")

            // Step 3: Fetch streams from both servers in parallel
            SERVERS.map { server ->
                async(Dispatchers.IO) {
                    fetchServer(consumet, episodeId, server)
                }
            }.awaitAll().flatten()
        } catch (e: Exception) {
            Log.w(TAG, "HiAnime error: ${e.message}")
            emptyList()
        }
    }

    private fun findEpisodeId(searchJson: String, consumet: String, req: ProviderRequest): String? {
        return try {
            val results = JSONObject(searchJson).optJSONArray("results") ?: return null
            val titleL  = req.title.lowercase()

            // Find best match
            var animeId: String? = null
            for (i in 0 until results.length()) {
                val item  = results.getJSONObject(i)
                val title = item.optString("title", "").lowercase()
                if (title.contains(titleL.take(5))) {
                    animeId = item.optString("id")
                    break
                }
            }
            if (animeId == null) {
                animeId = results.optJSONObject(0)?.optString("id") ?: return null
            }

            if (!req.isSeries) {
                // Movie: use anime ID directly as episode ID
                return animeId
            }

            // Series: fetch episodes list → find episode number
            val epListJson = HttpClient.getJson("$consumet/anime/zoro/episodes/$animeId")
                ?: return null
            val episodes   = JSONObject(epListJson).optJSONArray("episodes") ?: return null

            for (i in 0 until episodes.length()) {
                val ep = episodes.getJSONObject(i)
                if (ep.optInt("number") == req.episode) {
                    return ep.optString("id")
                }
            }
            // Fallback: first episode
            episodes.optJSONObject(0)?.optString("id")
        } catch (e: Exception) {
            Log.e(TAG, "findEpisodeId: ${e.message}")
            null
        }
    }

    private fun fetchServer(consumet: String, episodeId: String, server: String): List<StreamResult> {
        return try {
            val url  = "$consumet/anime/zoro/watch?episodeId=$episodeId&server=$server"
            Log.d(TAG, "HiAnime server $server: $url")
            val json = HttpClient.getJson(url) ?: return emptyList()
            val data = JSONObject(json)

            // Parse subtitles
            val subtitles = mutableListOf<SubtitleTrack>()
            data.optJSONArray("subtitles")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val sub  = arr.getJSONObject(i)
                    val lang = sub.optString("lang", "")
                    if (lang == "Thumbnails") continue
                    subtitles.add(SubtitleTrack(
                        url      = sub.optString("url"),
                        language = lang.take(2).ifEmpty { "und" },
                        title    = lang,
                        mimeType = if (sub.optString("url").endsWith(".vtt")) "text/vtt"
                                   else "application/x-subrip"
                    ))
                }
            }

            // Parse sources
            buildList {
                data.optJSONArray("sources")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val src   = arr.getJSONObject(i)
                        val link  = src.optString("url").takeIf { it.isNotEmpty() } ?: continue
                        val type  = if (src.optBoolean("isM3U8")) StreamType.HLS else StreamType.MP4
                        add(StreamResult(
                            url       = link,
                            quality   = "HD",
                            type      = type,
                            source    = "HiAnime ($server)",
                            language  = "Japanese",
                            label     = "HiAnime [$server]",
                            subtitles = subtitles,
                            headers   = mapOf(
                                "Referer" to "https://megacloud.club/",
                                "Origin"  to "https://megacloud.club"
                            )
                        ))
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "HiAnime server $server error: ${e.message}")
            emptyList()
        }
    }
}
