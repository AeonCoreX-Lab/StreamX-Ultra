package com.aeoncorex.streamx.streaming.providers

import android.util.Log
import com.aeoncorex.streamx.streaming.HttpClient
import com.aeoncorex.streamx.streaming.ModflixConfig
import com.aeoncorex.streamx.streaming.ProviderRequest
import com.aeoncorex.streamx.streaming.StreamResult
import com.aeoncorex.streamx.streaming.extractors.GdflixExtractor
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

            // Find GDFLIX button links
            val gdLinks = postDoc.select(".button, .button1, .button2, .button3, .button4")
                .filter { it.text().contains("GDFLIX", true) }
                .map { it.attr("href") }
                .filter { it.startsWith("http") }

            if (gdLinks.isEmpty()) {
                // Also check all buttons
                val anyLinks = postDoc.select("a[href*=gdflix], a[href*=gd-], a[href*=drive]")
                    .map { it.attr("href") }.filter { it.isNotEmpty() }.take(2)
                return@withContext anyLinks.flatMap { GdflixExtractor.extract(it, "Filmyfly") }
            }

            gdLinks.take(2).flatMap { GdflixExtractor.extract(it, "Filmyfly") }
        } catch (e: Exception) {
            Log.w(TAG, "Filmyfly error: ${e.message}")
            emptyList()
        }
    }

    private fun cleanTitle(title: String) = title
        .replace(Regex("""[:"'!?.,]"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
}
