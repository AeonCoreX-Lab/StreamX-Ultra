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
object KatMoviesProvider {
    private const val TAG = "KatMoviesProvider"
    private val HEADERS = mapOf(
        "Cookie"     to "xla=s4t",
        "User-Agent" to HttpClient.DESKTOP_UA,
        "Referer"    to "https://google.com",
    )

    suspend fun fetch(req: ProviderRequest): List<StreamResult> = withContext(Dispatchers.IO) {
        try {
            val base  = ModflixConfig.get("kat")
            val query = cleanTitle(req.title) + if (req.isSeries) " Season ${req.season}" else ""
            val searchUrl = "$base/page/1/?s=${query.replace(" ", "+")}"
            val html      = HttpClient.getHtml(searchUrl, HEADERS) ?: return@withContext emptyList()
            val doc       = Jsoup.parse(html, base)

            val titleL  = req.title.lowercase()
            val postUrl = doc.select("article a[href], .recent-movies a")
                .firstOrNull { el ->
                    val t = (el.attr("title") + el.text()).lowercase()
                    t.contains(titleL.take(6))
                }?.attr("href")
                ?: doc.selectFirst("article a[href]")?.attr("href")
                ?: return@withContext emptyList()

            val postHtml = HttpClient.getHtml(postUrl, HEADERS) ?: return@withContext emptyList()
            val postDoc  = Jsoup.parse(postHtml, postUrl)
            val links    = postDoc.select("a[href*=hubcloud], a[href*=hubdrive], a[href*=/drive/]")
                .map { it.attr("href") }.distinct().take(3)

            links.flatMap { HubCloudExtractor.extract(it, "KatMovies") }
        } catch (e: Exception) { Log.w(TAG, e.message ?: "error"); emptyList() }
    }
    private fun cleanTitle(t: String) = t.replace(Regex("""[:"'!?.,]"""), " ").replace(Regex("""\s+"""), " ").trim()
}
