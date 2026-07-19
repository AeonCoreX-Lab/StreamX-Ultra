package com.aeoncorex.streamx.streaming

import android.util.Log
import com.aeoncorex.streamx.network.FirebaseTokenProvider
import com.aeoncorex.streamx.network.StreamResolverConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

// ═════════════════════════════════════════════════════════════════════════════
//  StreamResolverClient
//  ─────────────────────────────────────────────────────────────────────────
//  Talks to the streamx-stream-resolver Cloudflare Worker's POST /resolve.
//
//  Changed from the previous version: resolve() now returns ResolveResult
//  instead of List<StreamResult> directly, so the wafBlockedDomain field
//  from the Worker's response can reach WorkerStreamProviderEngine's WAF
//  retry logic without changing the public List<StreamResult> contract that
//  ExoSourceSelectionScreen and StreamProviderEngine see (those still see
//  only the final merged List<StreamResult> from the engine — this wrapper
//  type is engine-internal only).
// ═════════════════════════════════════════════════════════════════════════════
object StreamResolverClient {

    private const val TAG = "StreamResolverClient"

    /**
     * Engine-internal wrapper so the Worker's wafBlockedDomain signal can
     * travel from the HTTP layer up to WorkerStreamProviderEngine's retry
     * logic without leaking into the public StreamProviderEngine API or
     * requiring ExoSourceSelectionScreen to change.
     */
    data class ResolveResult(
        val streams: List<StreamResult>,
        // Non-null when the Worker's response included a wafBlockedDomain
        // field — meaning the provider got a 403/WAF challenge on that
        // domain and returned 0 streams as a result. The engine uses this
        // to decide whether to trigger an on-device WebView solve + retry.
        val wafBlockedDomain: String?
    )

    /**
     * Resolves one provider via the Worker.
     * Never throws — returns ResolveResult(emptyList(), null) on any failure
     * so the safe() wrapper in WorkerStreamProviderEngine still works.
     */
    suspend fun resolve(
        provider: String,
        title:    String,
        type:     String,
        tmdbId:   Int?    = null,
        imdbId:   String? = null,
        season:   Int?    = null,
        episode:  Int?    = null
    ): ResolveResult = withContext(Dispatchers.IO) {
        try {
            val token = FirebaseTokenProvider.getIdToken()
            if (token == null) {
                Log.w(TAG, "$provider: no Firebase ID token — user not signed in, skipping resolve")
                return@withContext empty()
            }

            val baseUrl = StreamResolverConfig.getStreamWorkerBaseUrl()
            val body = buildRequestBody(provider, title, tmdbId, imdbId, type, season, episode)

            val request = Request.Builder()
                .url("${baseUrl}resolve")
                .header("Authorization", "Bearer $token")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            HttpClient.okhttp.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()

                if (response.code == 401) {
                    Log.d(TAG, "$provider: 401, retrying with forceRefresh")
                    return@withContext retryWithFreshToken(
                        provider, title, tmdbId, imdbId, type, season, episode, baseUrl
                    )
                }

                if (response.code == 429) {
                    Log.w(TAG, "$provider: rate limited by Worker")
                    return@withContext empty()
                }

                if (!response.isSuccessful || responseBody == null) {
                    Log.w(TAG, "$provider: HTTP ${response.code} — ${responseBody?.take(200)}")
                    return@withContext empty()
                }

                parseResolveResponse(responseBody, provider)
            }
        } catch (e: Exception) {
            Log.w(TAG, "$provider: resolve failed: ${e.message}")
            empty()
        }
    }

    private fun empty() = ResolveResult(emptyList(), null)

    private fun buildRequestBody(
        provider: String, title: String, tmdbId: Int?, imdbId: String?,
        type: String, season: Int?, episode: Int?
    ): String = JSONObject().apply {
        put("provider", provider)
        put("title", title)
        tmdbId?.let { put("tmdbId", it) }
        imdbId?.takeIf { it.isNotBlank() }?.let { put("imdbId", it) }
        put("type", type)
        if (type == "series") {
            put("season", season ?: 0)
            put("episode", episode ?: 0)
        }
    }.toString()

    private suspend fun retryWithFreshToken(
        provider: String, title: String, tmdbId: Int?, imdbId: String?,
        type: String, season: Int?, episode: Int?, baseUrl: String
    ): ResolveResult {
        val freshToken = FirebaseTokenProvider.getIdToken(forceRefresh = true)
            ?: return empty()
        val body = buildRequestBody(provider, title, tmdbId, imdbId, type, season, episode)

        val request = Request.Builder()
            .url("${baseUrl}resolve")
            .header("Authorization", "Bearer $freshToken")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            HttpClient.okhttp.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()
                if (!response.isSuccessful || responseBody == null) {
                    Log.w(TAG, "$provider: retry HTTP ${response.code}")
                    empty()
                } else {
                    parseResolveResponse(responseBody, provider)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "$provider: retry failed: ${e.message}")
            empty()
        }
    }

    // ── Response parsing ────────────────────────────────────────────────────
    //
    // Worker response shape (see streamx-stream-resolver/src/index.js):
    // {
    //   "provider": "autoEmbed",
    //   "streams": [ { "server":..., "quality":..., "type":..., "playUrl":..., ... } ],
    //   "wafBlockedDomain": "mostraguarda.stream"   ← NEW: null when no WAF block
    // }

    private fun parseResolveResponse(json: String, provider: String): ResolveResult {
        return try {
            val root = JSONObject(json)

            // wafBlockedDomain is null/absent when the provider ran cleanly —
            // either it returned streams or it returned [] for normal reasons
            // (site down, no search match, dead domain). Only non-null when
            // the Worker specifically detected a WAF/bot-challenge response
            // (403/503 with WAF-fingerprint body) — see wafDetect.js on the
            // Worker side.
            val wafDomain = root.optString("wafBlockedDomain", "")
                .takeIf { it.isNotBlank() }

            val arr = root.optJSONArray("streams") ?: JSONArray()
            val streams = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val playUrl = o.optString("playUrl", "")
                if (playUrl.isBlank()) return@mapNotNull null

                StreamResult(
                    url       = playUrl,
                    quality   = o.optString("quality", "Unknown"),
                    type      = mapStreamType(o.optString("type", "mp4")),
                    source    = "$provider (${o.optString("server", provider)})",
                    language  = mapLanguageLabel(o.optString("language", "en")),
                    label     = o.optString("server", provider),
                    subtitles = parseSubtitles(o.optJSONArray("subtitles")),
                    headers   = emptyMap()
                )
            }.also { Log.d(TAG, "$provider → ${it.size} streams via resolver" +
                if (wafDomain != null) " [waf-blocked: $wafDomain]" else "") }

            ResolveResult(streams, wafDomain)
        } catch (e: Exception) {
            Log.w(TAG, "$provider: failed to parse resolve response: ${e.message}")
            empty()
        }
    }

    private fun parseSubtitles(arr: JSONArray?): List<SubtitleTrack> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val uri = o.optString("uri", o.optString("url", ""))
            if (uri.isBlank()) return@mapNotNull null
            SubtitleTrack(
                url      = uri,
                language = o.optString("language", o.optString("lang", "Und")),
                title    = o.optString("title", o.optString("language", "Subtitle")),
                mimeType = if (uri.endsWith(".vtt")) "text/vtt" else "application/x-subrip"
            )
        }
    }

    private fun mapStreamType(raw: String): StreamType = when (raw.lowercase()) {
        "m3u8", "hls" -> StreamType.HLS
        "dash", "mpd" -> StreamType.DASH
        "mkv"         -> StreamType.MKV
        else          -> StreamType.MP4
    }

    private fun mapLanguageLabel(code: String): String = when (code.lowercase()) {
        "hi"  -> "Hindi"
        "bn"  -> "Bengali"
        "ta"  -> "Tamil"
        "te"  -> "Telugu"
        "dub" -> "Dub"
        "sub" -> "Sub"
        else  -> "English"
    }
}
