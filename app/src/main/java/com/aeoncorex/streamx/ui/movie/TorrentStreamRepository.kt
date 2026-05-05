package com.aeoncorex.streamx.ui.movie

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// ═══════════════════════════════════════════════════════════════════
//  TorrentStreamRepository
//  ─────────────────────────
//  1. search()      → /api/search-1337x  → TorrentResult list
//  2. getMagnet()   → /api/get-magnet    → magnet link
//  3. getStreamUrl()→ native TorrentEngine (C++ libtorrent via JNI)
//                     buffers pieces → returns local file path
//
//  Uses native TorrentEngine.kt + streamx-native .so
//  NOT the deprecated TorrentStream-Android Java library.
// ═══════════════════════════════════════════════════════════════════
object TorrentStreamRepository {

    private const val TAG = "TorrentStreamRepo"
    private const val MIN_BUFFER_TO_PLAY = 3   // % before ExoPlayer opens file

    private val SEARCH_URL get() = "${com.aeoncorex.streamx.BuildConfig.BACKEND_BASE_URL}/api/search-1337x"
    private val MAGNET_URL get() = "${com.aeoncorex.streamx.BuildConfig.BACKEND_BASE_URL}/api/get-magnet"

    data class TorrentResult(
        val title:      String,
        val torrentUrl: String,
        val quality:    String,
        val language:   String,
        val seeders:    Int,
        val size:       String,
        val mirror:     String,
        var magnet:     String? = null,
    )

    // ── Search 1337x via backend ──────────────────────────────────
    suspend fun search(
        title:    String,
        type:     MovieType,
        season:   Int    = 0,
        episode:  Int    = 0,
        language: String = "English",
    ): List<TorrentResult> = withContext(Dispatchers.IO) {

        val idToken = getToken() ?: return@withContext emptyList()

        val body = JSONObject().apply {
            put("title",    title)
            put("type",     if (type == MovieType.MOVIE) "MOVIE" else "SERIES")
            put("season",   season)
            put("episode",  episode)
            put("language", language)
        }.toString()

        try {
            val resp     = post(SEARCH_URL, body, idToken)
            val json     = JSONObject(resp)
            val torrents = json.optJSONArray("torrents") ?: return@withContext emptyList()
            val results  = mutableListOf<TorrentResult>()

            for (i in 0 until torrents.length()) {
                val t = torrents.getJSONObject(i)
                results.add(TorrentResult(
                    title      = t.optString("title"),
                    torrentUrl = t.optString("torrentUrl"),
                    quality    = t.optString("quality", "HD"),
                    language   = t.optString("language", language),
                    seeders    = t.optInt("seeders", 0),
                    size       = t.optString("size", ""),
                    mirror     = t.optString("mirror", ""),
                ))
            }
            Log.d(TAG, "1337x: ${results.size} results for '$title'")
            results.sortedByDescending { it.seeders }
        } catch (e: Exception) {
            Log.e(TAG, "search failed: ${e.message}")
            emptyList()
        }
    }

    // ── Get magnet link ───────────────────────────────────────────
    suspend fun getMagnet(result: TorrentResult): String? = withContext(Dispatchers.IO) {
        if (!result.magnet.isNullOrBlank()) return@withContext result.magnet
        val idToken = getToken() ?: return@withContext null
        val body    = JSONObject().apply { put("torrentUrl", result.torrentUrl) }.toString()
        try {
            val resp   = post(MAGNET_URL, body, idToken)
            val magnet = JSONObject(resp).optString("magnet").ifBlank { null }
            result.magnet = magnet
            Log.d(TAG, "Got magnet for ${result.title}")
            magnet
        } catch (e: Exception) {
            Log.e(TAG, "getMagnet failed: ${e.message}")
            null
        }
    }

    // ── Stream via native TorrentEngine ──────────────────────────
    suspend fun getStreamUrl(
        context:        Context,
        magnet:         String,
        result:         TorrentResult,
        onProgress:     (Int) -> Unit                                  = {},
        onStatusUpdate: (speed: Long, seeds: Int, peers: Int) -> Unit  = { _, _, _ -> },
    ): String = withContext(Dispatchers.IO) {

        Log.d(TAG, "Native engine starting: ${result.title}")
        var readyPath: String? = null
        var errorMsg:  String? = null
        var lastPct    = 0

        TorrentEngine.start(context, magnet).collect { state ->
            when (state) {
                is StreamState.Preparing -> {
                    Log.d(TAG, "Preparing: ${state.message}")
                    onProgress(0)
                }
                is StreamState.Buffering -> {
                    lastPct = state.progress
                    onProgress(state.progress)
                    onStatusUpdate(state.speed, state.seeds, state.peers)
                    // Early play once MIN_BUFFER_TO_PLAY% is buffered
                    if (state.progress >= MIN_BUFFER_TO_PLAY) {
                        val path = TorrentEngine.getFilePath()
                        if (!path.isNullOrEmpty()) { readyPath = path; return@collect }
                    }
                }
                is StreamState.Ready -> {
                    readyPath = state.filePath
                    Log.d(TAG, "Ready: ${state.filePath}")
                    return@collect
                }
                is StreamState.Error -> {
                    errorMsg = state.message
                    Log.e(TAG, "Engine error: ${state.message}")
                    return@collect
                }
            }
        }

        if (!readyPath.isNullOrEmpty()) return@withContext readyPath!!
        throw Exception(errorMsg ?: "Torrent stream failed (buffer: $lastPct%)")
    }

    fun stopStream()              = TorrentEngine.stop()
    fun clearCache(ctx: Context)  = TorrentEngine.clearCache(ctx)

    private fun post(url: String, body: String, token: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type",  "application/json")
            setRequestProperty("Authorization", "Bearer $token")
            doOutput = true; connectTimeout = 15_000; readTimeout = 20_000
            outputStream.use { it.write(body.toByteArray()) }
        }
        val code = conn.responseCode
        val resp = try { conn.inputStream.bufferedReader().use { it.readText() } }
                   catch (_: Exception) { conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "" }
        if (code != 200) throw Exception("HTTP $code: ${resp.take(100)}")
        return resp
    }

    private suspend fun getToken(): String? = try {
        FirebaseAuth.getInstance().currentUser?.getIdToken(false)?.await()?.token
    } catch (_: Exception) { null }
}
