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
object OgoMoviesProvider {
    private const val TAG = "OgoMoviesProvider"
    private val HEADERS = mapOf(
        "Cookie"     to "xla=s4t; _ga=GA1.1.1081149560.1756378968",
        "User-Agent" to HttpClient.DESKTOP_UA,
    )

    suspend fun fetch(req: ProviderRequest): List<StreamResult> = withContext(Dispatchers.IO) {
        try {
            val base  = ModflixConfig.get("ogomovies")
            val query = req.title.replace(" ", "+")
            val html  = HttpClient.getHtml("$base/?s=$query", HEADERS) ?: return@withContext emptyList()
            val doc   = Jsoup.parse(html, base)

            val postUrl = doc.select("article a[href]")
                .firstOrNull { it.text().lowercase().contains(req.title.lowercase().take(5)) }
                ?.attr("href") ?: doc.selectFirst("article a[href]")?.attr("href")
                ?: return@withContext emptyList()

            val postHtml = HttpClient.getHtml(postUrl, HEADERS) ?: return@withContext emptyList()

            // Extract download_video('id','mode','hash') calls
            val dlRegex = Regex("""download_video\('([^']+)','([^']+)','([^']+)'\)""")
            val results = mutableListOf<StreamResult>()

            dlRegex.findAll(postHtml).forEach { m ->
                val (id, mode, hash) = m.destructured
                val dlUrl = "https://cdn.bewab.co/dl?op=download_orig&id=$id&mode=$mode&hash=$hash"
                val dlHtml = HttpClient.getHtml(dlUrl, HEADERS) ?: return@forEach
                val mkv    = Regex("""<a\s+href="([^"]+\.(?:mkv|mp4))"[^>]*>""")
                    .find(dlHtml)?.groupValues?.get(1) ?: return@forEach
                results.add(StreamResult(
                    url    = mkv,
                    type   = if (mkv.endsWith(".mp4")) StreamType.MP4 else StreamType.MKV,
                    source = "OgoMovies",
                    label  = "HD — OgoMovies"
                ))
            }
            results
        } catch (e: Exception) { Log.w(TAG, e.message ?: "error"); emptyList() }
    }
}
