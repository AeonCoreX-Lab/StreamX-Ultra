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
object MultiMoviesProvider {
    private const val TAG = "MultiMoviesProvider"
    private val HEADERS   = mapOf(
        "User-Agent" to HttpClient.DESKTOP_UA,
        "Referer"    to "https://multimovies.online/",
    )

    suspend fun fetch(req: ProviderRequest): List<StreamResult> = withContext(Dispatchers.IO) {
        try {
            val base      = ModflixConfig.get("multi")
            val query     = req.title.replace(" ", "+")
            val html      = HttpClient.getHtml("$base/?s=$query", HEADERS) ?: return@withContext emptyList()
            val doc       = Jsoup.parse(html, base)
            val titleL    = req.title.lowercase()

            val postUrl = doc.select(".items.full article a, .result-item a")
                .firstOrNull { (it.attr("alt") + it.text()).lowercase().contains(titleL.take(5)) }
                ?.attr("href")
                ?: doc.selectFirst(".items.full article a[href]")?.attr("href")
                ?: return@withContext emptyList()

            // For series, find episode
            val targetUrl = if (req.isSeries) {
                val postHtml = HttpClient.getHtml(postUrl, HEADERS) ?: return@withContext emptyList()
                val postDoc  = Jsoup.parse(postHtml, postUrl)
                // Episode pages are nested: Season → Episode
                val seasonLinks = postDoc.select("a[href*=season-${req.season}], a[href*=temporada-${req.season}]")
                val seasonUrl   = seasonLinks.firstOrNull()?.attr("href") ?: postUrl
                if (seasonUrl != postUrl) {
                    val sHtml  = HttpClient.getHtml(seasonUrl, HEADERS) ?: return@withContext emptyList()
                    val sDoc   = Jsoup.parse(sHtml, seasonUrl)
                    sDoc.select("a[href]").firstOrNull { it.text().contains("Episode ${req.episode}") || it.text().contains("Ep ${req.episode}") }
                        ?.attr("href") ?: seasonUrl
                } else postUrl
            } else postUrl

            val epHtml  = HttpClient.getHtml(targetUrl, HEADERS) ?: return@withContext emptyList()
            val epDoc   = Jsoup.parse(epHtml, targetUrl)
            val postId  = epDoc.selectFirst("#player-option-1")?.attr("data-post") ?: return@withContext emptyList()
            val nume    = epDoc.selectFirst("#player-option-1")?.attr("data-nume") ?: "1"
            val typeVal = epDoc.selectFirst("#player-option-1")?.attr("data-type") ?: "movie"
            val ajaxUrl = "${targetUrl.split("/").take(3).joinToString("/")}/wp-admin/admin-ajax.php"

            val formBody = "action=doo_player_ajax&post=$postId&nume=$nume&type=$typeVal"
            val ajaxResp = HttpClient.postJson(ajaxUrl, formBody, mapOf(
                "Content-Type" to "application/x-www-form-urlencoded",
                "Referer"      to targetUrl,
            ) + HEADERS) ?: return@withContext emptyList()

            val embedUrl = JSONObject(ajaxResp).optString("embed_url").takeIf { it.isNotEmpty() }
                ?: return@withContext emptyList()

            // embedUrl is an iframe src — try to extract HLS from it
            val embedHtml = HttpClient.getHtml(embedUrl, mapOf("Referer" to targetUrl)) ?: return@withContext emptyList()
            val hlsMatch  = Regex("""(?:file|source|src)\s*[:=]\s*["'`](https?://[^"'`\s]+\.m3u8[^"'`\s]*)""")
                .find(embedHtml)
            if (hlsMatch != null) {
                return@withContext listOf(StreamResult(
                    url    = hlsMatch.groupValues[1], type = StreamType.HLS,
                    source = "MultiMovies", label = "HD — MultiMovies [HLS]"
                ))
            }
            // Fallback: mp4
            val mp4Match = Regex("""(?:file|source|src)\s*[:=]\s*["'`](https?://[^"'`\s]+\.mp4[^"'`\s]*)""")
                .find(embedHtml)
            if (mp4Match != null) {
                return@withContext listOf(StreamResult(
                    url    = mp4Match.groupValues[1], type = StreamType.MP4,
                    source = "MultiMovies", label = "HD — MultiMovies [MP4]"
                ))
            }
            emptyList()
        } catch (e: Exception) { Log.w(TAG, e.message ?: "error"); emptyList() }
    }
}
