package com.aeoncorex.streamx.ui.movie

import android.content.Context
import android.util.Log
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import java.io.File

// ============================================================
//  StreamState — must be defined here so both TorrentEngine
//  and MoviePlayerScreen can see it.
// ============================================================
sealed class StreamState {
    data class Preparing(val message: String) : StreamState()
    data class Buffering(val progress: Int, val speed: Long, val seeds: Int, val peers: Int) : StreamState()
    data class Ready(val filePath: String) : StreamState()
    data class Error(val message: String) : StreamState()
}

object TorrentEngine {
    private const val TAG = "StreamX_Native"

    // ── FIX: volatile flag so the flow loop sees stop() immediately ──
    // When stop() is called from INSIDE collect{} (e.g. metadata timeout),
    // the coroutine context is still active so isActive stays true.
    // Without this flag the flow loops forever on null statuses and
    // collect{} never returns → retry loop is unreachable.
    @Volatile private var engineStopped = false

    // --- NATIVE FUNCTIONS ---
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

    // ----------------------------------------------------------------
    //  start() — returns a cold Flow that:
    //    • emits Preparing / Buffering while waiting
    //    • emits Ready(path) ONCE and then TERMINATES  ← KEY FIX
    //    • emits Error and then TERMINATES             ← KEY FIX
    //
    //  BUG THAT WAS HERE:
    //    The old code ran `while(currentCoroutineContext().isActive)` forever.
    //    After emitting Ready it set isPlaying=true and kept looping silently.
    //    `collect {}` in MoviePlayerScreen therefore NEVER returned, so
    //    `if (completed) break` was unreachable → UI stuck at metadata forever.
    // ----------------------------------------------------------------
    fun start(context: Context, magnetLink: String): Flow<StreamState> = flow {
        val rootDir = context.externalCacheDir ?: context.cacheDir
        val downloadDir = File(rootDir, "StreamX_Video")
        if (!downloadDir.exists()) downloadDir.mkdirs()

        Log.d(TAG, "Starting Native Engine for: $magnetLink")

        try {
            engineStopped = false
            startNative(magnetLink, downloadDir.absolutePath)
            emit(StreamState.Preparing("Initializing Core Engine…"))

            // ── FIX: also check engineStopped so stop() from inside
            // collect{} (e.g. metadata timeout) terminates the flow.
            while (currentCoroutineContext().isActive && !engineStopped) {
                val status = getStatusNative()

                if (status != null && status.size >= 5) {
                    val progress = status[0].toInt()
                    val speedKB  = status[1] / 1024          // bytes → KB/s
                    val seeds    = status[2].toInt()
                    val peers    = status[3].toInt()
                    val state    = status[4].toInt()

                    Log.d(TAG, "State=$state  Progress=$progress%  Speed=$speedKB KB/s  Seeds=$seeds  Peers=$peers")

                    when (state) {
                        0 -> emit(StreamState.Preparing("Connecting to DHT network…"))

                        1 -> emit(StreamState.Preparing("Fetching Torrent Metadata… ($peers peers)"))

                        2 -> emit(StreamState.Buffering(progress, speedKB, seeds, peers))

                        3 -> {
                            // ✅ FIX: `return@flow` terminates the flow so collect{} returns.
                            val path = getFilePathNative()
                            if (path.isNotEmpty()) {
                                emit(StreamState.Ready(path))
                                return@flow   // ← FLOW ENDS HERE — collect{} will now return
                            }
                            // path not ready yet — stay in state 2 briefly
                            emit(StreamState.Buffering(progress, speedKB, seeds, peers))
                        }

                        4 -> {
                            // ✅ FIX: also terminate on error so collect{} returns
                            emit(StreamState.Error("Native engine reported an error"))
                            return@flow
                        }
                    }
                } else {
                    // Native engine not yet ready — keep waiting
                    Log.v(TAG, "getStatusNative() returned null, waiting…")
                }

                delay(250)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Engine Flow Error: ${e.message}")
            emit(StreamState.Error("Stream failed: ${e.message}"))
        }
        // flow{} block ends here → collect{} returns automatically
    }

    fun stop() {
        Log.d(TAG, "Stopping Native Engine")
        // ── FIX: set flag BEFORE stopNative() so the flow loop exits
        // on next iteration even while collect{} is still suspended
        engineStopped = true
        try { stopNative() } catch (e: Exception) { Log.e(TAG, "Error stopping engine: ${e.message}") }
    }

    fun clearCache(context: Context) {
        try {
            val rootDir = context.externalCacheDir ?: context.cacheDir
            File(rootDir, "StreamX_Video").also { if (it.exists()) it.deleteRecursively() }
        } catch (e: Exception) {
            Log.e(TAG, "Cache clear failed: ${e.message}")
        }
    }
}
