package com.aeoncorex.streamx.ui.movie

import android.content.Context
import android.os.Environment
import android.util.Log
import com.frostwire.jlibtorrent.Priority
import com.frostwire.jlibtorrent.SessionManager
import com.frostwire.jlibtorrent.SettingsPack
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
            
            // FIX: Create a new SettingsPack instead of getting it from null/session
            val settings = SettingsPack()
            settings.activeDownloads(5)
            settings.connectionsLimit(200)
            settings.downloadRateLimit(0) // 0 = Unlimited
            settings.uploadRateLimit(0)
            
            session?.applySettings(settings)
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
            
            // 1. Extract Hash
            val hashStr = parseMagnetHash(magnet)
            if (hashStr == null) {
                trySend(StreamState.Error("Invalid Magnet Link"))
                close()
                return@callbackFlow
            }

            val sha1Hash = Sha1Hash(hashStr)

            // 2. Fetch Metadata (timeout 45s)
            trySend(StreamState.Preparing("Fetching Metadata..."))
            
            val metadataBytes = withContext(Dispatchers.IO) {
                session?.fetchMagnet(magnet, 45, saveDir)
            }

            if (metadataBytes == null) {
                trySend(StreamState.Error("Metadata Timeout. No peers found."))
                close()
                return@callbackFlow
            }

            val torrentInfo = TorrentInfo.bdecode(metadataBytes)

            // 3. Start Download
            trySend(StreamState.Preparing("Starting Download..."))
            session?.download(torrentInfo, saveDir)

            var handle: TorrentHandle? = null
            var retries = 0
            
            // 4. Find the Handle
            while (isActive && handle == null && retries < 50) {
                handle = session?.find(sha1Hash)
                if (handle == null) {
                    delay(200)
                    retries++
                }
            }

            if (handle == null) {
                trySend(StreamState.Error("Failed to start torrent handle"))
                close()
                return@callbackFlow
            }

            // 5. Configure for Streaming
            Log.d("TorrentEngine", "Metadata Ready. Configuring Priorities.")
            
            // FIX: Removed 'setSequentialDownload' as it caused compilation errors.
            // rely on prioritizeFiles to ensure the video downloads.
            
            val ti = handle.torrentFile()
            val files = ti.files()
            var largestFileIndex = 0
            var largestSize = 0L
            
            // Find the video file (largest file)
            for (i in 0 until files.numFiles()) {
                val size = files.fileSize(i)
                if (size > largestSize) {
                    largestSize = size
                    largestFileIndex = i
                }
            }

            // Prioritize video file (High Priority), ignore others (Zero Priority)
            val priorities = Array(files.numFiles()) { Priority.IGNORE }
            priorities[largestFileIndex] = Priority.NORMAL
            handle.prioritizeFiles(priorities)
            
            val videoPath = saveDir.absolutePath + "/" + files.filePath(largestFileIndex)
            var isPlaying = false
            
            // 6. Monitoring Loop
            while (isActive) {
                val status = handle.status()
                val progress = (status.progress() * 100).toInt()
                val speed = status.downloadPayloadRate() / 1024 // KB/s
                val seeds = status.numSeeds()
                val peers = status.numPeers()

                // Logic: Wait for at least 1% buffer or sufficient speed before playing
                if (!isPlaying && progress >= 1 && speed > 50) {
                    isPlaying = true
                    trySend(StreamState.Ready(videoPath))
                }

                if (progress < 100) {
                    trySend(StreamState.Buffering(progress, speed.toLong(), seeds, peers))
                }

                delay(1000)
            }

        } catch (e: Exception) {
            Log.e("TorrentEngine", "Error: ${e.message}")
            trySend(StreamState.Error(e.message ?: "Unknown Error"))
        }

        awaitClose {
            // Optional cleanup
        }
    }

    fun stop() {
        session?.pause()
    }

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

// Updated State Class with correct fields needed by MoviePlayerScreen
sealed class StreamState {
    data class Preparing(val message: String) : StreamState()
    data class Buffering(val progress: Int, val speed: Long, val seeds: Int, val peers: Int) : StreamState()
    data class Ready(val filePath: String) : StreamState()
    data class Error(val message: String) : StreamState()
}
