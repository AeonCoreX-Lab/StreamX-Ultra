package com.aeoncorex.streamx.streaming.extractors

import android.util.Base64
import android.util.Log
import com.aeoncorex.streamx.streaming.HttpClient
import com.aeoncorex.streamx.streaming.StreamResult
import com.aeoncorex.streamx.streaming.StreamType
import okhttp3.Request
import okhttp3.Headers
import org.jsoup.Jsoup

// ─────────────────────────────────────────────────────────────────────────────
//  HubCloudExtractor.kt
//  Ported from vega-providers/dist/hdhub4u/stream.js → hubcloudExtractor()
//  Used by hdhub4u, vegamovies, katmovies, topmovies
//
//  Chain: HubCloud page → vcloud redirect → download buttons → final link
// ─────────────────────────────────────────────────────────────────────────────
object HubCloudExtractor {

    private const val TAG = "HubCloudExtractor"

    // Exact headers vega uses — cf_clearance is Cloudflare bypass
    // sec-ch-ua headers are required by Cloudflare Bot Management
    private val HUBCLOUD_HEADERS = mapOf(
        "Cookie" to "ext_name=ojplmecpdpgccookcobabopnaifgidhf; xla=s4t; cf_clearance=woQrFGXtLfmEMBEiGUsVHrUBMT8s3cmguIzmMjmvpkg-1770053679-1.2.1.1-xBrQdciOJsweUF6F2T_OtH6jmyanN_TduQ0yslc_XqjU6RcHSxI7.YOKv6ry7oYo64868HYoULnVyww536H2eVI3R2e4wKzsky6abjPdfQPxqpUaXjxfJ02o6jl3_Vkwr4uiaU7Wy596Vdst3y78HXvVmKdIohhtPvp.vZ9_L7wvWdce0GRixjh_6JiqWmWMws46hwEt3hboaS1e1e4EoWCvj5b0M_jVwvSxBOAW5emFzvT3QrnRh4nyYmKDERnY",
        "User-Agent"           to HttpClient.DESKTOP_UA,
        "Accept"               to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language"      to "en-US,en;q=0.9",
        "Referer"              to "https://google.com",
        "sec-ch-ua"            to """"Not_A Brand";v="8", "Chromium";v="120", "Microsoft Edge";v="120"""",
        "sec-ch-ua-mobile"     to "?0",
        "sec-ch-ua-platform"   to """"Windows"""",
        "Sec-Fetch-Dest"       to "document",
        "Sec-Fetch-Mode"       to "navigate",
        "Sec-Fetch-Site"       to "none",
        "Sec-Fetch-User"       to "?1",
        "Upgrade-Insecure-Requests" to "1",
        "Cache-Control"        to "no-store",
        "DNT"                  to "1",
    )

    fun extract(hubLink: String, sourceName: String = "HubCloud"): List<StreamResult> {
        return try {
            Log.d(TAG, "Extracting: $hubLink")
            val baseUrl = hubLink.split("/").take(3).joinToString("/")

            // Step 1: Fetch HubCloud page
            val html = HttpClient.getHtml(hubLink, HUBCLOUD_HEADERS) ?: return emptyList()

            // Step 2: Decode vcloud link — `var url = 'BASE64_AFTER_r='`
            val vcloudLink = resolveVcloudLink(html, baseUrl, hubLink)
            Log.d(TAG, "vcloudLink: $vcloudLink")

            // Step 3: Follow vcloud to download buttons page
            val vcloudHtml = fetchFollowRedirect(vcloudLink, HUBCLOUD_HEADERS) ?: return emptyList()
            val doc = Jsoup.parse(vcloudHtml, vcloudLink)

            // Step 4: Parse download buttons
            val results = mutableListOf<StreamResult>()
            val buttons = doc.select(".btn-success.btn-lg.h6, .btn-danger, .btn-secondary")

            for (el in buttons) {
                val href = el.attr("href").trim()
                if (href.isEmpty()) continue

                val resolved = resolveButtonLink(href, vcloudLink, HUBCLOUD_HEADERS)
                if (resolved.isNullOrEmpty()) continue

                val server = when {
                    href.contains("pixeld")           -> "Pixeldrain"
                    href.contains(".dev")              -> "CF Worker"
                    href.contains("hubcloud") ||
                    href.contains("/?id=")             -> "HubCloud"
                    href.contains("cloudflarestorage") -> "CF Storage"
                    href.contains("fastdl") ||
                    href.contains("fsl.")              -> "FastDL"
                    href.contains("hubcdn")            -> "HubCDN"
                    else                               -> sourceName
                }

                val quality = detectQuality(resolved)
                results.add(StreamResult(
                    url      = resolved,
                    quality  = quality,
                    type     = StreamType.MKV,
                    source   = "$sourceName ($server)",
                    label    = "$quality — $sourceName [$server]"
                ))
            }

            // Fallback: scan for .mkv direct links in page
            if (results.isEmpty()) {
                val mkvRe = Regex("""https?://[^\s"'<>]+\.mkv[^\s"'<>]*""")
                mkvRe.findAll(vcloudHtml).forEach { m ->
                    results.add(StreamResult(
                        url    = m.value,
                        type   = StreamType.MKV,
                        source = sourceName,
                        label  = "MKV — $sourceName"
                    ))
                }
            }

            Log.d(TAG, "Found ${results.size} streams from $hubLink")
            results
        } catch (e: Exception) {
            Log.e(TAG, "Error: ${e.message}")
            emptyList()
        }
    }

    // ── Decode vcloud link from HubCloud page ─────────────────────────────────
    private fun resolveVcloudLink(html: String, baseUrl: String, fallback: String): String {
        // Pattern: var url = 'BASE64_STRING';
        val match = Regex("""var\s+url\s*=\s*'([^']+)'""").find(html)
        if (match != null) {
            val encoded = match.groupValues[1]
            val afterR  = encoded.split("r=").getOrNull(1)
            if (afterR != null) {
                val decoded = runCatching {
                    String(Base64.decode(afterR, Base64.DEFAULT))
                }.getOrNull()
                if (!decoded.isNullOrEmpty()) return decoded
            }
            // Try plain decode
            val plain = runCatching { String(Base64.decode(encoded, Base64.DEFAULT)) }.getOrNull()
            if (!plain.isNullOrEmpty() && plain.startsWith("http")) return plain
        }

        // Fallback: look for .fa-file-download parent href
        val doc   = Jsoup.parse(html, baseUrl)
        val href  = doc.selectFirst(".fa-file-download.fa-lg")?.parent()?.attr("href")
        if (!href.isNullOrEmpty()) {
            return if (href.startsWith("/")) "$baseUrl$href" else href
        }
        return fallback
    }

    // ── Fetch page, following meta-refresh and header redirects ───────────────
    private fun fetchFollowRedirect(url: String, headers: Map<String, String>): String? {
        var current = url
        repeat(3) {
            val html = HttpClient.getHtml(current, headers) ?: return null
            // Meta refresh
            val meta = Regex("""<META[^>]+url=([^"'>]+)""", RegexOption.IGNORE_CASE).find(html)
            if (meta != null) { current = meta.groupValues[1]; return@repeat }
            return html
        }
        return null
    }

    // ── Resolve each download button link to final stream URL ─────────────────
    private fun resolveButtonLink(href: String, referer: String, headers: Map<String, String>): String? {
        return when {
            href.contains("pixeld") && !href.contains("api") -> {
                val token = href.split("/").last()
                val base2 = href.split("/").dropLast(2).joinToString("/")
                "$base2/api/file/$token"
            }

            href.contains(".dev") && !href.contains("/?id=") -> href

            href.contains("hubcloud") || href.contains("/?id=") -> {
                // Mirror vega's exact pattern:
                // fetch(link, {method:"HEAD", redirect:"manual"}) → check 3xx Location header
                try {
                    val req1 = okhttp3.Request.Builder().url(href)
                        .head()
                        .header("User-Agent", HttpClient.DESKTOP_UA)
                        .header("Referer", referer)
                        .build()
                    var link1 = href
                    HttpClient.noRedirect.newCall(req1).execute().use { r1 ->
                        link1 = if (r1.code in 300..399)
                            r1.header("Location") ?: href
                        else if (r1.request.url.toString() != href)
                            r1.request.url.toString()
                        else href
                    }
                    // If googleusercontent, extract ?link= param
                    if (link1.contains("googleusercontent")) {
                        link1 = link1.split("?link=").getOrNull(1) ?: link1
                    } else {
                        // Second redirect hop
                        val req2 = okhttp3.Request.Builder().url(link1)
                            .head()
                            .header("User-Agent", HttpClient.DESKTOP_UA)
                            .header("Referer", href)
                            .build()
                        HttpClient.noRedirect.newCall(req2).execute().use { r2 ->
                            val loc2 = if (r2.code in 300..399)
                                r2.header("Location") ?: link1
                            else link1
                            link1 = loc2.split("?link=").getOrNull(1) ?: loc2
                        }
                    }
                    link1
                } catch (e: Exception) { href }
            }

            href.contains("cloudflarestorage") -> href
            href.contains("fastdl") || href.contains("fsl.") -> href
            href.contains("hubcdn") && !href.contains("/?id=") -> href

            href.endsWith(".mkv") || href.contains("?token=") -> href

            else -> null
        }
    }

    // ── Detect quality from URL / filename ────────────────────────────────────
    private fun detectQuality(url: String): String = when {
        url.contains("2160", true) || url.contains("4k", true) -> "4K"
        url.contains("1080", true)                              -> "1080p"
        url.contains("720", true)                               -> "720p"
        url.contains("480", true)                               -> "480p"
        else                                                     -> "HD"
    }
}
