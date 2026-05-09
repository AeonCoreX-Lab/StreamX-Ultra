package com.aeoncorex.streamx.streaming.providers

import android.util.Log
import com.aeoncorex.streamx.streaming.HttpClient
import com.aeoncorex.streamx.streaming.ModflixConfig
import com.aeoncorex.streamx.streaming.ProviderRequest
import com.aeoncorex.streamx.streaming.StreamResult
import com.aeoncorex.streamx.streaming.StreamType
import com.aeoncorex.streamx.streaming.extractors.HubCloudExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup

// ─────────────────────────────────────────────────────────────────────────────
object MoviesModProvider {
    private const val TAG = "MoviesModProvider"
    private val HEADERS = mapOf(
        "Cookie"     to "popads_user_id=6ba8fe60a481387a3249f05aa058822d",
        "User-Agent" to HttpClient.DESKTOP_UA
    )

    suspend fun fetch(req: ProviderRequest): List<StreamResult> = withContext(Dispatchers.IO) {
        try {
            val base      = ModflixConfig.get("Moviesmod")
            val query     = req.title.replace(" ", "+")
            val searchUrl = "$base/search/$query/page/1/"
            val html      = HttpClient.getHtml(searchUrl, HEADERS) ?: return@withContext emptyList()
            val doc       = Jsoup.parse(html, base)
            val titleL    = req.title.lowercase()

            val postUrl = doc.select(".post-cards article a[href]")
                .firstOrNull { it.text().lowercase().contains(titleL.take(5)) }
                ?.attr("href")
                ?: doc.selectFirst(".post-cards article a[href]")?.attr("href")
                ?: return@withContext emptyList()

            val postHtml = HttpClient.getHtml(postUrl, HEADERS) ?: return@withContext emptyList()
            val postDoc  = Jsoup.parse(postHtml, postUrl)

            // If series: follow maxbutton links for episode
            val targetUrl = if (req.isSeries) {
                val buttons  = postDoc.select("a.maxbutton")
                // Find season → episode button
                val seasonBtn = buttons.firstOrNull {
                    it.select("span").text().contains("Season ${req.season}", true)
                } ?: buttons.firstOrNull()
                val seasonUrl = seasonBtn?.attr("href")?.let {
                    // May be base64 encoded: url=BASE64
                    if (it.contains("url=")) {
                        val enc = it.split("url=").last()
                        android.util.Base64.decode(enc, android.util.Base64.DEFAULT)
                            .let { b -> String(b) }
                    } else it
                } ?: postUrl

                if (seasonUrl != postUrl) {
                    val sHtml = HttpClient.getHtml(seasonUrl, HEADERS) ?: return@withContext emptyList()
                    val sDoc  = Jsoup.parse(sHtml, seasonUrl)
                    // Find episode heading link
                    val epLink = sDoc.select("h3 a, h4 a, a.maxbutton")
                        .firstOrNull {
                            it.text().contains("Episode ${req.episode}", true) ||
                            it.select("span").text().contains("Episode ${req.episode}", true)
                        }?.attr("href") ?: seasonUrl
                    if (epLink.contains("url=")) {
                        val enc = epLink.split("url=").last()
                        String(android.util.Base64.decode(enc, android.util.Base64.DEFAULT))
                    } else epLink
                } else postUrl
            } else postUrl

            val finalHtml = HttpClient.getHtml(targetUrl, HEADERS) ?: return@withContext emptyList()
            val finalDoc  = Jsoup.parse(finalHtml, targetUrl)

            finalDoc.select("a[href*=hubcloud], a[href*=hubdrive], a[href*=/drive/]")
                .map { it.attr("href") }.distinct().take(3)
                .flatMap { HubCloudExtractor.extract(it, "MoviesMod") }
        } catch (e: Exception) { Log.w(TAG, e.message ?: "error"); emptyList() }
    }
}
