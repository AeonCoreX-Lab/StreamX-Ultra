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
object DooflixProvider {
    private const val TAG = "DooflixProvider"
    private val HEADERS = mapOf(
        "User-Agent" to HttpClient.DESKTOP_UA,
        "Referer"    to "https://molop.art/",
        "Cookie"     to "cf_clearance=M2_2Hy4lKRy_ruRX3dzOgm3iho1FHe2DUC1lq28BUtI"
    )

    suspend fun fetch(req: ProviderRequest): List<StreamResult> = withContext(Dispatchers.IO) {
        try {
            val base  = ModflixConfig.get("dooflix")
            val query = req.title.replace(" ", "+")
            val html  = HttpClient.getHtml("$base/?s=$query", HEADERS) ?: return@withContext emptyList()
            val doc   = Jsoup.parse(html, base)

            // Dooflix posts often have direct m3u8 or embed links
            val postUrl = doc.select("article a[href], .poster a[href]")
                .firstOrNull { it.attr("href").contains(req.title.lowercase().take(4), true) }
                ?.attr("href") ?: doc.selectFirst("article a[href]")?.attr("href")
                ?: return@withContext emptyList()

            // Follow redirects manually to get final URL
            try {
                val req2 = okhttp3.Request.Builder().url(postUrl)
                    .headers(okhttp3.Headers.Builder().apply {
                        HEADERS.forEach { (k, v) -> add(k, v) }
                    }.build())
                    .build()
                val resp = HttpClient.noRedirect.newCall(req2).execute()
                val location = resp.header("Location") ?: postUrl
                val finalUrl = if (location.startsWith("http")) location else "$base$location"

                listOf(StreamResult(
                    url     = finalUrl,
                    type    = if (finalUrl.contains(".m3u8")) StreamType.HLS else StreamType.MP4,
                    source  = "Dooflix",
                    label   = "HD — Dooflix",
                    headers = HEADERS
                ))
            } catch (_: Exception) { emptyList() }
        } catch (e: Exception) { Log.w(TAG, e.message ?: "error"); emptyList() }
    }
}
