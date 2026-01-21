package com.aeoncorex.streamx.ui.movie

import android.content.Context
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import java.io.File

object TorrentEngine {
    private var session: SessionManager? = null
    private var isEngineRunning = false
    
    // বাফার সাইজ মাত্র ৫ মেগাবাইট রাখা হয়েছে যাতে ১-২ সেকেন্ডেই প্লে শুরু হয়
    private const val MIN_BUFFER_SIZE = 5L * 1024 * 1024 

    fun init(context: Context) {
        if (session == null) {
            session = SessionManager()
            val settings = SettingsPack()
            
            // Streaming Optimization Settings
            settings.activeDownloads(3)
            settings.connectionsLimit(200)
            settings.downloadRateLimit(0) // Unlimited Speed
            settings.uploadRateLimit(0)
            settings.anonymousMode(false)
            
            session?.applySettings(settings)
            session?.start()
            isEngineRunning = true
            Log.d("TorrentEngine", "Engine Started")
        }
    }

    fun startStreaming(magnet: String): Flow<StreamState> = callbackFlow {
        if (session == null || !isEngineRunning) {
            trySend(StreamState.Error("Engine Not Initialized"))
            close()
            return@callbackFlow
        }

        val saveDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "StreamX_Cache")
        if (!saveDir.exists()) saveDir.mkdirs()

        try {
            trySend(StreamState.Preparing("Connecting to Peers..."))
            
            // 1. Fetch Metadata (Timeout 30s)
            val metadataBytes = withContext(Dispatchers.IO) {
                session?.fetchMagnet(magnet, 30, saveDir)
            }

            if (metadataBytes == null) {
                trySend(StreamState.Error("Metadata Timeout. No peers found."))
                close()
                return@callbackFlow
            }

            val torrentInfo = TorrentInfo.bdecode(metadataBytes)
            
            // 2. Find Largest File (The Movie)
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
            
            // 3. Start Downloading
            session?.download(torrentInfo, saveDir)

            // Wait for handle
            var handle: TorrentHandle? = null
            var retries = 0
            val sha1Hash = torrentInfo.infoHash()
            while (isActive && handle == null && retries < 50) {
                handle = session?.find(sha1Hash)
                if (handle == null) { delay(200); retries++ }
            }

            if (handle == null || !handle.isValid) {
                trySend(StreamState.Error("Failed to start download"))
                close()
                return@callbackFlow
            }

            // --- CRITICAL STREAMING OPTIMIZATION ---
            
            // A. Set Priorities: Video = HIGH, Others = IGNORE
            val priorities = Array(files.numFiles()) { Priority.IGNORE }
            priorities[largestFileIndex] = Priority.SEVEN
            handle.prioritizeFiles(priorities)

            // B. Force Sequential Download (Download 0% -> 100% in order)
            // This flag is essential for streaming!
            handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)

            val videoFileName = files.filePath(largestFileIndex)
            val videoPath = File(saveDir, videoFileName).absolutePath
            var isPlaying = false

            // Monitoring Loop
            while (isActive) {
                val status = handle.status()
                val progress = (status.progress() * 100).toInt()
                val speed = status.downloadPayloadRate() / 1024 // KB/s
                val seeds = status.numSeeds()
                val peers = status.numPeers()
                val doneBytes = status.totalDone()

                if (!isPlaying) {
                    // প্লে কন্ডিশন: ৫ মেগাবাইট বাফার অথবা ১% ডাউনলোড
                    val isEnoughBuffer = doneBytes > MIN_BUFFER_SIZE
                    val hasStarted = progress >= 1 
                    
                    if (isEnoughBuffer || hasStarted) {
                        isPlaying = true
                        trySend(StreamState.Ready(videoPath))
                    } else {
                        trySend(StreamState.Buffering(progress, speed.toLong(), seeds, peers))
                    }
                } else {
                    // প্লে চলাকালীন আপডেট
                    if (progress < 100) {
                        trySend(StreamState.Buffering(progress, speed.toLong(), seeds, peers))
                    }
                }
                delay(1000)
            }

        } catch (e: Exception) {
            Log.e("TorrentEngine", "Error: ${e.message}")
            trySend(StreamState.Error(e.message ?: "Unknown Error"))
        }

        awaitClose { 
            // Optional: Pause session to save data when user leaves
            // session?.pause()
        }
    }

    fun stop() {
        // session?.stop() 
    }
}
