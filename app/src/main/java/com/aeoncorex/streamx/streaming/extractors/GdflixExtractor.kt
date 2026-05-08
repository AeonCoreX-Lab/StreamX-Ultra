package com.aeoncorex.streamx.streaming.extractors

import android.util.Log
import com.aeoncorex.streamx.streaming.HttpClient
import com.aeoncorex.streamx.streaming.StreamResult
import com.aeoncorex.streamx.streaming.StreamType
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Headers
import org.json.JSONObject
import org.jsoup.Jsoup

// ─────────────────────────────────────────────────────────────────────────────
//  GdflixExtractor.kt
//  Ported from vega-providers/dist/filmyfly/stream.js → gdflixExtractor()
//  Handles GDFLIX CDN links → ResumeBot / G-Drive / Instant links
// ─────────────────────────────────────────────────────────────────────────────
object GdflixExtractor {

    private const val TAG = "GdflixExtractor"

    fun extract(gdflixLink: String, sourceName: String = "GDFLIX"): List<StreamResult> {
        return try {
            Log.d(TAG, "Extracting GDFLIX: $gdflixLink")
            val results = mutableListOf<StreamResult>()

            // Fetch GDFLIX page (may redirect via body onload)
            var html = HttpClient.getHtml(gdflixLink, HttpClient.BASE_HEADERS) ?: return emptyList()
            var doc  = Jsoup.parse(html, gdflixLink)

            // Handle body onload redirect: location.replace('URL')
            val onload = doc.body().attr("onload")
            if (onload.contains("location.replace")) {
                val newLink = onload.split("location.replace('").getOrNull(1)
                    ?.split("'")?.firstOrNull()
                if (!newLink.isNullOrEmpty()) {
                    html = HttpClient.getHtml(newLink, HttpClient.BASE_HEADERS) ?: html
                    doc  = Jsoup.parse(html, newLink)
                }
            }

            val baseUrl = gdflixLink.split("/").take(3).joinToString("/")

            // ── Try ResumeBot / ResumeCloud link (.btn-secondary) ────────────
            try {
                val resumeHref = doc.selectFirst(".btn-secondary")?.attr("href") ?: ""
                if (resumeHref.contains("indexbot")) {
                    // POST to get token, then POST to download
                    val resumeHtml  = HttpClient.getHtml(resumeHref) ?: ""
                    val tokenMatch  = Regex("""formData\.append\('token',\s*'([a-f0-9]+)'\)""")
                        .find(resumeHtml)?.groupValues?.getOrNull(1) ?: ""
                    val pathMatch   = Regex("""fetch\('/download\?id=([a-zA-Z0-9/+]+)'\)""")
                        .find(resumeHtml)?.groupValues?.getOrNull(1) ?: ""
                    val botBase     = resumeHref.split("/download")[0]

                    if (tokenMatch.isNotEmpty() && pathMatch.isNotEmpty()) {
                        val body  = """{"token":"$tokenMatch"}"""
                        val resp  = HttpClient.postJson(
                            "$botBase/download?id=$pathMatch", body,
                            mapOf("Referer" to resumeHref,
                                  "Cookie"  to "PHPSESSID=7e9658ce7c805dab5bbcea9046f7f308")
                        )
                        val dlUrl = resp?.let { JSONObject(it).optString("url") }
                        if (!dlUrl.isNullOrEmpty()) {
                            results.add(StreamResult(
                                url    = dlUrl, type = StreamType.MKV,
                                source = "$sourceName (ResumeBot)",
                                label  = "HD — $sourceName [ResumeBot]"
                            ))
                        }
                    }
                } else if (resumeHref.isNotEmpty()) {
                    val url2  = if (resumeHref.startsWith("/")) "$baseUrl$resumeHref" else resumeHref
                    val html2 = HttpClient.getHtml(url2, HttpClient.BASE_HEADERS)
                    val link2 = Jsoup.parse(html2 ?: "", url2)
                        .selectFirst(".btn-success")?.attr("href")
                    if (!link2.isNullOrEmpty()) {
                        results.add(StreamResult(
                            url    = link2, type = StreamType.MKV,
                            source = "$sourceName (ResumeCloud)",
                            label  = "HD — $sourceName [ResumeCloud]"
                        ))
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "ResumeBot failed: ${e.message}")
            }

            // ── Try Instant / G-Drive link (.btn-danger) ─────────────────────
            try {
                val seed = doc.selectFirst(".btn-danger")?.attr("href") ?: ""
                if (seed.contains("?url=")) {
                    val instantToken = seed.split("=")[1]
                    val seedBase     = seed.split("/").take(3).joinToString("/") + "/api"
                    val body         = """{"keys":"$instantToken"}"""
                    val resp         = HttpClient.postJson(
                        seedBase, body, mapOf("x-token" to seedBase)
                    )
                    val json = resp?.let { runCatching { JSONObject(it) }.getOrNull() }
                    if (json != null && json.optBoolean("error") == false) {
                        val link3 = json.optString("url")
                        if (link3.isNotEmpty()) {
                            results.add(StreamResult(
                                url    = link3, type = StreamType.MKV,
                                source = "$sourceName (G-Drive Instant)",
                                label  = "HD — $sourceName [Instant]"
                            ))
                        }
                    }
                } else if (seed.isNotEmpty()) {
                    val finalUrl = seed.split("?url=").getOrNull(1) ?: seed
                    results.add(StreamResult(
                        url    = finalUrl, type = StreamType.MKV,
                        source = "$sourceName (G-Drive)",
                        label  = "HD — $sourceName [G-Drive]"
                    ))
                }
            } catch (e: Exception) {
                Log.d(TAG, "G-Drive failed: ${e.message}")
            }

            Log.d(TAG, "GDFLIX found ${results.size} streams")
            results
        } catch (e: Exception) {
            Log.e(TAG, "GDFLIX error: ${e.message}")
            emptyList()
        }
    }
}
