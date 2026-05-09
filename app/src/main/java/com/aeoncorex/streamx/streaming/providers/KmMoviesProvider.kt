package com.aeoncorex.streamx.streaming.providers

import android.util.Base64
import android.util.Log
import com.aeoncorex.streamx.streaming.HttpClient
import com.aeoncorex.streamx.streaming.ModflixConfig
import com.aeoncorex.streamx.streaming.ProviderRequest
import com.aeoncorex.streamx.streaming.StreamResult
import com.aeoncorex.streamx.streaming.StreamType
import com.aeoncorex.streamx.streaming.SubtitleTrack
import com.aeoncorex.streamx.streaming.extractors.HubCloudExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup

// ─────────────────────────────────────────────────────────────────────────────
object KmMoviesProvider {
    private const val TAG = "KmMoviesProvider"
    private val HEADERS   = mapOf(
        "User-Agent" to HttpClient.DESKTOP_UA,
        "Cookie"     to "xla=s4t"
    )
    private val ALLOWED_SERVERS = listOf("ONE CLICK", "ZIP-ZAP", "ULTRA FAST", "SKYDROP")

    suspend fun fetch(req: ProviderRequest): List<StreamResult> = withContext(Dispatchers.IO) {
        try {
            val base  = ModflixConfig.get("kmMovies")
            val query = req.title.replace(" ", "+") +
                        if (req.isSeries) "+Season+${req.season}" else ""
            val html  = HttpClient.getHtml("$base/?s=$query", HEADERS) ?: return@withContext emptyList()
            val doc   = Jsoup.parse(html, base)

            val postUrl = doc.select("article a[href]")
                .firstOrNull { it.text().lowercase().contains(req.title.lowercase().take(5)) }
                ?.attr("href") ?: doc.selectFirst("article a[href]")?.attr("href")
                ?: return@withContext emptyList()

            val postHtml = HttpClient.getHtml(postUrl, HEADERS) ?: return@withContext emptyList()
            val postDoc  = Jsoup.parse(postHtml, postUrl)

            buildList {
                postDoc.select("a.download-button").forEach { el ->
                    val href   = el.attr("href").trim().takeIf { it.isNotEmpty() } ?: return@forEach
                    val server = el.text().trim()
                    val allowed = ALLOWED_SERVERS.any { server.uppercase().contains(it) }
                    if (allowed) {
                        add(StreamResult(
                            url    = href,
                            type   = StreamType.MKV,
                            source = "KmMovies ($server)",
                            label  = "HD — KmMovies [$server]"
                        ))
                    }
                }
            }
        } catch (e: Exception) { Log.w(TAG, e.message ?: "error"); emptyList() }
    }
}
