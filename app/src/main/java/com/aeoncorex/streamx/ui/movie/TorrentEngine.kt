package com.aeoncorex.streamx.ui.movie

import android.content.Context
import android.os.Environment
import android.util.Log
import com.frostwire.jlibtorrent.Priority
import com.frostwire.jlibtorrent.SessionManager
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
            session?.start()
            // Streaming Settings Optimization
            val settings = session?.settingsPack()
            settings?.setInteger("active_downloads", 4)
            settings?.setInteger("active_seeds", 4)
            session?.applySettings(settings)
            
            isEngineRunning = true
        }
    }

    fun startStreaming(magnet: String): Flow<StreamState> = callbackFlow {
        if (session == null) {
            trySend(StreamState.Error("Engine not initialized"))
            close()
            return@callbackFlow
        }

        val saveDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "StreamX_Temp")
        if (!saveDir.exists()) saveDir.mkdirs()

        try {
            trySend(StreamState.Preparing("Fetching Metadata..."))

            val metadata = withContext(Dispatchers.IO) {
                session?.fetchMagnet(magnet, 30, saveDir)
            }

            if (metadata == null) {
                trySend(StreamState.Error("Metadata Timeout. Check Internet."))
                close()
                return@callbackFlow
            }

            val torrentInfo = TorrentInfo.bdecode(metadata)
            trySend(StreamState.Preparing("Starting Engine..."))
            
            session?.download(torrentInfo, saveDir)

            // Find the handle
            var handle = session?.find(torrentInfo.infoHash())
            var attempts = 0
            while (handle == null && attempts < 20) {
                delay(200)
                handle = session?.find(torrentInfo.infoHash())
                attempts++
            }

            if (handle != null) {
                // --- CRITICAL FOR STREAMING: SEQUENTIAL DOWNLOAD ---
                handle.isSequentialDownload = true
                
                // Find largest file (the video)
                val files = torrentInfo.files()
                var largestIndex = 0
                var largestSize = 0L
                for (i in 0 until files.numFiles()) {
                    if (files.fileSize(i) > largestSize) {
                        largestSize = files.fileSize(i)
                        largestIndex = i
                    }
                }

                // Set Priority: Video gets TOP priority, others IGNORE
                val priorities = Array(files.numFiles()) { Priority.IGNORE }
                priorities[largestIndex] = Priority.SEVEN // Highest Priority
                handle.prioritizeFiles(priorities)

                val videoPath = saveDir.absolutePath + "/" + files.filePath(largestIndex)
                
                // Wait for a tiny buffer (1%) before sending Ready signal
                var isPlaying = false
                while (isActive) {
                    val status = handle.status()
                    val progress = (status.progress() * 100).toInt()
                    val downloadRate = status.downloadRate() / 1024 // KB/s

                    if (!isPlaying && progress > 1) { // 1% buffer হলেই প্লে শুরু
                        isPlaying = true
                        trySend(StreamState.Ready(videoPath))
                    }

                    if (isPlaying && progress < 100) {
                        trySend(StreamState.Buffering(progress, downloadRate))
                    }
                    
                    delay(1000)
                }
            } else {
                trySend(StreamState.Error("Torrent Handle Failed"))
            }

        } catch (e: Exception) {
            trySend(StreamState.Error("Engine Error: ${e.message}"))
        }
        awaitClose { }
    }

    fun stop() {
        session?.pause()
    }
}

sealed class StreamState {
    data class Preparing(val message: String) : StreamState()
    data class Buffering(val progress: Int, val speed: Int) : StreamState() // speed in KB/s
    data class Ready(val filePath: String) : StreamState()
    data class Error(val message: String) : StreamState()
}
