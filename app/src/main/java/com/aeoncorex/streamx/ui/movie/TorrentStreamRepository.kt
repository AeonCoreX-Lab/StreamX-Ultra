package com.aeoncorex.streamx.ui.movie

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.torrentstream.TorrentOptions
import com.torrentstream.TorrentStream
import com.torrentstream.TorrentStreamNotInitializedException
import com.torrentstream.listener.TorrentListener
import com.torrentstream.model.TorrentModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// ═══════════════════════════════════════════════════════════════════
//  TorrentStreamRepository
//  ─────────────────────────
//  Handles the full 1337x torrent → stream URL flow:
//
//  1. search()   → calls /api/search-1337x  → returns TorrentResult list
//  2. getMagnet()→ calls /api/get-magnet    → returns magnet link
//  3. getStreamUrl() → TorrentStream-Android buffers magnet
//                   → returns localhost HTTP URL for ExoPlayer
//
//  Covers: Hollywood, Bollywood, Hindi dubs, Anime, K-Drama,
//          C-Drama, Documentaries, TV Series
// ═══════════════════════════════════════════════════════════════════
object TorrentStreamRepository {

    private const val TAG = "TorrentStreamRepo"

    private val SEARCH_URL  get() = "${com.aeoncorex.streamx.BuildConfig.BACKEND_BASE_URL}/api/search-1337x"
    private val MAGNET_URL  get() = "${com.aeoncorex.streamx.BuildConfig.BACKEND_BASE_URL}/api/get-magnet"

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

    // ── Search 1337x ──────────────────────────────────────────────
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
            val resp = post(SEARCH_URL, body, idToken)
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
            results

        } catch (e: Exception) {
            Log.e(TAG, "search failed: ${e.message}")
            emptyList()
        }
    }

    // ── Get magnet link for a torrent result ──────────────────────
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

    // ── Start torrent streaming → return localhost HTTP URL ───────
    // TorrentStream-Android buffers the most-needed pieces first
    // and serves them via a local HTTP server ExoPlayer can play
    suspend fun getStreamUrl(
        context:     Context,
        magnet:      String,
        result:      TorrentResult,
        onProgress:  (Int) -> Unit = {},   // 0-100 buffer %
    ): String = withContext(Dispatchers.IO) {

        suspendCancellableCoroutine { cont ->
            try {
                val options = TorrentOptions.Builder()
                    .saveLocation(context.cacheDir)
                    .removeFilesAfterStop(true)
                    .build()

                TorrentStream.init(options)

                TorrentStream.getInstance().addListener(object : TorrentListener {
                    override fun onStreamReady(torrent: TorrentModel) {
                        val streamUrl = torrent.videoFile?.absolutePath
                            ?: "http://127.0.0.1:${TorrentStream.getInstance().port}/${torrent.videoFile?.name}"
                        Log.d(TAG, "Stream ready: $streamUrl")
                        if (cont.isActive) cont.resume(streamUrl)
                    }

                    override fun onStreamProgress(
                        torrent:  TorrentModel,
                        status:   com.torrentstream.model.StreamStatus,
                    ) {
                        onProgress(status.bufferProgress)
                        Log.v(TAG, "Buffer: ${status.bufferProgress}% seeds:${status.seeds}")
                    }

                    override fun onStreamPrepared(torrent: TorrentModel) {
                        Log.d(TAG, "Prepared: ${torrent.videoFile?.name}")
                    }

                    override fun onStreamStarted(torrent: TorrentModel) {
                        Log.d(TAG, "Started streaming: ${torrent.videoFile?.name}")
                    }

                    override fun onStreamStopped() {
                        Log.d(TAG, "Stream stopped")
                    }

                    override fun onStreamError(torrent: TorrentModel?, e: Exception) {
                        Log.e(TAG, "Stream error: ${e.message}")
                        if (cont.isActive) cont.resumeWithException(e)
                    }
                })

                TorrentStream.getInstance().startStream(magnet)

                cont.invokeOnCancellation {
                    try { TorrentStream.getInstance().stopStream() } catch (_: Exception) {}
                }

            } catch (e: TorrentStreamNotInitializedException) {
                Log.e(TAG, "TorrentStream not initialized: ${e.message}")
                cont.resumeWithException(e)
            } catch (e: Exception) {
                Log.e(TAG, "getStreamUrl error: ${e.message}")
                cont.resumeWithException(e)
            }
        }
    }

    fun stopStream() {
        try { TorrentStream.getInstance().stopStream() } catch (_: Exception) {}
    }

    // ── HTTP POST helper ──────────────────────────────────────────
    private fun post(url: String, body: String, token: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type",  "application/json")
            setRequestProperty("Authorization", "Bearer $token")
            doOutput       = true
            connectTimeout = 15_000
            readTimeout    = 20_000
            outputStream.use { it.write(body.toByteArray()) }
        }
        val code = conn.responseCode
        val resp = try {
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
        }
        if (code != 200) throw Exception("HTTP $code: ${resp.take(100)}")
        return resp
    }

    private suspend fun getToken(): String? = try {
        FirebaseAuth.getInstance().currentUser
            ?.getIdToken(false)?.await()?.token
    } catch (_: Exception) { null }
}
