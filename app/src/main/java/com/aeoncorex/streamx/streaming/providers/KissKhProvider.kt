package com.aeoncorex.streamx.streaming.providers

import android.util.Log
import com.aeoncorex.streamx.streaming.HttpClient
import com.aeoncorex.streamx.streaming.ModflixConfig
import com.aeoncorex.streamx.streaming.ProviderRequest
import com.aeoncorex.streamx.streaming.StreamResult
import com.aeoncorex.streamx.streaming.StreamType
import com.aeoncorex.streamx.streaming.SubtitleTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

// ─────────────────────────────────────────────────────────────────────────────
//  KissKhProvider.kt
//  K-Drama and C-Drama HLS streams with subtitles via kisskh.do API.
//  Uses netlify proxy for actual stream extraction.
//
//  Ported from: vega-providers/dist/kissKh/stream.js + posts.js
// ─────────────────────────────────────────────────────────────────────────────
object KissKhProvider {

    private const val TAG       = "KissKhProvider"
    private const val PROXY_URL = "https://adorable-salamander-ecbb21.netlify.app/api/kisskh/video?id="
    private const val SEARCH_ENDPOINT = "https://kisskh.do/api/DramaList/Search"

    suspend fun fetch(req: ProviderRequest): List<StreamResult> = withContext(Dispatchers.IO) {
        try {
            val base = ModflixConfig.get("kissKh")

            // Step 1: Search for drama
            val query     = req.title.replace(" ", "+")
            val searchUrl = "$SEARCH_ENDPOINT?q=$query&type=0&sub=0&page=1&pageSize=10"
            Log.d(TAG, "KissKh search: $searchUrl")

            val searchJson = HttpClient.getJson(searchUrl,
                mapOf("Referer" to base)) ?: return@withContext emptyList()

            val searchData = JSONObject(searchJson)
            val items      = searchData.optJSONArray("data") ?: return@withContext emptyList()
            val titleL     = req.title.lowercase()

            // Find best match
            var dramaId: Int? = null
            for (i in 0 until items.length()) {
                val item  = items.getJSONObject(i)
                val title = item.optString("title", "").lowercase()
                if (title.contains(titleL.take(5))) {
                    dramaId = item.optInt("id")
                    break
                }
            }
            if (dramaId == null) dramaId = items.optJSONObject(0)?.optInt("id")
            if (dramaId == null) {
                Log.d(TAG, "KissKh: no match for '${req.title}'")
                return@withContext emptyList()
            }

            // Step 2: Get episode list
            val episodeListUrl = "$base/api/DramaList/Drama/$dramaId?type=Drama"
            val epListJson     = HttpClient.getJson(episodeListUrl,
                mapOf("Referer" to base)) ?: return@withContext emptyList()
            val epData         = JSONObject(epListJson)
            val episodes       = epData.optJSONArray("episodes") ?: return@withContext emptyList()

            val episodeId: Int? = if (req.isSeries) {
                // Find specific episode
                var eid: Int? = null
                for (i in 0 until episodes.length()) {
                    val ep = episodes.getJSONObject(i)
                    if (ep.optInt("number") == req.episode) {
                        eid = ep.optInt("id"); break
                    }
                }
                eid ?: episodes.optJSONObject(0)?.optInt("id")
            } else {
                // Movie: use first episode
                episodes.optJSONObject(0)?.optInt("id")
            }

            if (episodeId == null) return@withContext emptyList()
            Log.d(TAG, "KissKh episode ID: $episodeId")

            // Step 3: Fetch stream via proxy
            val streamUrl  = "$PROXY_URL$episodeId"
            Log.d(TAG, "KissKh stream: $streamUrl")
            val streamJson = HttpClient.getJson(streamUrl,
                mapOf("Referer" to base)) ?: return@withContext emptyList()
            val streamData = JSONObject(streamJson)

            val videoUrl  = streamData.optJSONObject("source")?.optString("Video")
                ?: streamData.optString("Video").takeIf { it.isNotEmpty() }
                ?: return@withContext emptyList()

            // Parse subtitles
            val subtitles = mutableListOf<SubtitleTrack>()
            streamData.optJSONArray("subtitles")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val sub = arr.getJSONObject(i)
                    subtitles.add(SubtitleTrack(
                        url      = sub.optString("src"),
                        language = sub.optString("land", "en"),
                        title    = sub.optString("label", "Unknown"),
                        mimeType = if (sub.optString("src").contains(".vtt")) "text/vtt"
                                   else "application/x-subrip"
                    ))
                }
            }

            val type = if (videoUrl.contains(".mp4")) StreamType.MP4 else StreamType.HLS
            listOf(StreamResult(
                url       = videoUrl,
                quality   = "HD",
                type      = type,
                source    = "KissKh",
                language  = "Korean",
                label     = "KissKh [K-Drama]",
                subtitles = subtitles,
                headers   = mapOf("Referer" to base)
            ))
        } catch (e: Exception) {
            Log.w(TAG, "KissKh error: ${e.message}")
            emptyList()
        }
    }
}
