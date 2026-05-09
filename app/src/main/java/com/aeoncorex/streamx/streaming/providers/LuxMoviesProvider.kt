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
object LuxMoviesProvider {
    private const val TAG = "LuxMoviesProvider"
    private val HEADERS   = mapOf("Cookie" to "xla=s4t", "User-Agent" to HttpClient.DESKTOP_UA)

    suspend fun fetch(req: ProviderRequest): List<StreamResult> = withContext(Dispatchers.IO) {
        try {
            val base      = ModflixConfig.get("lux")
            val query     = req.title.replace(" ", "+")
            val searchUrl = "$base/search.php?q=$query&page=1"
            val json      = HttpClient.getJson(searchUrl, HEADERS)
            val postUrl   = if (json != null) {
                val hits   = JSONObject(json).optJSONArray("hits") ?: return@withContext emptyList()
                val titleL = req.title.lowercase()
                var url: String? = null
                for (i in 0 until hits.length()) {
                    val doc = hits.getJSONObject(i).optJSONObject("document") ?: continue
                    if (doc.optString("post_title", "").lowercase().contains(titleL.take(5))) {
                        url = doc.optString("permalink")
                        break
                    }
                }
                url ?: hits.optJSONObject(0)?.optJSONObject("document")?.optString("permalink")
            } else {
                val html = HttpClient.getHtml("$base/?s=$query", HEADERS) ?: return@withContext emptyList()
                Jsoup.parse(html, base).selectFirst("article a[href]")?.attr("href")
            } ?: return@withContext emptyList()

            val postHtml = HttpClient.getHtml(postUrl, HEADERS) ?: return@withContext emptyList()
            val postDoc  = Jsoup.parse(postHtml, postUrl)
            postDoc.select("a[href*=hubcloud], a[href*=hubdrive], a[href*=/drive/]")
                .map { it.attr("href") }.distinct().take(3)
                .flatMap { HubCloudExtractor.extract(it, "LuxMovies") }
        } catch (e: Exception) { Log.w(TAG, e.message ?: "error"); emptyList() }
    }
}
