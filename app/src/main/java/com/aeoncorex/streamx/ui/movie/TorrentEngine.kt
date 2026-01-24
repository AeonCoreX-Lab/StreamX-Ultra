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
    private const val MIN_BUFFER_SIZE = 5L * 1024 * 1024 // 5MB Buffer

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
                
                // Optimize for streaming
                settings.setInteger(settings_pack.int_types.download_rate_limit.swigValue(), 0)
                settings.setInteger(settings_pack.int_types.upload_rate_limit.swigValue(), 0)
                
                session?.applySettings(settings)
                session?.start()
                Log.d(TAG, "Torrent Session Started")
            }

            trySend(StreamState.Preparing("Fetching Metadata..."))

            // 3. Fetch Metadata (This fixes the 'String vs TorrentInfo' mismatch)
            // FIXED: Added 'downloadDir' as the 3rd argument to satisfy parameter 'p2'
            val torrentData: ByteArray? = withContext(Dispatchers.IO) {
                session?.fetchMagnet(magnetLink, 30, downloadDir)
            }

            if (torrentData == null) {
                trySend(StreamState.Error("Failed to fetch metadata. No peers found."))
                close()
                return@callbackFlow
            }

            // 4. Create TorrentInfo from bytes
            // Write to a temp file first as TorrentInfo(byte[]) might be unstable in some swig versions
            val tempFile = File(downloadDir, "meta_${System.currentTimeMillis()}.torrent")
            tempFile.writeBytes(torrentData)
            val torrentInfo = TorrentInfo(tempFile)
            
            // 5. Start Download using valid TorrentInfo
            session?.download(torrentInfo, downloadDir)
            
            // Get the valid InfoHash directly from the object (Fixes SWIG constructor errors)
            val infoHash = torrentInfo.infoHash()
            Log.d(TAG, "Download started. Hash: $infoHash")

            trySend(StreamState.Preparing("Starting Download..."))

            // 6. Monitoring Loop
            var isPlaying = false
            var fileSelected = false
            var largestFileIndex = -1

            while (isActive) {
                val handle = session?.find(infoHash)

                if (handle != null && handle.isValid) {
                    val status = handle.status()

                    // A. Select Largest File (The Movie)
                    if (!fileSelected) {
                        // Since we already have TorrentInfo, we can do this immediately
                        var maxFileSize = 0L
                        for (i in 0 until torrentInfo.numFiles()) {
                            val fileSize = torrentInfo.files().fileSize(i)
                            if (fileSize > maxFileSize) {
                                maxFileSize = fileSize
                                largestFileIndex = i
                            }
                        }

                        if (largestFileIndex != -1) {
                            // Prioritize the video file
                            val priorities = Array(torrentInfo.numFiles()) { Priority.IGNORE }
                            priorities[largestFileIndex] = Priority.DEFAULT
                            handle.prioritizeFiles(priorities)
                            
                            // High priority for the beginning of the file (for buffering)
                            handle.filePriority(largestFileIndex, Priority.TOP_PRIORITY)
                            
                            fileSelected = true
                            Log.d(TAG, "Movie File Selected: Index $largestFileIndex")
                        }
                    }

                    // B. Stream Status
                    val progress = (status.progress() * 100).toInt()
                    val speed = status.downloadPayloadRate() / 1024 // KB/s
                    val seeds = status.numSeeds()
                    val peers = status.numPeers()
                    val bytesDownloaded = status.totalDone()

                    if (!isPlaying && fileSelected) {
                        if (bytesDownloaded > MIN_BUFFER_SIZE) {
                            val fileName = torrentInfo.files().filePath(largestFileIndex)
                            val videoFile = File(downloadDir, fileName)
                            
                            if (videoFile.exists() && videoFile.length() > 0) {
                                isPlaying = true
                                Log.d(TAG, "Ready to Play: ${videoFile.absolutePath}")
                                trySend(StreamState.Ready(videoFile.absolutePath))
                            }
                        } else {
                            trySend(StreamState.Buffering(progress, speed.toLong(), seeds, peers))
                        }
                    } else if (isPlaying) {
                        trySend(StreamState.Buffering(progress, speed.toLong(), seeds, peers))
                    } else {
                        trySend(StreamState.Preparing("Buffering... S:$seeds P:$peers"))
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
        // session?.stop() 
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
