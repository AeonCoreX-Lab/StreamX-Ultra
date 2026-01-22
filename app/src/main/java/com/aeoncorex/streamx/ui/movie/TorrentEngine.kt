package com.aeoncorex.streamx.ui.movie

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.swig.sha1_hash
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

            // 3. Manually Extract InfoHash from Magnet Link
            // Format: magnet:?xt=urn:btih:<HASH>&...
            val uri = Uri.parse(magnetLink)
            val xt = uri.getQueryParameter("xt") ?: ""
            val infoHashStr = if (xt.contains("urn:btih:")) {
                xt.substringAfter("urn:btih:")
            } else {
                trySend(StreamState.Error("Invalid Magnet Link: Missing Hash"))
                close()
                return@callbackFlow
            }

            // 4. Create Sha1Hash correctly via SWIG
            // We create the SWIG object first to avoid ambiguous constructor issues
            val swigHash = try {
                sha1_hash(infoHashStr)
            } catch (e: Exception) {
                trySend(StreamState.Error("Invalid Hash Format"))
                close()
                return@callbackFlow
            }
            val infoHash = Sha1Hash(swigHash)

            // 5. Start Download
            // We use the simple download method which handles the magnet parsing internally
            // for the actual download logic.
            session?.download(magnetLink, downloadDir)
            Log.d(TAG, "Download initiated for hash: $infoHashStr")

            trySend(StreamState.Preparing("Metadata..."))

            // 6. Monitoring Loop
            var isPlaying = false
            var fileSelected = false
            var largestFileIndex = -1
            var isSequentialSet = false

            while (isActive) {
                val handle = session?.find(infoHash)

                if (handle != null && handle.isValid) {
                    val status = handle.status()

                    // A. Metadata Loaded -> Select Largest File
                    if (!fileSelected && status.hasMetadata()) {
                        val torrentInfo = handle.torrentFile()
                        
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
                            val torrentInfo = handle.torrentFile()
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
                        trySend(StreamState.Preparing("Metadata... S:$seeds P:$peers"))
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
        // session?.stop() // Optional: keep session alive for background download if needed
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
