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
//  Talks to the streamx-stream-resolver Cloudflare Worker's POST /resolve —
//  replaces on-device execution of addon stream.js bundles
//  (StreamXNative.executeJsStream / QuickJS). The Worker runs the addon
//  code itself, caches results, and hands back short-lived SIGNED playback
//  URLs — see streamx-stream-resolver/README.md for the full contract.
//
//  Auth: same Firebase ID token flow as MovieRepository/CinemetaRepository
//  (FirebaseTokenProvider) — the Worker rejects anything without a valid
//  "Authorization: Bearer <token>" header, then rate-limits per uid.
//
//  One request per provider — the caller (WorkerStreamProviderEngine)
//  decides which providers to try and runs them in parallel.
// ═════════════════════════════════════════════════════════════════════════════
object StreamResolverClient {

    private const val TAG = "StreamResolverClient"

    /**
     * @param provider  one of the Worker's ENABLED_PROVIDERS (e.g. "autoEmbed",
     *                  "animetsu", "flixhq", "multi")
     * @param title     content title — used server-side for the
     *                  search→meta resolution chain on providers that need
     *                  it (animetsu/flixhq/multi); autoEmbed ignores it and
     *                  resolves directly from tmdbId/imdbId.
     * @param type      "movie" or "series"
     * @param tmdbId    TMDB id, used by autoEmbed
     * @param imdbId    IMDB id ("tt..."), used by autoEmbed
     * @param season    required when type == "series"
     * @param episode   required when type == "series"
     * @return          empty list on any failure — never throws, matching
     *                  the existing engine contract so the safe() wrapper
     *                  in WorkerStreamProviderEngine still works unchanged.
     */
    suspend fun resolve(
        provider: String,
        title:    String,
        type:     String,
        tmdbId:   Int?    = null,
        imdbId:   String? = null,
        season:   Int?    = null,
        episode:  Int?    = null
    ): List<StreamResult> = withContext(Dispatchers.IO) {
        try {
            val token = FirebaseTokenProvider.getIdToken()
            if (token == null) {
                Log.w(TAG, "$provider: no Firebase ID token — user not signed in, skipping resolve")
                return@withContext emptyList()
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
                    // Token expired mid-flight or was rejected — retry once
                    // with a forced refresh, same pattern as MovieRepository's
                    // authInterceptor. Not worth a generic retry wrapper for
                    // a single call site.
                    Log.d(TAG, "$provider: 401, retrying with forceRefresh")
                    return@withContext retryWithFreshToken(provider, title, tmdbId, imdbId, type, season, episode, baseUrl)
                }

                if (response.code == 429) {
                    Log.w(TAG, "$provider: rate limited by Worker")
                    return@withContext emptyList()
                }

                if (!response.isSuccessful || responseBody == null) {
                    Log.w(TAG, "$provider: HTTP ${response.code} — ${responseBody?.take(200)}")
                    return@withContext emptyList()
                }

                parseResolveResponse(responseBody, provider)
            }
        } catch (e: Exception) {
            Log.w(TAG, "$provider: resolve failed: ${e.message}")
            emptyList()
        }
    }

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
    ): List<StreamResult> {
        val freshToken = FirebaseTokenProvider.getIdToken(forceRefresh = true) ?: return emptyList()
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
                    emptyList()
                } else {
                    parseResolveResponse(responseBody, provider)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "$provider: retry failed: ${e.message}")
            emptyList()
        }
    }

    // ── Response parsing ────────────────────────────────────────────────────
    //
    // Worker response shape (see streamx-stream-resolver/src/index.js):
    // {
    //   "provider": "autoEmbed",
    //   "streams": [
    //     { "server": "hindicast-1080p", "quality": "1080p", "type": "m3u8",
    //       "language": "hi", "playUrl": "https://.../play?sig=...",
    //       "subtitles": [...] },
    //     ...
    //   ]
    // }

    private fun parseResolveResponse(json: String, provider: String): List<StreamResult> {
        return try {
            val root = JSONObject(json)
            val arr = root.optJSONArray("streams") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
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
                    // No headers here deliberately — the Worker already
                    // applied any Referer/Origin/Cookie the origin needs
                    // before handing back playUrl (see /play in the
                    // Worker's index.js). MPV just plays the URL as-is.
                    headers   = emptyMap()
                )
            }.also { Log.d(TAG, "$provider → ${it.size} streams via resolver") }
        } catch (e: Exception) {
            Log.w(TAG, "$provider: failed to parse resolve response: ${e.message}")
            emptyList()
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

    // Worker's guessLanguage() returns short codes (hi/bn/ta/te/dub/sub/en) —
    // shown as a badge/label in the UI rather than fed to anything that
    // needs a strict BCP-47 tag, so a friendly display string is fine here.
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
