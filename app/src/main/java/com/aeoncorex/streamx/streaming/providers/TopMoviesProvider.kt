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
object TopMoviesProvider {
    private const val TAG = "TopMoviesProvider"
    private val HEADERS = mapOf(
        "Cookie"     to "popads_user_id=6ba8fe60a481387a3249f05aa058822d",
        "User-Agent" to HttpClient.DESKTOP_UA,
    )

    suspend fun fetch(req: ProviderRequest): List<StreamResult> = withContext(Dispatchers.IO) {
        try {
            val base      = ModflixConfig.get("Topmovies")
            val query     = "${req.title} ${if (req.isSeries) "Season ${req.season}" else ""}".trim()
            val searchUrl = "$base/search/${query.replace(" ", "+")}/page/1/"
            val html      = HttpClient.getHtml(searchUrl, HEADERS) ?: return@withContext emptyList()
            val doc       = Jsoup.parse(html, base)
            val postUrl   = doc.selectFirst(".items.full article a[href], .result-item a[href]")
                ?.attr("href") ?: return@withContext emptyList()

            val postHtml = HttpClient.getHtml(postUrl, HEADERS) ?: return@withContext emptyList()
            val postDoc  = Jsoup.parse(postHtml, postUrl)

            // If series, look for episode link
            val targetUrl = if (req.isSeries) {
                val epLinks = postDoc.select("h3 a, h4 a, a.maxbutton")
                epLinks.firstOrNull { it.text().contains("Episode ${req.episode}", true) }
                    ?.attr("href") ?: epLinks.getOrNull(req.episode - 1)?.attr("href") ?: postUrl
            } else postUrl

            // Follow meta-refresh if needed
            val finalHtml = if (targetUrl != postUrl) {
                val h = HttpClient.getHtml(targetUrl, HEADERS) ?: return@withContext emptyList()
                val metaUrl = Regex("""content="0;url=(.*?)"""", RegexOption.IGNORE_CASE)
                    .find(h)?.groupValues?.get(1)
                if (metaUrl != null) HttpClient.getHtml(metaUrl, HEADERS) ?: h else h
            } else postHtml

            val finalDoc = Jsoup.parse(finalHtml, targetUrl)
            finalDoc.select("a[href*=hubcloud], a[href*=hubdrive], a[href*=/drive/]")
                .map { it.attr("href") }.distinct().take(3)
                .flatMap { HubCloudExtractor.extract(it, "TopMovies") }
        } catch (e: Exception) { Log.w(TAG, e.message ?: "error"); emptyList() }
    }
}
