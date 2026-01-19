package com.aeoncorex.streamx.ui.movie

import android.content.Context
import android.os.Environment
import android.util.Log
import com.frostwire.jlibtorrent.Priority
import com.frostwire.jlibtorrent.SessionManager
import com.frostwire.jlibtorrent.TorrentInfo
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import java.io.File

object TorrentEngine {
    private var session: SessionManager? = null
    private var currentMagnet: String? = null
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

        currentMagnet = magnet
        
        // Save directory setup
        val saveDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "StreamX_Cache")
        if (!saveDir.exists()) saveDir.mkdirs()

        try {
            trySend(StreamState.Preparing("Fetching Metadata..."))
            
            // Fetch/Add Torrent
            // Note: In a real app, you might want to fetch magnet metadata first using fetchMagnet() 
            // but for speed we add it directly and wait for metadata.
            session?.download(TorrentInfo.bdecode(ByteArray(0)), saveDir) // Dummy call or logic handling
            
            // Correct way to add magnet in jlibtorrent
            val byteMagnet = magnet // string handling
            // We rely on session.download to handle magnet strings internally if supported or manual fetch
            // Simpler approach for jlibtorrent wrapper:
            
            // Clean previous downloads to avoid conflict logic for this demo
            session?.stop() 
            session?.start()
            
            // Wait a bit for session restart
            delay(100)

            val handle = session?.download(magnet, saveDir)
            
            if (handle == null) {
                trySend(StreamState.Error("Invalid Magnet Link"))
                close()
                return@callbackFlow
            }

            // Loop to check status
            var isReady = false
            var attempts = 0
            
            while (isActive) {
                val status = handle.status()
                
                // 1. Check if Metadata is received
                if (!handle.torrentFile().isValid) {
                    trySend(StreamState.Preparing("Downloading Metadata..."))
                    delay(1000)
                    attempts++
                    if (attempts > 30) { // 30 seconds timeout for metadata
                        trySend(StreamState.Error("Timeout fetching metadata"))
                        close()
                        break
                    }
                    continue
                }

                // 2. Metadata Ready - Setup Sequential Download for Streaming
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
                    val priorities = Priority.array(files.numFiles(), Priority.IGNORE)
                    priorities[largestFileIndex] = Priority.NORMAL
                    handle.prioritizeFiles(priorities)
                    
                    // CRITICAL: Enable Sequential Download (Download pieces in order 1,2,3...)
                    // This allows ExoPlayer to play while downloading
                    handle.setSequentialDownload(true)
                    
                    isReady = true
                    val videoPath = saveDir.absolutePath + "/" + files.filePath(largestFileIndex)
                    trySend(StreamState.Ready(videoPath))
                }

                // 3. Monitor Progress
                val progress = (status.progress() * 100).toInt()
                val seeds = status.numSeeds()
                val speed = status.downloadPayloadRate() / 1024 // KB/s
                
                // If using ExoPlayer, we just need the file path (sent in Ready). 
                // But we can update UI with buffering status.
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
            // Optional: Stop download on exit or keep correctly in background service
            // For now, we keep session alive but maybe pause handle
            // session?.removeTorrent(handle) 
        }
    }

    fun stop() {
        // Pausing session is better than killing it if user comes back
        session?.pause()
        currentMagnet = null
    }
}

// Keeping the same State class so UI doesn't break
sealed class StreamState {
    data class Preparing(val message: String) : StreamState()
    data class Buffering(val progress: Int) : StreamState()
    data class Ready(val filePath: String) : StreamState()
    data class Error(val message: String) : StreamState()
}
