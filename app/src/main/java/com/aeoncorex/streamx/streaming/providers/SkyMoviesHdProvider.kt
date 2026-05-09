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
object SkyMoviesHdProvider {
    private const val TAG = "SkyMoviesHdProvider"
    private val HEADERS   = mapOf("Cookie" to "xla=s4t", "User-Agent" to HttpClient.DESKTOP_UA)

    suspend fun fetch(req: ProviderRequest): List<StreamResult> = withContext(Dispatchers.IO) {
        try {
            val base  = ModflixConfig.get("skymovieshd")
            val query = "${req.title} ${if (req.isSeries) "Season ${req.season}" else ""}".trim()
            val html  = HttpClient.getHtml("$base/?s=${query.replace(" ", "+")}", HEADERS) ?: return@withContext emptyList()
            val doc   = Jsoup.parse(html, base)
            val titleL = req.title.lowercase()

            val postUrl = doc.select("article a[href], .recent-movies a")
                .firstOrNull { it.text().lowercase().contains(titleL.take(5)) }
                ?.attr("href")
                ?: doc.selectFirst("article a[href]")?.attr("href")
                ?: return@withContext emptyList()

            val postHtml = HttpClient.getHtml(postUrl, HEADERS) ?: return@withContext emptyList()
            val postDoc  = Jsoup.parse(postHtml, postUrl)
            postDoc.select("a[href*=hubcloud], a[href*=hubdrive], a[href*=/drive/]")
                .map { it.attr("href") }.distinct().take(3)
                .flatMap { HubCloudExtractor.extract(it, "SkyMoviesHD") }
        } catch (e: Exception) { Log.w(TAG, e.message ?: "error"); emptyList() }
    }
}
