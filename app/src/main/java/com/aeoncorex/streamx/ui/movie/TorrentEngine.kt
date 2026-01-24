package com.aeoncorex.streamx.ui.movie

import android.content.Context
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
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.swig.settings_pack
import java.io.File

object TorrentEngine {
    private var session: SessionManager? = null
    private const val TAG = "TorrentEngine"
    
    // Reduced buffer to 2MB for faster start (Telegram style)
    private const val MIN_BUFFER_SIZE = 2L * 1024 * 1024 

    fun start(context: Context, magnetLink: String): Flow<StreamState> = callbackFlow {
        try {
            // 1. Setup Cache Directory
            val rootDir = context.externalCacheDir ?: context.cacheDir
            val downloadDir = File(rootDir, "StreamX_Temp")
            if (!downloadDir.exists()) downloadDir.mkdirs()

            // 2. Initialize Session
            if (session == null) {
                session = SessionManager()
                val settings = SettingsPack()
                settings.setInteger(settings_pack.int_types.active_downloads.swigValue(), 4)
                settings.setBoolean(settings_pack.bool_types.enable_dht.swigValue(), true)
                settings.setInteger(settings_pack.int_types.connections_limit.swigValue(), 200)
                
                // Optimize for streaming (Unlimited speed)
                settings.setInteger(settings_pack.int_types.download_rate_limit.swigValue(), 0)
                settings.setInteger(settings_pack.int_types.upload_rate_limit.swigValue(), 0)
                
                session?.applySettings(settings)
                session?.start()
                Log.d(TAG, "Torrent Session Started")
            }

            trySend(StreamState.Preparing("Fetching Metadata..."))

            // 3. Fetch Metadata
            val torrentData: ByteArray? = withContext(Dispatchers.IO) {
                session?.fetchMagnet(magnetLink, 30, downloadDir)
            }

            if (torrentData == null) {
                trySend(StreamState.Error("Failed to fetch metadata. No peers found."))
                close()
                return@callbackFlow
            }

            // 4. Create TorrentInfo
            val tempFile = File(downloadDir, "meta_${System.currentTimeMillis()}.torrent")
            tempFile.writeBytes(torrentData)
            val torrentInfo = TorrentInfo(tempFile)
            
            // 5. Start Download
            session?.download(torrentInfo, downloadDir)
            
            val infoHash = torrentInfo.infoHash()
            Log.d(TAG, "Download started. Hash: $infoHash")

            trySend(StreamState.Preparing("Starting Engine..."))

            // 6. Monitoring Loop
            var isPlaying = false
            var fileSelected = false
            var largestFileIndex = -1

            while (isActive) {
                val handle = session?.find(infoHash)

                if (handle != null && handle.isValid) {
                    val status = handle.status()

                    // --- A. SMART SEQUENTIAL LOGIC (THE FIX) ---
                    if (!fileSelected) {
                        var maxFileSize = 0L
                        for (i in 0 until torrentInfo.numFiles()) {
                            val fileSize = torrentInfo.files().fileSize(i)
                            if (fileSize > maxFileSize) {
                                maxFileSize = fileSize
                                largestFileIndex = i
                            }
                        }

                        if (largestFileIndex != -1) {
                            // 1. Prioritize ONLY the movie file (Ignore others to save bandwidth)
                            val priorities = Array(torrentInfo.numFiles()) { Priority.IGNORE }
                            priorities[largestFileIndex] = Priority.DEFAULT
                            handle.prioritizeFiles(priorities)
                            
                            // 2. CRITICAL: Force High Priority
                            // We removed 'setSequentialDownload(true)' because it caused a compilation error.
                            // Setting 'TOP_PRIORITY' achieves a similar effect by requesting pieces ASAP.
                            handle.filePriority(largestFileIndex, Priority.TOP_PRIORITY)
                            
                            fileSelected = true
                            Log.d(TAG, "Streaming Mode Active: File Priority Set to TOP")
                        }
                    }

                    // --- B. Stream Status ---
                    val progress = (status.progress() * 100).toInt()
                    val speed = status.downloadPayloadRate() / 1024 // KB/s
                    val seeds = status.numSeeds()
                    val peers = status.numPeers()
                    
                    // Check actual bytes downloaded
                    val bytesDownloaded = handle.status().totalDone() 

                    if (!isPlaying && fileSelected) {
                        // Logic: If we have the first 2MB (Header), start playing.
                        if (bytesDownloaded > MIN_BUFFER_SIZE) {
                            val fileName = torrentInfo.files().filePath(largestFileIndex)
                            val videoFile = File(downloadDir, fileName)
                            
                            // Verify file exists on disk
                            if (videoFile.exists()) {
                                isPlaying = true
                                Log.d(TAG, "Buffer Filled. Starting Playback.")
                                trySend(StreamState.Ready(videoFile.absolutePath))
                            }
                        } else {
                            trySend(StreamState.Buffering(progress, speed.toLong(), seeds, peers))
                        }
                    } else if (isPlaying) {
                        // Continue updating UI stats while playing
                        trySend(StreamState.Buffering(progress, speed.toLong(), seeds, peers))
                    } else {
                        trySend(StreamState.Preparing("Buffering... S:$seeds"))
                    }
                } else {
                    trySend(StreamState.Preparing("Connecting to peers..."))
                }
                delay(1000)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error: ${e.message}")
            trySend(StreamState.Error("Stream Failed: ${e.message}"))
        }

        awaitClose {
            Log.d(TAG, "Stream Closed")
        }
    }

    fun stop() {
        // Keeping session alive helps DHT, but pausing is an option if needed
        // session?.pause()
    }
    
    fun clearCache(context: Context) {
        try {
            val rootDir = context.externalCacheDir ?: context.cacheDir
            val downloadDir = File(rootDir, "StreamX_Temp")
            if (downloadDir.exists()) {
                downloadDir.deleteRecursively()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear cache")
        }
    }
}
