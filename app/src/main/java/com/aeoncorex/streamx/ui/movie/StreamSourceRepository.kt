package com.aeoncorex.streamx.ui.movie

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// ═══════════════════════════════════════════════════════════════════
//  StreamSourceRepository
//  ───────────────────────
//  Priority:
//    1. Backend server (/api/extract-stream) — always tried first
//    2. In-app MovieSourceScraper           — auto fallback if server
//       fails, is slow, or returns empty results
//
//  The caller (ExoSourceSelectionScreen) never needs to care which
//  path was used — it always gets a List<StreamResult>.
//  Check `lastUsedFallback` to show a UI badge if needed.
// ═══════════════════════════════════════════════════════════════════
object StreamSourceRepository {

    private const val TAG = "StreamSourceRepo"

    private val BACKEND_URL get() =
        "${com.aeoncorex.streamx.BuildConfig.BACKEND_BASE_URL}/api/extract-stream"

    // True when the last call used in-app fallback scraping
    var lastUsedFallback: Boolean = false
        private set

    // ── Public data class ─────────────────────────────────────────
    data class StreamResult(
        val url:       String,
        val type:      String,   // "HLS" | "MP4" | "MKV" | "DASH"
        val quality:   String,
        val source:    String,
        val label:     String,
        val language:  String = "English",
        val subtitles: List<SubtitleItem> = emptyList(),
        val headers:   Map<String, String> = emptyMap()
    )

    data class SubtitleItem(
        val url:      String,
        val title:    String,
        val language: String,   // ISO-639-1 e.g. "en", "hi", "ja"
        val mimeType: String = "text/vtt"
    )

    // ── Main entry point ──────────────────────────────────────────
    suspend fun getSources(
        tmdbId:   Int?,
        imdbId:   String?,
        title:    String,
        type:     MovieType,
        season:   Int    = 0,
        episode:  Int    = 0,
        language: String = "English"
    ): List<StreamResult> = withContext(Dispatchers.IO) {

        lastUsedFallback = false

        // ── Step 1: Backend server ────────────────────────────────
        val serverResults = tryBackend(tmdbId, imdbId, title, type, season, episode, language)
        if (serverResults.isNotEmpty()) {
            Log.d(TAG, "✅ Backend: ${serverResults.size} streams for \"$title\"")
            return@withContext serverResults
        }

        // ── Step 2: New provider engine (no server needed) ───────────
        Log.w(TAG, "⚡ Using StreamProviderEngine for \"$title\"")
        lastUsedFallback = true

        val engineResults = com.aeoncorex.streamx.streaming.StreamProviderEngine.fetch(
            com.aeoncorex.streamx.streaming.ProviderRequest(
                tmdbId   = tmdbId,
                imdbId   = imdbId,
                title    = title,
                isSeries = type == MovieType.SERIES,
                season   = season,
                episode  = episode,
                language = language
            )
        )

        Log.d(TAG, "⚡ ProviderEngine: ${engineResults.size} streams for \"$title\"")

        // Convert engine StreamResult → repository StreamResult
        if (engineResults.isNotEmpty()) {
            return@withContext engineResults.map { s ->
                StreamResult(
                    url       = s.url,
                    type      = s.type.name,
                    quality   = s.quality,
                    source    = s.source,
                    label     = s.label.ifEmpty { "${s.quality} · ${s.source}" },
                    language  = s.language,
                    subtitles = s.subtitles.map { sub ->
                        SubtitleItem(
                            url      = sub.url,
                            title    = sub.title,
                            language = sub.language,
                            mimeType = sub.mimeType
                        )
                    },
                    headers   = s.headers
                )
            }
        }

        // ── Step 3: Legacy in-app scraper as last resort ──────────────
        Log.w(TAG, "⚠️ ProviderEngine empty — legacy scraper for \"$title\"")
        val scraperResults = MovieSourceScraper.getSources(
            tmdbId   = tmdbId,
            imdbId   = imdbId,
            title    = title,
            type     = type,
            season   = season,
            episode  = episode,
            language = language
        )

        Log.d(TAG, "📱 Legacy scraper: ${scraperResults.size} streams for \"$title\"")

        // Convert MovieSourceScraper.StreamSource → StreamResult
        scraperResults.map { s ->
            StreamResult(
                url      = s.url,
                type     = s.type.name,   // HLS, MP4, DASH
                quality  = s.quality,
                source   = s.sourceSite,
                label    = s.label.ifEmpty { "${s.quality} · ${s.sourceSite}" },
                language = s.language
            )
        }
    }

    // ── Backend call (isolated so exceptions don't crash fallback) ─
    private suspend fun tryBackend(
        tmdbId: Int?, imdbId: String?, title: String,
        type: MovieType, season: Int, episode: Int, language: String
    ): List<StreamResult> {

        // Firebase token — skip backend if user is not signed in
        val idToken = try {
            FirebaseAuth.getInstance().currentUser
                ?.getIdToken(false)?.await()?.token
        } catch (_: Exception) { null }

        if (idToken == null) {
            Log.w(TAG, "No Firebase token — skipping backend")
            return emptyList()
        }

        val body = JSONObject().apply {
            put("tmdbId",   tmdbId ?: JSONObject.NULL)
            put("imdbId",   if (imdbId.isNullOrEmpty() || imdbId == "null") JSONObject.NULL else imdbId)
            put("title",    title)
            put("type",     if (type == MovieType.MOVIE) "MOVIE" else "SERIES")
            put("season",   season)
            put("episode",  episode)
            put("language", language)
        }.toString()

        return try {
            val conn = (URL(BACKEND_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type",  "application/json")
                setRequestProperty("Authorization", "Bearer $idToken")
                doOutput       = true
                connectTimeout = 12_000   // shorter timeout → fallback kicks in faster
                readTimeout    = 20_000
                outputStream.use { it.write(body.toByteArray()) }
            }

            val code = conn.responseCode
            val resp = try {
                conn.inputStream.bufferedReader().use { it.readText() }
            } catch (_: Exception) {
                conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            }

            if (code != 200) {
                Log.w(TAG, "Backend HTTP $code: ${resp.take(120)}")
                return emptyList()
            }

            val json    = JSONObject(resp)
            val streams = json.optJSONArray("streams") ?: return emptyList()
            val results = mutableListOf<StreamResult>()

            for (i in 0 until streams.length()) {
                val s = streams.getJSONObject(i)
                results.add(StreamResult(
                    url      = s.optString("url"),
                    type     = s.optString("type", "HLS"),
                    quality  = s.optString("quality", "Auto"),
                    source   = s.optString("source", ""),
                    label    = s.optString("label", ""),
                    language = language
                ))
            }
            results

        } catch (e: Exception) {
            Log.e(TAG, "Backend error: ${e.javaClass.simpleName} — ${e.message}")
            emptyList()
        }
    }
}
