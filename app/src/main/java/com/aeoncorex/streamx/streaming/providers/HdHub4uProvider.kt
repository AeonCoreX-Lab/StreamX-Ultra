package com.aeoncorex.streamx.streaming.providers

import android.util.Log
import com.aeoncorex.streamx.streaming.HttpClient
import com.aeoncorex.streamx.streaming.ModflixConfig
import com.aeoncorex.streamx.streaming.ProviderRequest
import com.aeoncorex.streamx.streaming.StreamResult
import com.aeoncorex.streamx.streaming.extractors.HubCloudExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

// ─────────────────────────────────────────────────────────────────────────────
//  HdHub4uProvider.kt
//  Search hdhub4u by title → find movie/series post → HubCloud extraction.
//  Best for: Hindi + English dubbed content, 1080p/4K.
//
//  Ported from: vega-providers/dist/hdhub4u/posts.js + stream.js
// ─────────────────────────────────────────────────────────────────────────────
object HdHub4uProvider {

    private const val TAG = "HdHub4uProvider"

    // Match vega's exact hdbHeaders
    private val HEADERS = mapOf(
        "Cookie"             to "xla=s4t",
        "Referer"            to "https://google.com",
        "User-Agent"         to HttpClient.DESKTOP_UA,
        "sec-ch-ua"          to """"Not_A Brand";v="8", "Chromium";v="120", "Microsoft Edge";v="120"""",
        "sec-ch-ua-mobile"   to "?0",
        "sec-ch-ua-platform" to """"Windows"""",
        "Sec-Fetch-Dest"     to "document",
        "Sec-Fetch-Mode"     to "navigate",
        "Sec-Fetch-Site"     to "none",
        "Cache-Control"      to "no-store",
    )

    suspend fun fetch(req: ProviderRequest): List<StreamResult> = withContext(Dispatchers.IO) {
        try {
            val base    = ModflixConfig.get("hdhub")
            val query   = buildSearchQuery(req)
            val searchUrl = "$base/page/1/?s=${query.replace(" ", "+")}"
            Log.d(TAG, "HdHub4u search: $searchUrl")

            val searchHtml = HttpClient.getHtml(searchUrl, HEADERS) ?: return@withContext emptyList()
            val postUrl    = findBestPost(searchHtml, req, base) ?: return@withContext emptyList()
            Log.d(TAG, "HdHub4u post: $postUrl")

            // Get post page
            val postHtml = HttpClient.getHtml(postUrl, HEADERS) ?: return@withContext emptyList()
            val postDoc  = Jsoup.parse(postHtml, postUrl)

            // For series: find the specific season/episode section
            val hubLinks = if (req.isSeries) {
                findSeriesLinks(postDoc, req)
            } else {
                findMovieLinks(postDoc)
            }

            if (hubLinks.isEmpty()) {
                Log.d(TAG, "HdHub4u: no hub links found in $postUrl")
                return@withContext emptyList()
            }

            hubLinks.flatMap { link ->
                Log.d(TAG, "HdHub4u extracting: $link")
                HubCloudExtractor.extract(link, "HDHub4u")
            }
        } catch (e: Exception) {
            Log.w(TAG, "HdHub4u error: ${e.message}")
            emptyList()
        }
    }

    private fun buildSearchQuery(req: ProviderRequest): String {
        val titleClean = req.title
            .replace(Regex("""[:"'!?.,]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        return if (req.isSeries) {
            "$titleClean Season ${req.season}"
        } else {
            "$titleClean ${req.year ?: ""}"
        }.trim()
    }

    private fun findBestPost(html: String, req: ProviderRequest, base: String): String? {
        val doc    = Jsoup.parse(html, base)
        // vega uses: element.find('figure').find('img').attr('alt') for title
        // and element.find('a').attr('href') for link
        val titleL = req.title.lowercase()
            .replace("download", "")
            .replace(Regex("""[^a-z0-9\s]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

        // Try .recent-movies children (vega's selector)
        val container = doc.selectFirst(".recent-movies") ?: doc.body()
        val items     = container.children()

        for (el in items) {
            // vega: figure > img[alt]
            val altTitle = el.selectFirst("figure img")?.attr("alt") ?: ""
            val aTitle   = el.selectFirst("a")?.attr("title") ?: ""
            val combined = "$altTitle $aTitle".lowercase()
                .replace("download", "").trim()

            if (combined.isNotEmpty() && combined.contains(titleL.take(4))) {
                val href = el.selectFirst("a")?.attr("href") ?: continue
                return if (href.startsWith("http")) href else "$base$href"
            }
        }

        // Fallback: any article link
        return doc.selectFirst(".recent-movies article a[href], .entry-title a[href]")?.attr("href")
    }

    private fun findMovieLinks(doc: org.jsoup.nodes.Document): List<String> {
        return doc.select("a[href*=hubcloud], a[href*=hubdrive], a[href*=/drive/]")
            .map { it.attr("href") }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(3)
    }

    private fun findSeriesLinks(doc: org.jsoup.nodes.Document, req: ProviderRequest): List<String> {
        // Look for episode section: "Episode X" heading, then find the hub link below it
        val episodeText = "episode ${req.episode}"
        val allLinks    = doc.select("a[href*=hubcloud], a[href*=hubdrive], a[href*=/drive/]")

        // Try to find links near episode heading
        val epHeadings  = doc.select("h2, h3, h4, strong, b")
            .filter { it.text().lowercase().contains(episodeText) }

        if (epHeadings.isNotEmpty()) {
            val heading = epHeadings.first()
            // Get next sibling links
            var next = heading.nextElementSibling()
            repeat(5) {
                next?.select("a[href*=hubcloud], a[href*=hubdrive]")
                    ?.firstOrNull()?.attr("href")
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { return listOf(it) }
                next = next?.nextElementSibling()
            }
        }

        // Fallback: return all hub links (first 2)
        return allLinks.map { it.attr("href") }.distinct().take(2)
    }
}
