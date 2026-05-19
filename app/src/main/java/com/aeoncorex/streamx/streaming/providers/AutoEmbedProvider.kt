package com.aeoncorex.streamx.streaming.providers

import android.util.Log
import com.aeoncorex.streamx.streaming.HttpClient
import com.aeoncorex.streamx.streaming.ModflixConfig
import com.aeoncorex.streamx.streaming.ProviderRequest
import com.aeoncorex.streamx.streaming.StreamResult
import com.aeoncorex.streamx.streaming.StreamType
import com.aeoncorex.streamx.streaming.extractors.RiveKeyGen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder

// ─────────────────────────────────────────────────────────────────────────────
//  AutoEmbedProvider.kt
//  Best source for English + multi-language HLS streams.
//  Two sub-sources:
//    1. Webstreamr (webstreamr.hayd.uk) — needs IMDB ID, returns JSON with HLS
//    2. Rive (rivestream.app) — needs TMDB ID + secret key, 11 servers parallel
//
//  Ported from: vega-providers/dist/autoEmbed/stream.js
// ─────────────────────────────────────────────────────────────────────────────
object AutoEmbedProvider {

    private const val TAG = "AutoEmbedProvider"

    // Rive servers (all tried in parallel)
    private val RIVE_SERVERS = listOf(
        "flowcast", "asiacloud", "humpy", "primevids", "shadow",
        "hindicast", "animez", "aqua", "yggdrasil", "putafilme", "ophim"
    )

    suspend fun fetch(req: ProviderRequest): List<StreamResult> = coroutineScope {
        val results = mutableListOf<StreamResult>()

        val webstreamrJob = async { fetchWebstreamer(req) }
        val riveJob       = async { fetchRive(req) }

        results += webstreamrJob.await()
        results += riveJob.await()

        Log.d(TAG, "AutoEmbed: ${results.size} streams total")
        results
    }

    // ── Webstreamr — multi-language HLS, IMDB-based ───────────────────────────
    // URL: https://webstreamr.hayd.uk/{json_config}/stream/{type}/{imdbId}.json
    // The JSON config enables language subtracks: hi=on, de=on, fr=on etc.
    private suspend fun fetchWebstreamer(req: ProviderRequest): List<StreamResult> =
        withContext(Dispatchers.IO) {
            val imdbId = req.imdbId?.takeIf { it.isNotEmpty() } ?: return@withContext emptyList()
            try {
                val config  = """{"multi":"on","al":"on","de":"on","es":"on","fr":"on","hi":"on","it":"on","mx":"on","mediaFlowProxyUrl":"","mediaFlowProxyPassword":""}"""
                val typeStr = if (req.isSeries) "series" else "movie"
                val suffix  = if (req.isSeries) ":${req.season}:${req.episode}" else ""
                // Use encodeUri() — exact JS encodeURI() port.
                // encodeURI keeps : and , literal; URLEncoder.encode() wrongly converts them to %3A/%2C
                val fullUrl = "https://webstreamr.hayd.uk/$config/stream/$typeStr/$imdbId$suffix.json"
                val url     = encodeUri(fullUrl)

                Log.d(TAG, "Webstreamr: $url")
                val json = HttpClient.getJson(url) ?: return@withContext emptyList()
                val arr  = JSONObject(json).optJSONArray("streams") ?: return@withContext emptyList()

                buildList {
                    for (i in 0 until arr.length()) {
                        val s       = arr.getJSONObject(i)
                        val link    = s.optString("url").takeIf { it.isNotEmpty() } ?: continue
                        val name    = s.optString("name", "WebStreamer")
                        val quality = Regex("""(\d{3,4})p""").find(name)?.groupValues?.get(1)
                            ?.let { "${it}p" } ?: "HD"
                        add(StreamResult(
                            url     = link,
                            quality = quality,
                            type    = StreamType.HLS,
                            source  = "WebStreamer",
                            label   = "$quality — WebStreamer [$name]"
                        ))
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Webstreamr error: ${e.message}")
                emptyList()
            }
        }

    // ── Rive — multi-server HLS, TMDB-based ──────────────────────────────────
    private suspend fun fetchRive(req: ProviderRequest): List<StreamResult> = coroutineScope {
        val tmdbId = req.tmdbId?.toString() ?: return@coroutineScope emptyList()
        try {
            val riveBase = ModflixConfig.get("rive")
            val secretKey = RiveKeyGen.generate(tmdbId)
            val route = if (req.isSeries) {
                "/api/backendfetch?requestID=tvVideoProvider&id=$tmdbId" +
                "&season=${req.season}&episode=${req.episode}&secretKey=$secretKey&service="
            } else {
                "/api/backendfetch?requestID=movieVideoProvider&id=$tmdbId&secretKey=$secretKey&service="
            }

            Log.d(TAG, "Rive base: $riveBase$route<server>")

            RIVE_SERVERS.map { server ->
                async(Dispatchers.IO) {
                    try {
                        val url  = "$riveBase$route$server"
                        val json = HttpClient.getJson(url,
                            mapOf("Referer" to riveBase, "Origin" to riveBase)
                        ) ?: return@async emptyList()

                        // TS: res.data?.data?.sources — root → data → sources
                    val root = JSONObject(json)
                    val data = root.optJSONObject("data") ?: return@async emptyList()
                    val sources = data.optJSONArray("sources")
                        ?: root.optJSONArray("sources") ?: return@async emptyList()

                        buildList {
                            for (i in 0 until sources.length()) {
                                val s      = sources.getJSONObject(i)
                                val link   = s.optString("url").takeIf { it.isNotEmpty() } ?: continue
                                val type   = if (s.optString("format") == "hls") StreamType.HLS else StreamType.MP4
                                val qual   = s.optString("quality", "HD")
                                val srcTag = s.optString("source", server)
                                add(StreamResult(
                                    url     = link,
                                    quality = qual,
                                    type    = type,
                                    source  = "Rive ($server)",
                                    label   = "$qual — Rive [$srcTag]",
                                    headers = mapOf("Referer" to riveBase)
                                ))
                            }
                        }
                    } catch (e: Exception) { emptyList() }
                }
            }.awaitAll().flatten()
        } catch (e: Exception) {
            Log.w(TAG, "Rive error: ${e.message}")
            emptyList()
        }
    }

    // ── JS encodeURI() equivalent ────────────────────────────────────────────
    // encodeURI safe chars: A-Za-z0-9 ; , / ? : @ & = + $ - _ . ! ~ * ' ( ) #
    // URLEncoder.encode() additionally encodes : and , which encodeURI DOES NOT
    private fun encodeUri(s: String): String {
        val safe = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789;,/?:@&=+$-_.!~*'()#"
        return buildString {
            for (byte in s.toByteArray(Charsets.UTF_8)) {
                val b = byte.toInt() and 0xFF
                if (b < 128 && safe.contains(b.toChar())) append(b.toChar())
                else append("%${b.toString(16).padStart(2, '0').uppercase()}")
            }
        }
    }
}
