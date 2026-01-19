package com.aeoncorex.streamx.ui.movie

import android.content.Context
import android.os.Environment
import android.util.Log
import com.frostwire.jlibtorrent.Priority
import com.frostwire.jlibtorrent.SessionManager
import com.frostwire.jlibtorrent.Sha1Hash
import com.frostwire.jlibtorrent.TorrentHandle
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import java.io.File

object TorrentEngine {
    private var session: SessionManager? = null
    private var isEngineRunning = false

    fun init(context: Context) {
        if (session == null) {
            session = SessionManager()
            // Setting up settings for streaming optimization
            session?.start()
            isEngineRunning = true
            Log.d("TorrentEngine", "LibTorrent Session Started")
        }
    }

    // Returns a Flow that emits status and eventually the File Path
    fun startStreaming(magnet: String): Flow<StreamState> = callbackFlow {
        if (session == null || !isEngineRunning) {
            trySend(StreamState.Error("Engine not initialized"))
            close()
            return@callbackFlow
        }

        // Save directory setup
        val saveDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "StreamX_Cache")
        if (!saveDir.exists()) saveDir.mkdirs()

        try {
            trySend(StreamState.Preparing("Initializing Session..."))
            
            // Restart session to clear previous handles (Optional, helps with single-stream focus)
            session?.pause()
            session?.resume()

            // 1. Extract Hash from Magnet Link to track it
            val hashStr = parseMagnetHash(magnet)
            if (hashStr == null) {
                trySend(StreamState.Error("Invalid Magnet Link Format"))
                close()
                return@callbackFlow
            }

            val sha1Hash = Sha1Hash(hashStr)

            // 2. Start Download (Async)
            // This adds the magnet to the session and starts fetching metadata automatically
            session?.download(magnet, saveDir)

            var handle: TorrentHandle? = null
            var retries = 0
            
            // 3. Find the TorrentHandle in the session
            while (isActive && handle == null && retries < 50) {
                handle = session?.find(sha1Hash)
                if (handle == null) {
                    delay(200)
                    retries++
                }
            }

            if (handle == null) {
                trySend(StreamState.Error("Failed to add Torrent"))
                close()
                return@callbackFlow
            }

            // 4. Wait for Metadata
            trySend(StreamState.Preparing("Fetching Metadata..."))
            
            while (isActive && !handle.status().hasMetadata()) {
                val progress = (handle.status().progress() * 100).toInt()
                // Metadata fetching usually doesn't show normal progress, but we wait
                delay(500)
            }

            // 5. Metadata Ready - Configure Priorities
            var isReady = false
            
            while (isActive) {
                val status = handle.status()
                
                if (!isReady && status.hasMetadata()) {
                    Log.d("TorrentEngine", "Metadata Ready. Config for Stream.")
                    
                    val torrentInfo = handle.torrentFile()
                    val files = torrentInfo.files()
                    var largestFileIndex = 0
                    var largestSize = 0L
                    
                    for (i in 0 until files.numFiles()) {
                        val size = files.fileSize(i)
                        if (size > largestSize) {
                            largestSize = size
                            largestFileIndex = i
                        }
                    }

                    // Set priorities: High for video, Ignore others
                    val priorities = Array(files.numFiles()) { Priority.IGNORE }
                    priorities[largestFileIndex] = Priority.NORMAL
                    
                    handle.prioritizeFiles(priorities)
                    
                    // Prioritize the first and last pieces of the file for smoother streaming start
                    // (Optional optimization, jlibtorrent handles some of this)
                    
                    isReady = true
                    val videoPath = saveDir.absolutePath + "/" + files.filePath(largestFileIndex)
                    trySend(StreamState.Ready(videoPath))
                }

                // Monitor Progress
                val progress = (status.progress() * 100).toInt()
                val seeds = status.numSeeds()
                val downloadRate = status.downloadRate() / 1024 // KB/s

                if (progress < 100) {
                    trySend(StreamState.Buffering(progress))
                    // Optional: You can send download rate in the message
                    // trySend(StreamState.Preparing("Buffering $progress% ($seeds seeds, $downloadRate KB/s)"))
                }

                delay(1000)
            }

        } catch (e: Exception) {
            Log.e("TorrentEngine", "Error: ${e.message}")
            trySend(StreamState.Error(e.message ?: "Unknown Error"))
        }

        awaitClose {
            // Optional: Cleanup
        }
    }

    fun stop() {
        session?.pause()
    }

    // Helper to extract SHA1 Hex from Magnet URI
    private fun parseMagnetHash(magnet: String): String? {
        return try {
            val pattern = "xt=urn:btih:([a-fA-F0-9]{40})".toRegex()
            val match = pattern.find(magnet)
            match?.groupValues?.get(1)
        } catch (e: Exception) {
            null
        }
    }
}

// Keeping the same State class so UI doesn't break
sealed class StreamState {
    data class Preparing(val message: String) : StreamState()
    data class Buffering(val progress: Int) : StreamState()
    data class Ready(val filePath: String) : StreamState()
    data class Error(val message: String) : StreamState()
}
