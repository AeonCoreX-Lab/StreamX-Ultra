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
    private const val STREAM_READY_BUFFER_MB = 15L // 15MB বাফার হলে প্লে হবে

    fun init(context: Context) {
        if (session == null) {
            session = SessionManager()
            val settings = SettingsPack()
            settings.activeDownloads(5)
            settings.connectionsLimit(200)
            settings.downloadRateLimit(0)
            settings.uploadRateLimit(0)
            settings.anonymousMode(false) 
            session?.applySettings(settings)
            session?.start()
            isEngineRunning = true
            Log.d("TorrentEngine", "LibTorrent Session Started")
        }
    }

    fun startStreaming(magnet: String): Flow<StreamState> = callbackFlow {
        if (session == null || !isEngineRunning) {
            trySend(StreamState.Error("Engine not initialized"))
            close()
            return@callbackFlow
        }

        // Cache Directory
        val saveDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "StreamX_Cache")
        if (!saveDir.exists()) saveDir.mkdirs()

        try {
            trySend(StreamState.Preparing("Initializing Session..."))
            
            val hashStr = parseMagnetHash(magnet) ?: run {
                trySend(StreamState.Error("Invalid Magnet Link"))
                close()
                return@callbackFlow
            }

            val sha1Hash = Sha1Hash(hashStr)

            // Metadata Fetching
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
            session?.download(torrentInfo, saveDir)

            var handle: TorrentHandle? = null
            var retries = 0
            while (isActive && handle == null && retries < 50) {
                handle = session?.find(sha1Hash)
                if (handle == null) { delay(200); retries++ }
            }

            if (handle == null || !handle.isValid) {
                trySend(StreamState.Error("Failed to start torrent handle"))
                close()
                return@callbackFlow
            }

            // --- CRITICAL FIX: SEQUENTIAL DOWNLOAD SETUP ---
            val files = torrentInfo.files()
            var largestFileIndex = 0
            var largestSize = 0L

            // সব ফাইল চেক করে মেইন ভিডিও ফাইল বের করা
            for (i in 0 until files.numFiles()) {
                if (files.fileSize(i) > largestSize) {
                    largestSize = files.fileSize(i)
                    largestFileIndex = i
                }
            }

            // শুধুমাত্র ভিডিও ফাইলটি হাই প্রায়োরিটি দেওয়া
            val priorities = Array(files.numFiles()) { Priority.IGNORE }
            priorities[largestFileIndex] = Priority.SEVEN // Highest Priority
            handle.prioritizeFiles(priorities)
            
            // **Sequential Download অন করা (ভিডিওর শুরু থেকে ডাউনলোড হবে)**
            handle.setSequentialDownload(true) 
            
            val videoPath = saveDir.absolutePath + "/" + files.filePath(largestFileIndex)
            var isPlaying = false

            // Monitoring Loop
            while (isActive) {
                val status = handle.status()
                val progress = (status.progress() * 100).toInt()
                val speed = status.downloadPayloadRate() / 1024 // KB/s
                val seeds = status.numSeeds()
                val peers = status.numPeers()
                val doneBytes = status.totalDone()

                // Play Logic: 15MB বা ২% ডাউনলোড হলে প্লে হবে
                if (!isPlaying) {
                    if (doneBytes > STREAM_READY_BUFFER_MB * 1024 * 1024 || progress >= 2) {
                        isPlaying = true
                        trySend(StreamState.Ready(videoPath))
                    } else {
                        trySend(StreamState.Buffering(progress, speed.toLong(), seeds, peers))
                    }
                } else {
                    // প্লে চলাকালীন আপডেট পাঠানো (অপশনাল)
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

        awaitClose { /* Keep session alive specifically for background download if needed */ }
    }

    fun stop() {
        // session?.pause()
    }

    private fun parseMagnetHash(magnet: String): String? {
        return try {
            val pattern = "xt=urn:btih:([a-fA-F0-9]{40})".toRegex()
            pattern.find(magnet)?.groupValues?.get(1)
        } catch (e: Exception) { null }
    }
}
