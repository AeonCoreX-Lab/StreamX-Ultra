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
            trySend(StreamState.Preparing("Fetching Metadata..."))
            
            // Clean previous downloads to avoid conflict logic for this demo
            session?.stop() 
            session?.start()
            delay(500) // Wait for restart

            // 1. Download doesn't return handle directly in jlibtorrent wrapper, it returns void.
            // We must find the handle after adding it.
            session?.download(magnet, saveDir)
            
            // 2. Extract Hash from Magnet Link to find the handle
            val hashStr = magnet.substringAfter("xt=urn:btih:").substringBefore("&")
            val sha1Hash = Sha1Hash(hashStr)
            
            var handle: TorrentHandle? = null
            var retries = 0
            
            // Wait for handle to appear in session
            while (isActive && handle == null && retries < 20) {
                handle = session?.find(sha1Hash)
                if (handle == null) {
                    delay(500)
                    retries++
                }
            }

            if (handle == null) {
                trySend(StreamState.Error("Failed to add Torrent"))
                close()
                return@callbackFlow
            }

            // Loop to check status
            var isReady = false
            var metadataAttempts = 0
            
            while (isActive) {
                val status = handle.status()
                
                // 3. Check if Metadata is received
                if (!handle.torrentFile().isValid) {
                    trySend(StreamState.Preparing("Downloading Metadata..."))
                    delay(1000)
                    metadataAttempts++
                    if (metadataAttempts > 30) { // 30 seconds timeout for metadata
                        trySend(StreamState.Error("Timeout fetching metadata"))
                        close()
                        break
                    }
                    continue
                }

                // 4. Metadata Ready - Setup Sequential Download for Streaming
                if (!isReady) {
                    Log.d("TorrentEngine", "Metadata Received. Config for Stream.")
                    
                    // Prioritize the largest file (The Video)
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
                    // FIXED: Using Array<Priority> instead of internal array methods that cause type mismatch
                    val priorities = Array(files.numFiles()) { Priority.IGNORE }
                    priorities[largestFileIndex] = Priority.NORMAL
                    
                    handle.prioritizeFiles(priorities)
                    
                    // CRITICAL: Enable Sequential Download (Download pieces in order 1,2,3...)
                    handle.setSequentialDownload(true)
                    
                    isReady = true
                    val videoPath = saveDir.absolutePath + "/" + files.filePath(largestFileIndex)
                    trySend(StreamState.Ready(videoPath))
                }

                // 5. Monitor Progress
                val progress = (status.progress() * 100).toInt()
                
                if (progress < 100) {
                    trySend(StreamState.Buffering(progress))
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
}

// Keeping the same State class so UI doesn't break
sealed class StreamState {
    data class Preparing(val message: String) : StreamState()
    data class Buffering(val progress: Int) : StreamState()
    data class Ready(val filePath: String) : StreamState()
    data class Error(val message: String) : StreamState()
}
