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
object UhdMoviesProvider {
    private const val TAG = "UhdMoviesProvider"
    private val HEADERS   = mapOf("User-Agent" to HttpClient.DESKTOP_UA, "Cookie" to "xla=s4t")

    suspend fun fetch(req: ProviderRequest): List<StreamResult> = withContext(Dispatchers.IO) {
        try {
            val base      = ModflixConfig.get("UhdMovies")
            val query     = req.title.replace(" ", "+")
            val searchUrl = "$base/?s=$query"
            val html      = HttpClient.getHtml(searchUrl, HEADERS) ?: return@withContext emptyList()
            val doc       = Jsoup.parse(html, base)
            val titleL    = req.title.lowercase()

            val postUrl = doc.select("article a[href]")
                .firstOrNull { it.text().lowercase().contains(titleL.take(5)) }
                ?.attr("href")
                ?: doc.selectFirst("article a[href]")?.attr("href")
                ?: return@withContext emptyList()

            val postHtml = HttpClient.getHtml(postUrl, HEADERS) ?: return@withContext emptyList()
            val postDoc  = Jsoup.parse(postHtml, postUrl)

            // UHD uses GDFLIX links
            val gdLinks = postDoc.select("a[href*=gdflix], a[href*=gd-]")
                .map { it.attr("href") }.filter { it.startsWith("http") }.take(2)

            if (gdLinks.isNotEmpty()) {
                return@withContext gdLinks.flatMap {
                    com.aeoncorex.streamx.streaming.extractors.GdflixExtractor.extract(it, "UhdMovies 4K")
                }
            }

            // Fallback HubCloud
            postDoc.select("a[href*=hubcloud], a[href*=hubdrive]")
                .map { it.attr("href") }.distinct().take(2)
                .flatMap { HubCloudExtractor.extract(it, "UhdMovies") }
        } catch (e: Exception) { Log.w(TAG, e.message ?: "error"); emptyList() }
    }
}
