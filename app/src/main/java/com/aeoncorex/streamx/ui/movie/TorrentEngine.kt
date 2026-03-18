package com.aeoncorex.streamx.ui.movie

import android.content.Context
import android.util.Log
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import java.io.File

object TorrentEngine {
    private const val TAG = "StreamX_Native"

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

    // --- MAIN LOGIC ---
    fun start(context: Context, magnetLink: String): Flow<StreamState> = flow {
        val rootDir = context.externalCacheDir ?: context.cacheDir
        val downloadDir = File(rootDir, "StreamX_Video")
        if (!downloadDir.exists()) downloadDir.mkdirs()

        Log.d(TAG, "Starting Native Engine for: $magnetLink")
        
        try {
            startNative(magnetLink, downloadDir.absolutePath)
            emit(StreamState.Preparing("Initializing Core Engine..."))

            var isPlaying = false
            
            // FIX: currentCoroutineContext().isActive must be used inside a flow builder
            while (currentCoroutineContext().isActive) {
                val status = getStatusNative()
                if (status != null && status.size >= 5) {
                    val progress = status[0].toInt()
                    val speedKB = status[1] / 1024
                    val seeds = status[2].toInt()
                    val peers = status[3].toInt()
                    val state = status[4].toInt()

                    Log.d(TAG, "Native Status -> State: $state, Progress: $progress%, Speed: $speedKB KB/s, Seeds: $seeds, Peers: $peers")

                    when (state) {
                        0 -> {
                            if (!isPlaying) emit(StreamState.Preparing("Connecting to DHT..."))
                        }
                        1 -> {
                            if (!isPlaying) emit(StreamState.Preparing("Fetching Metadata... ($peers peers)"))
                        }
                        2 -> {
                            // FIX: Only emit buffering if playback hasn't started yet
                            if (!isPlaying) {
                                emit(StreamState.Buffering(progress, speedKB, seeds, peers))
                            }
                        }
                        3 -> {
                            // Ready/Playing (crossed 1%)
                            if (!isPlaying) {
                                val path = getFilePathNative()
                                if (path.isNotEmpty()) {
                                    emit(StreamState.Ready(path))
                                    isPlaying = true // Prevent reverting to Buffering UI
                                }
                            }
                        }
                        4 -> emit(StreamState.Error("Engine Error occurred"))
                    }
                } else {
                    Log.w(TAG, "Native status unavailable")
                }
                
                // Reduced delay for faster UI syncing
                delay(250)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Engine Flow Error: ${e.message}")
            emit(StreamState.Error("Stream Failed: ${e.message}"))
        }
    }

    fun stop() {
        Log.d(TAG, "Stopping Native Engine")
        try {
            stopNative()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping engine: ${e.message}")
        }
    }

    fun clearCache(context: Context) {
        try {
            val rootDir = context.externalCacheDir ?: context.cacheDir
            val downloadDir = File(rootDir, "StreamX_Video")
            if (downloadDir.exists()) {
                downloadDir.deleteRecursively()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Cache clear failed")
        }
    }
}
