package com.aeoncorex.streamx.ui.movie

import android.content.Context
import android.util.Log
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import java.io.File

sealed class StreamState {
    data class Preparing(val message: String) : StreamState()
    data class Buffering(val progress: Int, val speed: Long, val seeds: Int, val peers: Int) : StreamState()
    data class Ready(val filePath: String) : StreamState()
    data class Error(val message: String) : StreamState()
}

object TorrentEngine {
    private const val TAG = "StreamX_Native"

    @Volatile private var engineStopped = false

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
        val rootDir = context.externalCacheDir ?: context.cacheDir
        val downloadDir = File(rootDir, "StreamX_Video")
        if (!downloadDir.exists()) downloadDir.mkdirs()

        Log.d(TAG, "Starting for: $magnetLink")

        try {
            engineStopped = false
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
                        2 -> emit(StreamState.Buffering(progress, speedKB, seeds, peers))
                        3 -> {
                            val path = getFilePathNative()
                            if (path.isNotEmpty()) {
                                emit(StreamState.Ready(path))
                                return@flow
                            }
                            emit(StreamState.Buffering(progress, speedKB, seeds, peers))
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

    // Public accessor so TorrentStreamRepository can read the path
    // during early buffering (before Ready state)
    fun getFilePath(): String? = try {
        val path = getFilePathNative()
        if (path.isNotEmpty()) path else null
    } catch (_: Exception) { null }

    fun stop() {
        engineStopped = true
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
