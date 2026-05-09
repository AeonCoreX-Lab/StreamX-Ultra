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
object Movies4uProvider {
    private const val TAG = "Movies4uProvider"
    private val HEADERS   = mapOf("Cookie" to "xla=s4t", "User-Agent" to HttpClient.DESKTOP_UA)

    suspend fun fetch(req: ProviderRequest): List<StreamResult> = withContext(Dispatchers.IO) {
        try {
            val base      = ModflixConfig.get("movies4u")
            val query     = req.title.replace(" ", "+")
            val html      = HttpClient.getHtml("$base/?s=$query", HEADERS) ?: return@withContext emptyList()
            val doc       = Jsoup.parse(html, base)
            val titleL    = req.title.lowercase()

            val postUrl = doc.select("article a[href]")
                .firstOrNull { it.text().lowercase().contains(titleL.take(5)) }
                ?.attr("href")
                ?: doc.selectFirst("article a[href]")?.attr("href")
                ?: return@withContext emptyList()

            val postHtml = HttpClient.getHtml(postUrl, HEADERS) ?: return@withContext emptyList()
            val postDoc  = Jsoup.parse(postHtml, postUrl)
            postDoc.select("a[href*=hubcloud], a[href*=fastdl], a[href*=filepress]")
                .map { it.attr("href") }.distinct().take(3)
                .flatMap { link ->
                    when {
                        link.contains("filepress") -> {
                            val id   = link.split("/").last()
                            val base2= link.split("/").dropLast(2).joinToString("/")
                            val b1   = """{"id":"$id","method":"indexDownlaod","captchaValue":null}"""
                            val r1   = HttpClient.postJson("$base2/api/file/downlaod/", b1, mapOf("Referer" to base2))
                            val j1   = r1?.let { runCatching { JSONObject(it) }.getOrNull() }
                            if (j1?.optBoolean("status") == true) {
                                val tok = j1.optString("data")
                                val b2  = """{"id":"$tok","method":"indexDownlaod","captchaValue":null}"""
                                val r2  = HttpClient.postJson("$base2/api/file/downlaod2/", b2, mapOf("Referer" to base2))
                                val url = r2?.let { JSONObject(it).optJSONArray("data")?.optString(0) }
                                if (!url.isNullOrEmpty()) listOf(StreamResult(url=url, type=StreamType.MKV, source="Movies4u (filepress)", label="HD — Movies4u [filepress]"))
                                else emptyList()
                            } else emptyList()
                        }
                        else -> HubCloudExtractor.extract(link, "Movies4u")
                    }
                }
        } catch (e: Exception) { Log.w(TAG, e.message ?: "error"); emptyList() }
    }
}
