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
object MoviezwapProvider {
    private const val TAG = "MoviezwapProvider"

    suspend fun fetch(req: ProviderRequest): List<StreamResult> = withContext(Dispatchers.IO) {
        try {
            val base  = ModflixConfig.get("moviezwap")
            val query = req.title.replace(" ", "+")
            val html  = HttpClient.getHtml("$base/?s=$query") ?: return@withContext emptyList()
            val doc   = Jsoup.parse(html, base)
            val postUrl = doc.selectFirst("article a[href]")?.attr("href") ?: return@withContext emptyList()
            val postHtml = HttpClient.getHtml(postUrl) ?: return@withContext emptyList()
            val postDoc  = Jsoup.parse(postHtml, postUrl)

            buildList {
                postDoc.select("a").forEach { el ->
                    if (el.text().contains("Fast Download Server", true)) {
                        val href = el.attr("href").lowercase().trim()
                        if (href.endsWith(".mkv") || href.endsWith(".mp4")) {
                            add(StreamResult(
                                url    = href,
                                type   = if (href.endsWith(".mp4")) StreamType.MP4 else StreamType.MKV,
                                source = "Moviezwap",
                                label  = "HD — Moviezwap"
                            ))
                        }
                    }
                }
            }
        } catch (e: Exception) { Log.w(TAG, e.message ?: "error"); emptyList() }
    }
}
