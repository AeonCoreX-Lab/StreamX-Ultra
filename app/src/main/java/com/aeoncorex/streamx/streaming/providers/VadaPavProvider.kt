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
object VadaPavProvider {
    private const val TAG = "VadaPavProvider"

    suspend fun fetch(req: ProviderRequest): List<StreamResult> = withContext(Dispatchers.IO) {
        try {
            val base  = ModflixConfig.get("vadapav")
            val query = req.title.replace(" ", "+")
            val html  = HttpClient.getHtml("$base/s/$query") ?: return@withContext emptyList()
            val doc   = Jsoup.parse(html, base)
            val titleL = req.title.lowercase()

            // Find matching directory entry
            val entries = doc.select(".directory-entry:not(:contains(Parent Directory))")
            val match   = entries.firstOrNull { it.text().lowercase().contains(titleL.take(5)) }
                ?: entries.firstOrNull()
            val dirUrl  = match?.attr("href")?.let {
                if (it.startsWith("http")) it else "$base$it"
            } ?: return@withContext emptyList()

            // Browse directory for video files
            val dirHtml = HttpClient.getHtml(dirUrl) ?: return@withContext emptyList()
            val dirDoc  = Jsoup.parse(dirHtml, dirUrl)

            buildList {
                dirDoc.select("a[href]").forEach { el ->
                    val href = el.attr("href")
                    val url  = if (href.startsWith("http")) href else "$base$href"
                    val ext  = url.substringAfterLast(".").lowercase().substringBefore("?")
                    if (ext in listOf("mkv", "mp4", "avi", "m3u8")) {
                        val type = if (ext == "m3u8") StreamType.HLS else StreamType.MKV
                        val qual = detectQuality(url)
                        add(StreamResult(
                            url    = url, quality = qual, type = type,
                            source = "VadaPav", label = "$qual — VadaPav"
                        ))
                    }
                }
            }.take(4)
        } catch (e: Exception) { Log.w(TAG, e.message ?: "error"); emptyList() }
    }
    private fun detectQuality(url: String) = when {
        url.contains("2160") || url.contains("4k", true) -> "4K"
        url.contains("1080")                              -> "1080p"
        url.contains("720")                               -> "720p"
        url.contains("480")                               -> "480p"
        else                                              -> "HD"
    }
}
