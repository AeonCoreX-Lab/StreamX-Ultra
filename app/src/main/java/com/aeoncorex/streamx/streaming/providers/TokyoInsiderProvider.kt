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
object TokyoInsiderProvider {
    private const val TAG      = "TokyoInsiderProvider"
    private const val BASE_URL = "https://www.tokyoinsider.com"

    suspend fun fetch(req: ProviderRequest): List<StreamResult> = withContext(Dispatchers.IO) {
        try {
            val query = req.title.replace(" ", "+")
            val html  = HttpClient.getHtml("$BASE_URL/anime/search/?k=$query") ?: return@withContext emptyList()
            val doc   = Jsoup.parse(html, BASE_URL)
            val titleL = req.title.lowercase()

            val animeUrl = doc.select(".c_h1 a, .c_h2 a")
                .firstOrNull { it.text().lowercase().contains(titleL.take(5)) }
                ?.attr("href")?.let { if (it.startsWith("http")) it else "$BASE_URL$it" }
                ?: return@withContext emptyList()

            // For series: navigate to episode
            val streamUrl = if (req.isSeries) {
                val epHtml = HttpClient.getHtml(animeUrl) ?: return@withContext emptyList()
                val epDoc  = Jsoup.parse(epHtml, BASE_URL)
                val epLink = epDoc.select(".c_h2 a").getOrNull(req.episode - 1)
                    ?.attr("href")?.let { if (it.startsWith("http")) it else "$BASE_URL$it" }
                    ?: animeUrl
                epLink
            } else animeUrl

            val pageHtml = HttpClient.getHtml(streamUrl) ?: return@withContext emptyList()
            val pageDoc  = Jsoup.parse(pageHtml, BASE_URL)

            buildList {
                pageDoc.select(".c_h1, .c_h2").forEach { el ->
                    el.select("span").remove()
                    val title2 = el.selectFirst("a")?.text() ?: return@forEach
                    val link2  = el.selectFirst("a")?.attr("href") ?: return@forEach
                    if (!link2.contains("media")) return@forEach
                    val ext  = link2.substringAfterLast(".").lowercase()
                    val type = when (ext) {
                        "m3u8"       -> StreamType.HLS
                        "mp4"        -> StreamType.MP4
                        else         -> StreamType.MKV
                    }
                    add(StreamResult(
                        url    = if (link2.startsWith("http")) link2 else "$BASE_URL$link2",
                        type   = type,
                        source = "TokyoInsider",
                        label  = "$title2 — TokyoInsider"
                    ))
                }
            }
        } catch (e: Exception) { Log.w(TAG, e.message ?: "error"); emptyList() }
    }
}
