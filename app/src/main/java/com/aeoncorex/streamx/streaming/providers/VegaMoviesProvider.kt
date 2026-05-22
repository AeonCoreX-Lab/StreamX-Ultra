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
//  VegaMoviesProvider.kt
//  Best for Hindi Bollywood + South Indian dubbed + Hollywood Hindi.
//  Uses Vega's search API (Typesense) → HubCloud + filepress extractors.
//
//  Ported from: vega-providers/dist/vega/posts.js + stream.js
// ─────────────────────────────────────────────────────────────────────────────
object VegaMoviesProvider {

    private const val TAG = "VegaMoviesProvider"

    // vega posts.js headers — exact copy required for Cloudflare bypass
    private val HEADERS = mapOf(
        "Accept"               to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
        "Cache-Control"        to "no-store",
        "Accept-Language"      to "en-US,en;q=0.9",
        "DNT"                  to "1",
        "sec-ch-ua"            to """"Not_A Brand";v="8", "Chromium";v="120", "Microsoft Edge";v="120"""",
        "sec-ch-ua-mobile"     to "?0",
        "sec-ch-ua-platform"   to """"Windows"""",
        "Sec-Fetch-Dest"       to "document",
        "Sec-Fetch-Mode"       to "navigate",
        "Sec-Fetch-Site"       to "none",
        "Sec-Fetch-User"       to "?1",
        "Cookie"               to "xla=s4t; _ga=GA1.1.1081149560.1756378968",
        "Upgrade-Insecure-Requests" to "1",
        "User-Agent"           to HttpClient.DESKTOP_UA,
    )

    suspend fun fetch(req: ProviderRequest): List<StreamResult> = withContext(Dispatchers.IO) {
        try {
            val base = ModflixConfig.get("Vega")

            // Vega has a Typesense search API endpoint
            val query     = cleanTitle(req.title)
            val searchUrl = "$base/search.php?q=${query.replace(" ", "+")}&page=1"
            Log.d(TAG, "Vega search: $searchUrl")

            val searchJson = HttpClient.getJson(searchUrl, HEADERS)
            val postUrl = if (searchJson != null) {
                findPostViaApi(searchJson, req)
            } else {
                // Fallback: HTML search
                findPostViaHtml("$base/page/1/?s=${query.replace(" ", "+")}", req, base)
            } ?: return@withContext emptyList()

            Log.d(TAG, "Vega post: $postUrl")
            // FIX: store raw HTML so extractMovie() can use it for regex scanning
            val postHtml = HttpClient.getHtml(postUrl, HEADERS) ?: return@withContext emptyList()
            val postDoc  = Jsoup.parse(postHtml, postUrl)

            val results = mutableListOf<StreamResult>()

            if (req.isSeries) {
                results += extractSeries(postDoc, req)
            } else {
                // FIX: pass postHtml to extractMovie so it can do regex scan
                results += extractMovie(postDoc, postUrl, postHtml)
            }

            Log.d(TAG, "Vega: ${results.size} streams")
            results
        } catch (e: Exception) {
            Log.w(TAG, "Vega error: ${e.message}")
            emptyList()
        }
    }

    private fun cleanTitle(title: String) = title
        .replace(Regex("""[:"'!?.,]"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()

    private fun findPostViaApi(json: String, req: ProviderRequest): String? {
        return try {
            val hits   = JSONObject(json).optJSONArray("hits") ?: return null
            val titleL = req.title.lowercase()
            for (i in 0 until hits.length()) {
                val doc   = hits.getJSONObject(i).optJSONObject("document") ?: continue
                val title = doc.optString("post_title", "").lowercase()
                if (title.contains(titleL.take(6))) {
                    return doc.optString("permalink").takeIf { it.startsWith("http") }
                }
            }
            hits.optJSONObject(0)?.optJSONObject("document")?.optString("permalink")
                ?.takeIf { it.startsWith("http") }
        } catch (e: Exception) { null }
    }

    private fun findPostViaHtml(url: String, req: ProviderRequest, base: String): String? {
        val html   = HttpClient.getHtml(url, HEADERS) ?: return null
        val doc    = Jsoup.parse(html, base)
        val titleL = req.title.lowercase()
        // vega: .blog-items,.post-list,#archive-container,.movies-grid > children > find a
        val items  = doc.select(".blog-items article a, .post-list article a, #archive-container a, .movies-grid article a, .entry-list-item a")
        for (el in items) {
            val text      = (el.attr("title") + " " + el.text()).lowercase()
            val textClean = text.replace("download", "").trim()
            if (textClean.contains(titleL.take(4)))
                return el.attr("href").takeIf { it.startsWith("http") }
        }
        return items.firstOrNull()?.attr("href")?.takeIf { it.startsWith("http") }
    }

    // FIX: added 'html: String' parameter — extractMovie now receives raw HTML
    // so it can do regex scanning for cloud links (previously used outer-scope
    // 'postHtml' which is not accessible from this private function).
    private fun extractMovie(
        doc    : org.jsoup.nodes.Document,
        postUrl: String,
        html   : String
    ): List<StreamResult> {
        val results = mutableListOf<StreamResult>()

        // TS: match(/<a\s+href="([^"]*cloud\.[^"]*)"/)
        // Catches vcloud., hubcloud., hubdrive., any URL with cloud. in it
        val cloudPattern = Regex("""<a\s+href="([^"]*cloud\.[^"]*)"""", RegexOption.IGNORE_CASE)
        val cloudLinks = cloudPattern.findAll(html)          // FIX: was postHtml (unresolved)
            .map { it.groupValues[1] }
            .filter { it.startsWith("http") }
            .distinct().take(3).toList()

        // Fallback: Jsoup for hubcloud/hubdrive explicit
        val jsoupLinks = doc.select("a[href*=hubcloud], a[href*=hubdrive], a[href*=vcloud]")
            .map { it.attr("href") }
            .filter { it.startsWith("http") }
            .distinct().take(3)

        (cloudLinks + jsoupLinks).distinct().take(3).forEach { link ->
            results += HubCloudExtractor.extract(link, "VegaMovies")
        }

        // Filepress links (separate extractor - direct API)
        val filepressLinks = doc.select("a[href*=filepress]")
            .map { it.attr("href") }.distinct().take(2)

        filepressLinks.forEach { link ->
            extractFilepress(link)?.let { results += it }
        }

        return results
    }

    private fun extractSeries(doc: org.jsoup.nodes.Document, req: ProviderRequest): List<StreamResult> {
        val epText   = "episode ${req.episode}"
        val allLinks = doc.select("a[href*=hubcloud], a[href*=hubdrive], a[href*=/drive/]")

        // Try episode-specific links near matching heading
        val headings = doc.select("h2,h3,h4,strong,b")
            .filter { it.text().lowercase().contains(epText) }

        if (headings.isNotEmpty()) {
            var next = headings.first().nextElementSibling()
            repeat(6) {
                val link = next?.select("a[href*=hubcloud], a[href*=hubdrive]")
                    ?.firstOrNull()?.attr("href")
                if (!link.isNullOrEmpty()) {
                    return HubCloudExtractor.extract(link, "VegaMovies")
                }
                next = next?.nextElementSibling()
            }
        }
        // Fallback
        return allLinks.take(2).flatMap {
            HubCloudExtractor.extract(it.attr("href"), "VegaMovies")
        }
    }

    // ── Filepress extractor ───────────────────────────────────────────────────
    private fun extractFilepress(link: String): StreamResult? {
        return try {
            val id      = link.split("/").last()
            val baseUrl = link.split("/").dropLast(2).joinToString("/")

            val body1  = """{"id":"$id","method":"indexDownlaod","captchaValue":null}"""
            val resp1  = HttpClient.postJson("$baseUrl/api/file/downlaod/", body1,
                mapOf("Referer" to baseUrl)) ?: return null
            val json1  = JSONObject(resp1)
            if (!json1.optBoolean("status")) return null

            val token  = json1.optString("data")
            val body2  = """{"id":"$token","method":"indexDownlaod","captchaValue":null}"""
            val resp2  = HttpClient.postJson("$baseUrl/api/file/downlaod2/", body2,
                mapOf("Referer" to baseUrl)) ?: return null
            val json2  = JSONObject(resp2)
            val url    = json2.optJSONArray("data")?.optString(0) ?: return null

            StreamResult(
                url    = url, type = StreamType.MKV,
                source = "VegaMovies (Filepress)",
                label  = "HD — VegaMovies [Filepress]"
            )
        } catch (e: Exception) { null }
    }
}
