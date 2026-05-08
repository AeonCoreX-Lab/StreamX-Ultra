package com.aeoncorex.streamx.streaming.providers

import android.util.Log
import com.aeoncorex.streamx.streaming.HttpClient
import com.aeoncorex.streamx.streaming.ModflixConfig
import com.aeoncorex.streamx.streaming.ProviderRequest
import com.aeoncorex.streamx.streaming.StreamResult
import com.aeoncorex.streamx.streaming.StreamType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

// ─────────────────────────────────────────────────────────────────────────────
//  MovieBoxProvider.kt — api6.aoneroom.com via dob-worker.8man.workers.dev
//  Ported from: vega-providers/dist/movieBox/posts.js + stream.js
//  Pure JSON API — no HTML scraping.
// ─────────────────────────────────────────────────────────────────────────────
object MovieBoxProvider {
    private const val TAG    = "MovieBoxProvider"
    private const val PROXY  = "https://dob-worker.8man.workers.dev"

    suspend fun fetch(req: ProviderRequest): List<StreamResult> = withContext(Dispatchers.IO) {
        try {
            val base = ModflixConfig.get("movieBox")

            // Search via proxy POST
            val searchBody = JSONObject().apply {
                put("url", "$base/wefeed-mobile-bff/subject-api/search/v2")
                put("method", "POST")
                put("body", JSONObject().apply {
                    put("page", 1)
                    put("perPage", 10)
                    put("keyword", req.title)
                    put("tabId", if (req.isSeries) "TvShow" else "Movie")
                })
            }.toString()

            val searchResp = HttpClient.postJson(PROXY, searchBody) ?: return@withContext emptyList()
            val results = JSONObject(searchResp)
                .optJSONObject("data")?.optJSONArray("results")
                ?: return@withContext emptyList()

            var subjectId: String? = null
            val titleL = req.title.lowercase()
            for (i in 0 until results.length()) {
                val item   = results.getJSONObject(i)
                val items2 = item.optJSONArray("items") ?: continue
                for (j in 0 until items2.length()) {
                    val s = items2.getJSONObject(j)
                    val t = s.optString("title", "").lowercase()
                    if (t.contains(titleL.take(6))) {
                        subjectId = s.optString("subjectId")
                        break
                    }
                }
                if (subjectId != null) break
            }
            if (subjectId == null) return@withContext emptyList()

            // Get subject detail
            val detailBody = JSONObject().apply {
                put("url", "$base/wefeed-mobile-bff/subject-api/get?subjectId=$subjectId")
                put("method", "GET")
            }.toString()
            val detailResp = HttpClient.postJson(PROXY, detailBody) ?: return@withContext emptyList()
            val detail     = JSONObject(detailResp).optJSONObject("data") ?: return@withContext emptyList()

            // Find episode fid
            val fid: String? = if (!req.isSeries) {
                detail.optString("fid").takeIf { it.isNotEmpty() }
            } else {
                val seasons = detail.optJSONArray("seasons")
                var epFid: String? = null
                seasons?.let { sl ->
                    for (i in 0 until sl.length()) {
                        val season = sl.getJSONObject(i)
                        if (season.optInt("seasonNumber") == req.season) {
                            val eps = season.optJSONArray("episodes") ?: continue
                            for (j in 0 until eps.length()) {
                                val ep = eps.getJSONObject(j)
                                if (ep.optInt("episodeNumber") == req.episode) {
                                    epFid = ep.optString("fid")
                                    break
                                }
                            }
                            break
                        }
                    }
                }
                epFid
            }
            if (fid.isNullOrEmpty()) return@withContext emptyList()

            // Get stream URL
            val streamBody = JSONObject().apply {
                put("url", "$base/wefeed-mobile-bff/subject-api/source/v2?fid=$fid")
                put("method", "GET")
            }.toString()
            val streamResp = HttpClient.postJson(PROXY, streamBody) ?: return@withContext emptyList()
            val sources    = JSONObject(streamResp).optJSONObject("data")?.optJSONArray("sources")
                ?: return@withContext emptyList()

            buildList {
                for (i in 0 until sources.length()) {
                    val s    = sources.getJSONObject(i)
                    val url  = s.optString("url").takeIf { it.isNotEmpty() } ?: continue
                    val qual = s.optString("quality", "HD")
                    val type = if (url.contains(".m3u8")) StreamType.HLS else StreamType.MP4
                    add(StreamResult(
                        url     = url, quality = qual, type = type,
                        source  = "MovieBox",
                        label   = "$qual — MovieBox"
                    ))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "MovieBox error: ${e.message}")
            emptyList()
        }
    }
}
