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
//  Calls Vercel /api/extract-stream for stream URLs.
//
//  WHY Vercel backend (not in-app scraping):
//    • Source URLs change → update server, no app update needed
//    • Server uses desktop UA → bypasses mobile blocks
//    • CORS not an issue server-side
//    • Can cache results → faster repeat plays
//    • Anti-bot bypass works better server-side
//
//  The app has ZERO hardcoded stream source URLs.
//  All source logic lives in /api/extract-stream.js on Vercel.
// ═══════════════════════════════════════════════════════════════════
object StreamSourceRepository {

    private const val TAG          = "StreamSourceRepo"
    private const val VERCEL_URL   = "https://YOUR_APP.vercel.app/api/extract-stream"

    data class StreamResult(
        val url:       String,
        val type:      String,   // "HLS" or "MP4"
        val quality:   String,
        val source:    String,
        val label:     String,
        val language:  String = "English"
    )

    // ── Fetch streams from Vercel backend ─────────────────────────
    suspend fun getSources(
        tmdbId:   Int?,
        imdbId:   String?,
        title:    String,
        type:     MovieType,
        season:   Int    = 0,
        episode:  Int    = 0,
        language: String = "English"
    ): List<StreamResult> = withContext(Dispatchers.IO) {

        val idToken = try {
            FirebaseAuth.getInstance().currentUser
                ?.getIdToken(false)?.await()?.token
        } catch (_: Exception) { null }

        if (idToken == null) {
            Log.w(TAG, "No Firebase token — user not signed in")
            return@withContext emptyList()
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

        try {
            val conn = (URL(VERCEL_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type",  "application/json")
                setRequestProperty("Authorization", "Bearer $idToken")
                doOutput        = true
                connectTimeout  = 15_000
                readTimeout     = 25_000
                outputStream.use { it.write(body.toByteArray()) }
            }

            val code = conn.responseCode
            val resp = try {
                conn.inputStream.bufferedReader().use { it.readText() }
            } catch (_: Exception) {
                conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            }

            if (code != 200) {
                Log.w(TAG, "Vercel HTTP $code: ${resp.take(200)}")
                return@withContext emptyList()
            }

            val json    = JSONObject(resp)
            val streams = json.optJSONArray("streams") ?: return@withContext emptyList()
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

            Log.d(TAG, "Got ${results.size} streams from Vercel for $title")
            results

        } catch (e: Exception) {
            Log.e(TAG, "Vercel fetch failed: ${e.message}")
            emptyList()
        }
    }
}
