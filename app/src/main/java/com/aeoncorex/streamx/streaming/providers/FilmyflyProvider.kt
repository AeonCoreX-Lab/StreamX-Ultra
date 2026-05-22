package com.aeoncorex.streamx.streaming.providers

import android.util.Log
import com.aeoncorex.streamx.streaming.HttpClient
import com.aeoncorex.streamx.streaming.ModflixConfig
import com.aeoncorex.streamx.streaming.ProviderRequest
import com.aeoncorex.streamx.streaming.StreamResult
import com.aeoncorex.streamx.streaming.StreamType
import com.aeoncorex.streamx.streaming.extractors.GdflixExtractor
import com.aeoncorex.streamx.streaming.extractors.HubCloudExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

// ─────────────────────────────────────────────────────────────────────────────
//  FilmyflyProvider.kt
//  Best for: South Indian dubbed (Tamil/Telugu/Malayalam), Hindi Bollywood.
//  Search filmyfiy.org → GDFLIX extractor → G-Drive/ResumeBot links.
//
//  Ported from: vega-providers/dist/filmyfly/posts.js + stream.js
// ─────────────────────────────────────────────────────────────────────────────
object FilmyflyProvider {

    private const val TAG = "FilmyflyProvider"

    suspend fun fetch(req: ProviderRequest): List<StreamResult> = withContext(Dispatchers.IO) {
        try {
            val base  = ModflixConfig.get("filmyfly")
            val query = cleanTitle(req.title).replace(" ", "+")

            // Search
            val searchUrl = "$base/site-1.html?to-search=$query"
            Log.d(TAG, "Filmyfly search: $searchUrl")
            val searchHtml = HttpClient.getHtml(searchUrl, HttpClient.BASE_HEADERS)
                ?: return@withContext emptyList()
            val searchDoc  = Jsoup.parse(searchHtml, base)

            // Find best match (.A2, .A10, .fl selectors from posts.js)
            val titleL  = req.title.lowercase()
            var postUrl: String? = null

            for (el in searchDoc.select(".A2, .A10, .fl")) {
                val title = (el.selectFirst("a")?.attr("title")
                    ?: el.selectFirst("b")?.text() ?: "").lowercase()
                val link  = el.selectFirst("a")?.attr("href") ?: continue
                if (title.contains(titleL.take(6))) {
                    postUrl = if (link.startsWith("http")) link else "$base$link"
                    break
                }
            }

            if (postUrl == null) {
                // Fallback: first result
                val first = searchDoc.selectFirst(".A2 a, .A10 a, .fl a")
                postUrl   = first?.attr("href")?.let {
                    if (it.startsWith("http")) it else "$base$it"
                }
            }

            if (postUrl == null) {
                Log.d(TAG, "Filmyfly: no match for '${req.title}'")
                return@withContext emptyList()
            }
            Log.d(TAG, "Filmyfly post: $postUrl")

            val postHtml = HttpClient.getHtml(postUrl, HttpClient.BASE_HEADERS)
                ?: return@withContext emptyList()
            val postDoc  = Jsoup.parse(postHtml, postUrl)

            // TS: select all .button,.button1,.button2,.button3,.button4 elements
            // GDFLIX buttons → GdflixExtractor
            // Other buttons (non-Watch/Login/GoFile) → add as direct MKV links
            val results = mutableListOf<StreamResult>()
            val buttons = postDoc.select(".button2,.button1,.button3,.button4,.button")
            val skipWords = listOf("Watch", "Login", "GoFile", "Trailer", "Screenshot")

            for (btn in buttons) {
                val title = btn.text().trim()
                val link  = btn.attr("href").takeIf { it.startsWith("http") } ?: continue

                when {
                    title.contains("GDFLIX", true) -> {
                        results += GdflixExtractor.extract(link, "Filmyfly")
                    }
                    skipWords.none { title.contains(it, true) } && title.isNotEmpty() -> {
                        // Direct download link (HubCloud, GDrive, etc.)
                        val quality = when {
                            title.contains("4K",    true) || title.contains("2160", true) -> "4K"
                            title.contains("1080",  true) -> "1080P"
                            title.contains("720",   true) -> "720P"
                            title.contains("480",   true) -> "480P"
                            else -> "HD"
                        }
                        when {
                            link.contains("hubcloud") || link.contains("vcloud") || link.contains("hubdrive") ->
                                results += HubCloudExtractor.extract(link, "Filmyfly")
                            else ->
                                results += StreamResult(
                                    url = link, quality = quality,
                                    type = StreamType.MKV,
                                    source = "Filmyfly",
                                    label = "$quality — Filmyfly [$title]"
                                )
                        }
                    }
                }
            }

            // Fallback: scan for any gdflix/gdrive links
            if (results.isEmpty()) {
                postDoc.select("a[href*=gdflix], a[href*=gd-]")
                    .take(2).forEach { el ->
                        results += GdflixExtractor.extract(el.attr("href"), "Filmyfly")
                    }
            }
            results
        } catch (e: Exception) {
            Log.w(TAG, "Filmyfly error: ${e.message}")
            emptyList()
        }
    }

    private fun cleanTitle(title: String) = title
        .replace(Regex("""[:\"'!?.,]"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
}
