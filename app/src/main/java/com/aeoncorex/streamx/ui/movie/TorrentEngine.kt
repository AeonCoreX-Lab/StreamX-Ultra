package com.aeoncorex.streamx.ui.movie

import android.content.Context
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

object TorrentEngine {
    private const val TAG = "StreamX_JNI"

    // --- NATIVE FUNCTIONS ---
    private external fun initNative()
    private external fun startNative(magnet: String, savePath: String)
    private external fun stopNative()
    private external fun getStatusNative(): LongArray? 
    private external fun getFilePathNative(): String

    // লাইব্রেরি লোড করা
    init {
        try {
            // FIXME: Ensure this matches the name in CMakeLists.txt -> "streamx-native"
            System.loadLibrary("streamx-native") 
            initNative()
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Native Library Load Failed: ${e.message}")
            // Fallback logic could go here if needed
        } catch (e: Exception) {
            Log.e(TAG, "Engine Init Failed: ${e.message}")
        }
    }

    // --- MAIN LOGIC ---
    fun start(context: Context, magnetLink: String): Flow<StreamState> = flow {
        // ১. ক্যাশ ফোল্ডার সেটআপ
        val rootDir = context.externalCacheDir ?: context.cacheDir
        val downloadDir = File(rootDir, "StreamX_Video")
        if (!downloadDir.exists()) downloadDir.mkdirs()

        Log.d(TAG, "Starting Native Engine for: $magnetLink")
        
        try {
            startNative(magnetLink, downloadDir.absolutePath)
            emit(StreamState.Preparing("Initializing Core Engine..."))
        } catch (e: UnsatisfiedLinkError) {
            emit(StreamState.Error("Core Engine Missing! Check APK split."))
            return@flow
        }

        // ২. মনিটরিং লুপ
        var isPlaying = false
        
        while (true) {
            val status = getStatusNative()
            
            if (status != null && status.size == 5) {
                val progress = status[0].toInt()
                val speed = status[1]
                val seeds = status[2].toInt()
                val peers = status[3].toInt()
                val state = status[4].toInt()

                val speedKB = speed / 1024

                when (state) {
                    0 -> emit(StreamState.Preparing("Idle"))
                    1 -> emit(StreamState.Preparing("Metadata: S:$seeds"))
                    2, 3 -> {
                        // 2 = Downloading, 3 = Ready/Playing
                        if (state == 3 && !isPlaying) {
                            val path = getFilePathNative()
                            if (path.isNotEmpty()) {
                                emit(StreamState.Ready(path))
                                isPlaying = true
                            }
                        }
                        // Always emit buffering update for UI stats
                        emit(StreamState.Buffering(progress, speedKB, seeds, peers))
                    }
                    4 -> emit(StreamState.Error("Engine Error occurred"))
                }
            } else {
                // If native returns null abruptly
                Log.w(TAG, "Native status unavailable")
            }
            
            delay(500)
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
