package com.aeoncorex.streamx.streaming

import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.concurrent.TimeUnit

// ─────────────────────────────────────────────────────────────────────────────
//  HttpClient.kt — Shared HTTP helpers (OkHttp + Jsoup)
//
//  CHANGE LOG (bundle-addon fix):
//    • Added FetchResult data class — body + status + response headers + final URL
//    • Added fetchRaw()          — used by JsAxios / fetch polyfill in JS engine
//    • All existing public API (getHtml, getJson, postJson, getFinalUrl) unchanged
// ─────────────────────────────────────────────────────────────────────────────
object HttpClient {

    val okhttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /** Client that does NOT follow redirects — used for HEAD so we can capture Location */
    val noRedirect: OkHttpClient = okhttp.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 Edg/131.0.0.0"

    val BASE_HEADERS = mapOf(
        "User-Agent"               to DESKTOP_UA,
        "Accept"                   to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language"          to "en-US,en;q=0.9",
        "Cache-Control"            to "no-store",
        "DNT"                      to "1",
        "Upgrade-Insecure-Requests" to "1",
        "sec-ch-ua"                to """"Not_A Brand";v="8", "Chromium";v="120", "Microsoft Edge";v="120"""",
        "sec-ch-ua-mobile"         to "?0",
        "sec-ch-ua-platform"       to """"Windows"""",
        "Sec-Fetch-Dest"           to "document",
        "Sec-Fetch-Mode"           to "navigate",
        "Sec-Fetch-Site"           to "none",
        "Sec-Fetch-User"           to "?1",
    )

    // ─────────────────────────────────────────────────────────────────────────
    //  FetchResult — carries body + status + all response headers + final URL
    //  Used by JsAxios.get/head/post and the JS fetch() polyfill
    // ─────────────────────────────────────────────────────────────────────────
    data class FetchResult(
        val body:            String                 = "",
        val status:          Int,
        val responseHeaders: Map<String, String>    = emptyMap(),
        val finalUrl:        String                 = ""
    )

    /**
     * Generic fetch — covers GET / POST / HEAD with optional redirect control.
     * Returns a [FetchResult] including lower-cased response headers.
     * The special key "x-final-url" is always present and holds the URL after
     * any redirects (or the original URL for HEAD no-redirect calls).
     */
    fun fetchRaw(
        url:             String,
        method:          String                 = "GET",
        extraHeaders:    Map<String, String>    = emptyMap(),
        body:            String?                = null,
        contentType:     String                 = "application/x-www-form-urlencoded",
        followRedirects: Boolean                = true
    ): FetchResult {
        return try {
            val client = if (followRedirects) okhttp else noRedirect

            val hdrsBuilder = Headers.Builder().apply {
                BASE_HEADERS.forEach { (k, v) -> add(k, v) }
                extraHeaders.forEach  { (k, v) -> add(k, v) }
            }

            val reqBuilder = Request.Builder().url(url).headers(hdrsBuilder.build())

            when (method.uppercase()) {
                "POST" -> {
                    val ct = extraHeaders["Content-Type"] ?: extraHeaders["content-type"] ?: contentType
                    reqBuilder.post((body ?: "").toRequestBody(ct.toMediaType()))
                }
                "HEAD" -> reqBuilder.head()
                else   -> reqBuilder.get()
            }

            client.newCall(reqBuilder.build()).execute().use { resp ->
                val respHeaders = resp.headers.toMultimap()
                    .entries.associate { (k, v) -> k.lowercase() to v.first() }
                    .toMutableMap()
                val finalUrl = resp.request.url.toString()
                respHeaders["x-final-url"] = finalUrl
                // Also expose Location as lower-case key for fetch polyfill
                resp.header("Location")?.let { respHeaders["location"] = it }

                FetchResult(
                    body            = if (method.uppercase() == "HEAD") "" else (resp.body?.string() ?: ""),
                    status          = resp.code,
                    responseHeaders = respHeaders,
                    finalUrl        = finalUrl
                )
            }
        } catch (e: Exception) {
            FetchResult(body = "", status = 0, responseHeaders = emptyMap(), finalUrl = url)
        }
    }

    // ── Existing public helpers (unchanged) ───────────────────────────────────

    fun getHtml(url: String, headers: Map<String, String> = emptyMap()): String? {
        return try {
            val req = Request.Builder().url(url)
                .headers(Headers.Builder().apply {
                    BASE_HEADERS.forEach { (k, v) -> add(k, v) }
                    headers.forEach    { (k, v) -> add(k, v) }
                }.build())
                .build()
            okhttp.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        } catch (e: Exception) { null }
    }

    fun getDoc(url: String, headers: Map<String, String> = emptyMap()): Document? {
        val html = getHtml(url, headers) ?: return null
        return Jsoup.parse(html, url)
    }

    fun getJson(url: String, headers: Map<String, String> = emptyMap()): String? {
        return try {
            val req = Request.Builder().url(url)
                .headers(Headers.Builder().apply {
                    add("User-Agent", DESKTOP_UA)
                    add("Accept", "application/json")
                    headers.forEach { (k, v) -> add(k, v) }
                }.build())
                .build()
            okhttp.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        } catch (e: Exception) { null }
    }

    fun postJson(url: String, body: String, headers: Map<String, String> = emptyMap()): String? {
        return try {
            val req = Request.Builder().url(url)
                .headers(Headers.Builder().apply {
                    add("Content-Type", "application/json")
                    add("User-Agent", DESKTOP_UA)
                    headers.forEach { (k, v) -> add(k, v) }
                }.build())
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            okhttp.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        } catch (e: Exception) { null }
    }

    fun getFinalUrl(url: String, referer: String = ""): String {
        return try {
            val req = Request.Builder().url(url)
                .head()
                .headers(Headers.Builder().apply {
                    add("User-Agent", DESKTOP_UA)
                    if (referer.isNotEmpty()) add("Referer", referer)
                }.build())
                .build()
            okhttp.newCall(req).execute().use { it.request.url.toString() }
        } catch (e: Exception) { url }
    }
}
