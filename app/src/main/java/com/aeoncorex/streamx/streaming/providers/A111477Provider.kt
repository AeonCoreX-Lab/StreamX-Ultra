package com.aeoncorex.streamx.streaming.providers

import android.util.Log
import com.aeoncorex.streamx.streaming.HttpClient
import com.aeoncorex.streamx.streaming.ProviderRequest
import com.aeoncorex.streamx.streaming.StreamResult
import com.aeoncorex.streamx.streaming.StreamType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup

// ─────────────────────────────────────────────────────────────────────────────
object A111477Provider {
    private const val TAG      = "A111477Provider"
    private const val BASE_URL = "https://a.111477.xyz"
    private val HEADERS = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36",
        "Referer"    to "$BASE_URL/"
    )

    suspend fun fetch(req: ProviderRequest): List<StreamResult> = withContext(Dispatchers.IO) {
        try {
            val titleL = req.title.lowercase()
            val query  = req.title.lowercase()

            // Search both /movies/ and /tvs/ in parallel
            val (movies, shows) = coroutineScope {
                val m = async { searchPage("$BASE_URL/movies/", titleL) }
                val s = async { searchPage("$BASE_URL/tvs/",    titleL) }
                Pair(m.await(), s.await())
            }

            val matches = (if (req.isSeries) shows + movies else movies + shows)
                .filter { (title, _) -> title.lowercase().contains(query.take(5)) }
                .take(3)

            if (matches.isEmpty()) {
                Log.d(TAG, "a111477: no match for '${req.title}'")
                return@withContext emptyList()
            }

            matches.mapNotNull { (title, url) ->
                val ext  = url.substringAfterLast(".").lowercase().substringBefore("?")
                val type = when (ext) {
                    "mkv", "avi" -> StreamType.MKV
                    "m3u8"       -> StreamType.HLS
                    else         -> StreamType.MP4
                }
                StreamResult(
                    url     = url,
                    type    = type,
                    source  = "111477.xyz",
                    label   = "$title — 111477.xyz",
                    headers = HEADERS
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, e.message ?: "error")
            emptyList()
        }
    }

    // Returns list of (title, directUrl) pairs from a catalog page
    private fun searchPage(pageUrl: String, query: String): List<Pair<String, String>> {
        return try {
            val html = HttpClient.getHtml(pageUrl, HEADERS) ?: return emptyList()
            val doc  = Jsoup.parse(html, BASE_URL)
            doc.select("a[href]").mapNotNull { el ->
                val href  = el.attr("href")
                val title = (el.attr("title").ifEmpty { el.text() }).trim()
                val ext   = href.substringAfterLast(".").lowercase().substringBefore("?")
                if (ext in listOf("mkv", "mp4", "avi", "m3u8") &&
                    title.lowercase().contains(query.take(4))) {
                    val url = if (href.startsWith("http")) href else "$BASE_URL$href"
                    Pair(title, url)
                } else null
            }
        } catch (e: Exception) { emptyList() }
    }
}
