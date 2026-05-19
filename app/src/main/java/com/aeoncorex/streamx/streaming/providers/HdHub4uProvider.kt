package com.aeoncorex.streamx.streaming.providers

import android.util.Base64
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

object HdHub4uProvider {

    private const val TAG = "HdHub4uProvider"

    private val HEADERS = mapOf(
        "Accept"            to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language"   to "en-US,en;q=0.9",
        "DNT"               to "1",
        "Sec-Fetch-Dest"    to "document",
        "Sec-Fetch-Mode"    to "navigate",
        "Sec-Fetch-Site"    to "none",
        "Upgrade-Insecure-Requests" to "1",
        "Cookie"            to "ext_name=ojplmecpdpgccookcobabopnaifgidhf; xla=s4t; cf_clearance=woQrFGXtLfmEMBEiGUsVHrUBMT8s3cmguIzmMjmvpkg-1770053679-1.2.1.1-xBrQdciOJsweUF6F2T_OtH6jmyanN_TduQ0yslc_XqjU6RcHSxI7.YOKv6ry7oYo64868HYoULnVyww536H2eVI3R2e4wKzsky6abjPdfQPxqpUaXjxfJ02o6jl3_Vkwr4uiaU7Wy596Vdst3y78HXvVmKdIohhtPvp.vZ9_L7wvWdce0GRixjh_6JiqWmWMws46hwEt3hboaS1e1e4EoWCvj5b0M_jVwvSxBOAW5emFzvT3QrnRh4nyYmKDERnY",
        "User-Agent"        to HttpClient.DESKTOP_UA,
    )

    suspend fun fetch(req: ProviderRequest): List<StreamResult> = withContext(Dispatchers.IO) {
        try {
            val base = ModflixConfig.get("hdhub4u")
            val query = req.title
                .replace(Regex("""[:'"!?.,]"""), " ")
                .replace(Regex("""\s+"""), " ").trim()

            val searchUrl = "$base/?s=${query.replace(" ", "+")}"
            Log.d(TAG, "Searching: $searchUrl")
            val searchHtml = HttpClient.getHtml(searchUrl, HEADERS) ?: return@withContext emptyList()
            val searchDoc  = Jsoup.parse(searchHtml, base)

            val postUrl = findPost(searchDoc, req, base) ?: return@withContext emptyList()
            Log.d(TAG, "Post: $postUrl")

            val postHtml = HttpClient.getHtml(postUrl, HEADERS) ?: return@withContext emptyList()
            extractFromPost(postHtml, postUrl)
        } catch (e: Exception) {
            Log.w(TAG, "HdHub4u error: ${e.message}")
            emptyList()
        }
    }

    private fun findPost(doc: org.jsoup.nodes.Document, req: ProviderRequest, base: String): String? {
        val titleL = req.title.lowercase().take(6)
        val items  = doc.select(".result-item article a, .movies-list .ml-item a, .post-list article a, h2.title a, article a")
        for (el in items) {
            val text = (el.attr("title") + " " + el.text()).lowercase()
            if (text.contains(titleL)) {
                val href = el.absUrl("href").ifEmpty { el.attr("href") }
                if (href.startsWith("http")) return href
            }
        }
        return items.firstOrNull()?.absUrl("href")?.takeIf { it.startsWith("http") }
    }

    private fun extractFromPost(html: String, postUrl: String): List<StreamResult> {
        val doc     = Jsoup.parse(html, postUrl)
        val results = mutableListOf<StreamResult>()

        // CASE 1: direct hubcloud/hubdrive link on the post page
        val directCloud = doc.select("a[href*=hubcloud], a[href*=hubdrive], a[href*=vcloud]")
            .map { it.absUrl("href") }.filter { it.startsWith("http") }.distinct()
        if (directCloud.isNotEmpty()) {
            directCloud.take(2).forEach { link ->
                results += HubCloudExtractor.extract(link, "HdHub4u")
            }
            if (results.isNotEmpty()) return results
        }

        // CASE 2: Encoded link — TS: text.split("s('o','")[1]?.split("',180")[0]
        val encMatch = Regex("""s\('o','([^']+)',\s*180""").find(html)
        if (encMatch != null) {
            val encStr = encMatch.groupValues[1]
            val decoded = decodeString(encStr)
            if (decoded != null) {
                // TS: safeAtob(decoded.o) → redirect chain
                val rawO    = decoded.optString("o")
                val linkB64 = safeAtob(rawO) ?: rawO
                Log.d(TAG, "Decoded link: $linkB64")

                // getRedirectLinks — decode _wp_http cookies
                val redirectLink = getRedirectLinks(linkB64)
                val redirectHtml = HttpClient.getHtml(redirectLink, HEADERS)
                if (redirectHtml != null) {
                    val redirectDoc = Jsoup.parse(redirectHtml, redirectLink)

                    // TS: $('h3:contains("1080p")').find("a").attr("href")
                    var hubdriveLink = redirectDoc.select("h3:contains(1080p) a").firstOrNull()?.absUrl("href")
                        ?: Regex("""href="(https://hubcloud\.[^/]+/drive/[^"]+)"""").find(redirectHtml)?.groupValues?.get(1)

                    if (hubdriveLink?.contains("hubdrive") == true) {
                        val hubdriveHtml = HttpClient.getHtml(hubdriveLink, HEADERS)
                        if (hubdriveHtml != null) {
                            hubdriveLink = Jsoup.parse(hubdriveHtml, hubdriveLink)
                                .select(".btn.btn-primary.btn-user.btn-success1").attr("href")
                                .ifEmpty { hubdriveLink }
                        }
                    }

                    if (!hubdriveLink.isNullOrEmpty()) {
                        // META refresh redirect
                        val hubdriveHtml = HttpClient.getHtml(hubdriveLink, HEADERS)
                        val finalLink = hubdriveHtml?.let {
                            Regex("""META HTTP-EQUIV="refresh"[^"]*content="[^;]+;\s*url=([^"]+)"""", RegexOption.IGNORE_CASE)
                                .find(it)?.groupValues?.get(1)
                        } ?: hubdriveLink

                        results += HubCloudExtractor.extract(finalLink, "HdHub4u")
                    }
                }
            }
        }

        return results
    }

    // ── Exact port of TS decodeString ──────────────────────────────────────
    // atob → atob → rot13 → atob → JSON.parse
    private fun decodeString(encrypted: String): JSONObject? = try {
        var decoded = atob(encrypted)
        decoded = atob(decoded)
        decoded = rot13(decoded)
        decoded = atob(decoded)
        JSONObject(decoded)
    } catch (e: Exception) {
        Log.w(TAG, "decodeString failed: ${e.message}")
        null
    }

    // ── Exact port of TS getRedirectLinks ───────────────────────────────────
    // Finds _wp_http cookies, base64-decodes the combined string chain
    private fun getRedirectLinks(link: String): String {
        return try {
            val html = HttpClient.getHtml(link, emptyMap()) ?: return link
            val regex = Regex("""ck\('_wp_http_\d+','([^']+)'""")
            val combined = regex.findAll(html).joinToString("") { it.groupValues[1] }
            if (combined.isBlank()) return link

            // TS: decode(pen(decode(decode(combined)))) → JSON.parse
            val decoded = atob(rot13(atob(atob(combined))))
            val data    = JSONObject(decoded)
            val token   = btoa(data.optString("data"))
            data.optString("wp_http1") + "?re=" + token
        } catch (e: Exception) {
            Log.w(TAG, "getRedirectLinks: ${e.message}")
            link
        }
    }

    // ── Helper functions (ports of JS btoa/atob/rot13) ──────────────────────
    private fun atob(s: String): String =
        String(Base64.decode(s.trim(), Base64.DEFAULT), Charsets.UTF_8)

    private fun btoa(s: String): String =
        Base64.encodeToString(s.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    private fun safeAtob(s: String?): String? = try {
        if (s.isNullOrBlank()) null else atob(s)
    } catch (_: Exception) { null }

    private fun rot13(s: String): String = s.map { c ->
        when {
            c in 'a'..'z' -> 'a' + (c - 'a' + 13) % 26
            c in 'A'..'Z' -> 'A' + (c - 'A' + 13) % 26
            else -> c
        }
    }.joinToString("")
}
