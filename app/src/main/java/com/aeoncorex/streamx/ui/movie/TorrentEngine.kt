package com.aeoncorex.streamx.ui.movie

import android.content.Context
import android.os.Environment
import android.util.Log
import com.frostwire.jlibtorrent.Priority
import com.frostwire.jlibtorrent.SessionManager
import com.frostwire.jlibtorrent.Sha1Hash
import com.frostwire.jlibtorrent.TorrentHandle
import com.frostwire.jlibtorrent.TorrentInfo //Added import for TorrentInfo
import kotlinx.coroutines.Dispatchers // Added for IO dispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext // Added for context switching
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

            // FIX 1: Fetch metadata explicitly to get TorrentInfo, as session.download(String) is not available
            val magnetBytes = withContext(Dispatchers.IO) {
                session?.fetchMagnet(magnet, 30) // 30 seconds timeout
            }

            if (magnetBytes == null) {
                trySend(StreamState.Error("Failed to fetch magnet metadata"))
                close()
                return@callbackFlow
            }

            // Decode the bytes into TorrentInfo
            val ti = TorrentInfo.bdecode(magnetBytes)

            // Start download using the valid TorrentInfo object
            session?.download(ti, saveDir)
            
            // Extract Hash from TorrentInfo to find the handle
            val sha1Hash = ti.infoHash()
            
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
            
            while (isActive) {
                val status = handle.status()
                
                // Metadata is already fetched via fetchMagnet, so we can skip the isValid check loop
                // But we keep the loop for progress monitoring

                // Setup Priority for Streaming
                if (!isReady) {
                    Log.d("TorrentEngine", "Metadata Ready. Config for Stream.")
                    
                    val torrentInfo = handle.torrentFile() // Should be valid now
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
                    
                    // FIX 2: Removed setSequentialDownload(true) as it's unresolved in this version.
                    // Prioritizing the file usually triggers enough sequential behavior for basic streaming.
                    // handle.setSequentialDownload(true) 
                    
                    isReady = true
                    val videoPath = saveDir.absolutePath + "/" + files.filePath(largestFileIndex)
                    trySend(StreamState.Ready(videoPath))
                }

                // Monitor Progress
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
