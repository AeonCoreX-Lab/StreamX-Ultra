package com.aeoncorex.streamx.ui.movie

import android.content.Context
import android.os.Environment
import android.util.Log
import com.frostwire.jlibtorrent.Priority
import com.frostwire.jlibtorrent.SessionManager
import com.frostwire.jlibtorrent.Sha1Hash
import com.frostwire.jlibtorrent.TorrentHandle
import com.frostwire.jlibtorrent.TorrentInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
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

            // 2. Fetch Metadata (Required before download in this version)
            trySend(StreamState.Preparing("Fetching Metadata..."))
            
            // fetchMagnet is blocking, so we run it on the IO dispatcher
            // 30 seconds timeout
            val metadataBytes = withContext(Dispatchers.IO) {
                session?.fetchMagnet(magnet, 30)
            }

            if (metadataBytes == null) {
                trySend(StreamState.Error("Timeout: Could not fetch torrent metadata"))
                close()
                return@callbackFlow
            }

            // Decode the raw bytes into a TorrentInfo object
            val torrentInfo = try {
                TorrentInfo.bdecode(metadataBytes)
            } catch (e: Exception) {
                trySend(StreamState.Error("Invalid Metadata received"))
                close()
                return@callbackFlow
            }

            // 3. Start Download using the TorrentInfo object
            trySend(StreamState.Preparing("Starting Download..."))
            session?.download(torrentInfo, saveDir)

            var handle: TorrentHandle? = null
            var retries = 0
            
            // 4. Find the TorrentHandle in the session
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

            // 5. Metadata is already ready (since we provided TorrentInfo). Configure Priorities.
            var isReady = false
            
            while (isActive) {
                val status = handle.status()
                
                if (!isReady) {
                    Log.d("TorrentEngine", "Metadata Ready. Config for Stream.")
                    
                    val ti = handle.torrentFile() // Use the handle's info
                    val files = ti.files()
                    var largestFileIndex = 0
                    var largestSize = 0L
                    
                    for (i in 0 until files.numFiles()) {
                        val size = files.fileSize(i)
                        if (size > largestSize) {
                            largestSize = size
                            largestFileIndex = i
                        }
                    }

                    // Set priorities: High for video (NORMAL), Ignore others
                    val priorities = Array(files.numFiles()) { Priority.IGNORE }
                    priorities[largestFileIndex] = Priority.NORMAL
                    
                    handle.prioritizeFiles(priorities)
                    
                    isReady = true
                    val videoPath = saveDir.absolutePath + "/" + files.filePath(largestFileIndex)
                    trySend(StreamState.Ready(videoPath))
                }

                // Monitor Progress
                val progress = (status.progress() * 100).toInt()
                
                // Only send buffering updates if not 100% yet
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
