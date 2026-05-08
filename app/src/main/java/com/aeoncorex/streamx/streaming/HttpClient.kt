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
// ─────────────────────────────────────────────────────────────────────────────
object HttpClient {

    val okhttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // No-redirect client (for manually following redirects to capture Location header)
    val noRedirect: OkHttpClient = okhttp.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 Edg/131.0.0.0"

    val BASE_HEADERS = mapOf(
        "User-Agent"               to DESKTOP_UA,
        "Accept"                   to "text/html,application/xhtml+xml,*/*;q=0.8",
        "Accept-Language"          to "en-US,en;q=0.9",
        "DNT"                      to "1",
        "Upgrade-Insecure-Requests" to "1",
    )

    // ── GET HTML ─────────────────────────────────────────────────────────────
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

    // ── GET JSON ─────────────────────────────────────────────────────────────
    fun getJson(url: String, headers: Map<String, String> = emptyMap()): String? {
        return try {
            val req = Request.Builder().url(url)
                .headers(Headers.Builder().apply {
                    add("User-Agent", DESKTOP_UA)
                    add("Accept", "application/json, */*")
                    headers.forEach { (k, v) -> add(k, v) }
                }.build())
                .build()
            okhttp.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        } catch (e: Exception) { null }
    }

    // ── POST ─────────────────────────────────────────────────────────────────
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

    // ── HEAD (follow redirects, return final URL) ─────────────────────────────
    fun getFinalUrl(url: String, referer: String = ""): String {
        return try {
            val req = Request.Builder().url(url)
                .head()
                .headers(Headers.Builder().apply {
                    add("User-Agent", DESKTOP_UA)
                    if (referer.isNotEmpty()) add("Referer", referer)
                }.build())
                .build()
            // Use okhttp (follows redirects) to get final URL
            okhttp.newCall(req).execute().use { it.request.url.toString() }
        } catch (e: Exception) { url }
    }
}
