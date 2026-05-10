package com.aeoncorex.streamx.ui.movie

import android.content.Context
import android.util.Log
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import java.io.File

// ═══════════════════════════════════════════════════════════════════
//  StreamState — Torrent download/stream progress
// ═══════════════════════════════════════════════════════════════════
sealed class StreamState {
    data class Preparing(val message: String)                              : StreamState()
    data class Buffering(val progress: Int, val speed: Long, val seeds: Int, val peers: Int) : StreamState()
    /** [streamUrl] = http://127.0.0.1:8088/stream (MPV uses this directly) */
    data class Ready(val streamUrl: String)                               : StreamState()
    data class Error(val message: String)                                 : StreamState()
}

// ═══════════════════════════════════════════════════════════════════
//  TorrentEngine — JNI bridge to native libtorrent
//  ──────────────────────────────────────────────────────────────────
//  C++ state machine (torrent-engine.cpp → updateLoop):
//
//    state 0 → Preparing  (no metadata yet, connecting DHT)
//    state 1 → Preparing  (fetching torrent metadata from peers)
//    state 2 → Buffering  (downloading; headerOk=false OR progress<MIN)
//    state 3 → READY      (headerOk=true AND progress≥MIN_PROGRESS=3%)
//                          C++ has already verified both conditions.
//    state 4 → Error
//
//  BUG FIXED (previous version):
//    The old Kotlin code treated state 2 and state 3 identically,
//    then re-checked `progress >= MIN_BUFFER_PCT` itself.
//    This had two problems:
//      (a) state 2 includes the case where headerOk=false — emitting
//          Ready when headerOk=false caused MPV to get an unplayable
//          stream (no container header → "Unknown file format").
//      (b) The Kotlin MIN_BUFFER_PCT (3%) was applied without
//          knowing if headerOk was satisfied, bypassing C++'s gate.
//
//  FIX:
//    • state 2 → always Buffering. C++ says not ready, trust it.
//    • state 3 → start Ktor server and emit Ready immediately.
//                C++ has already validated headerOk AND progressOk.
//    • Kotlin-side MIN_BUFFER_PCT check completely removed.
// ═══════════════════════════════════════════════════════════════════

object TorrentEngine {
    private const val TAG = "StreamX_Native"

    @Volatile private var engineStopped = false
    @Volatile private var serverStarted = false

    private external fun initNative()
    private external fun startNative(magnet: String, savePath: String)
    private external fun stopNative()
    private external fun getStatusNative(): LongArray?
    private external fun getFilePathNative(): String

    init {
        try {
            System.loadLibrary("streamx-native")
            initNative()
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Native Library Load Failed: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Engine Init Failed: ${e.message}")
        }
    }

    fun start(context: Context, magnetLink: String): Flow<StreamState> = flow {
        val rootDir     = context.externalCacheDir ?: context.cacheDir
        val downloadDir = File(rootDir, "StreamX_Video")
        if (!downloadDir.exists()) downloadDir.mkdirs()

        Log.d(TAG, "Starting for: $magnetLink")

        try {
            engineStopped = false
            serverStarted = false
            startNative(magnetLink, downloadDir.absolutePath)
            emit(StreamState.Preparing("Initializing…"))

            while (currentCoroutineContext().isActive && !engineStopped) {
                val status = getStatusNative()

                if (status != null && status.size >= 5) {
                    val progress = status[0].toInt()
                    val speedKB  = status[1] / 1024
                    val seeds    = status[2].toInt()
                    val peers    = status[3].toInt()
                    val state    = status[4].toInt()

                    Log.d(TAG, "State=$state  Progress=$progress%  Speed=${speedKB}KB/s  Seeds=$seeds")

                    when (state) {
                        0 -> emit(StreamState.Preparing("Connecting to DHT…"))
                        1 -> emit(StreamState.Preparing("Fetching Metadata… ($peers peers)"))

                        2 -> {
                            // C++ says: headerOk=false OR progress<MIN_PROGRESS.
                            // Keep updating the Ktor server's file reference so it
                            // serves new bytes as the torrent downloads.
                            val path = getFilePathNative()
                            if (path.isNotEmpty()) TorrentStreamServer.updateFile(File(path))
                            emit(StreamState.Buffering(progress, speedKB, seeds, peers))
                        }

                        3 -> {
                            // C++ says: headerOk=true AND progress≥MIN_PROGRESS (3%).
                            // This is the authoritative ready signal — start streaming.
                            if (!serverStarted) {
                                val path = getFilePathNative()
                                if (path.isNotEmpty()) {
                                    val httpUrl = TorrentStreamServer.start(File(path))
                                    serverStarted = true
                                    Log.d(TAG, "HTTP streaming ready at $httpUrl (${progress}% buffered)")
                                    emit(StreamState.Ready(httpUrl))
                                    return@flow
                                }
                                // path not populated yet (rare race) → stay in Buffering
                                emit(StreamState.Buffering(progress, speedKB, seeds, peers))
                            }
                        }

                        4 -> {
                            emit(StreamState.Error("Engine error"))
                            return@flow
                        }
                    }
                }
                delay(250)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Flow error: ${e.message}")
            emit(StreamState.Error("Stream failed: ${e.message}"))
        }
    }

    /** Public accessor so TorrentStreamServer can read the path during early buffering */
    fun getFilePath(): String? = try {
        val path = getFilePathNative()
        if (path.isNotEmpty()) path else null
    } catch (_: Exception) { null }

    fun stop() {
        engineStopped = true
        serverStarted = false
        TorrentStreamServer.stop()
        try { stopNative() } catch (e: Exception) { Log.e(TAG, "Stop error: ${e.message}") }
    }

    fun clearCache(context: Context) {
        try {
            val rootDir = context.externalCacheDir ?: context.cacheDir
            File(rootDir, "StreamX_Video").also { if (it.exists()) it.deleteRecursively() }
        } catch (e: Exception) {
            Log.e(TAG, "Cache clear failed")
        }
    }
}
