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
object CinevoodProvider {
    private const val TAG = "CinevoodProvider"
    private val HEADERS = mapOf(
        "Referer"    to "https://www.google.com",
        "User-Agent" to HttpClient.DESKTOP_UA,
        "Cookie"     to "xla=s4t"
    )

    suspend fun fetch(req: ProviderRequest): List<StreamResult> = withContext(Dispatchers.IO) {
        try {
            val base  = ModflixConfig.get("1cinevood")
            val query = req.title.replace(" ", "+") +
                        if (req.isSeries) "+Season+${req.season}" else ""
            val html  = HttpClient.getHtml("$base/?s=$query", HEADERS) ?: return@withContext emptyList()
            val doc   = Jsoup.parse(html, base)
            val titleL = req.title.lowercase()

            val postUrl = doc.select("article a[href]")
                .firstOrNull { (it.attr("title") + it.text()).lowercase().contains(titleL.take(5)) }
                ?.attr("href") ?: doc.selectFirst("article a[href]")?.attr("href")
                ?: return@withContext emptyList()

            val postHtml = HttpClient.getHtml(postUrl, HEADERS) ?: return@withContext emptyList()
            val postDoc  = Jsoup.parse(postHtml, postUrl)
            postDoc.select("a[href*=hubcloud], a[href*=hubdrive], a[href*=/drive/]")
                .map { it.attr("href") }.distinct().take(3)
                .flatMap { HubCloudExtractor.extract(it, "1Cinevood") }
        } catch (e: Exception) { Log.w(TAG, e.message ?: "error"); emptyList() }
    }
}
