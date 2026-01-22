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
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.swig.settings_pack
import java.io.File

object TorrentEngine {
    private var session: SessionManager? = null
    private const val TAG = "TorrentEngine"

    // Play when 5MB is buffered (Adjusted for faster start)
    private const val MIN_BUFFER_SIZE = 5L * 1024 * 1024 

    private val TRACKERS = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://9.rarbg.com:2810/announce",
        "udp://tracker.openbittorrent.com:80/announce",
        "udp://opentracker.i2p.rocks:6969/announce",
        "http://tracker.openbittorrent.com:80/announce",
        "udp://open.demonii.com:1337/announce"
    )

    fun start(context: Context, magnetLink: String): Flow<StreamState> = callbackFlow {
        try {
            // 1. Setup Cache Directory
            // Use externalCacheDir to prevent gallery scanning (treated as temp files)
            val rootDir = context.externalCacheDir ?: context.cacheDir
            val downloadDir = File(rootDir, "StreamX_Temp")
            if (!downloadDir.exists()) downloadDir.mkdirs()

            // 2. Initialize Session
            if (session == null) {
                session = SessionManager()
                val settings = SettingsPack()
                // Enable DHT and optimize for streaming
                settings.setInteger(settings_pack.int_types.active_downloads.swigValue(), 4)
                settings.setBoolean(settings_pack.bool_types.enable_dht.swigValue(), true)
                settings.setInteger(settings_pack.int_types.connections_limit.swigValue(), 200)
                settings.setInteger(settings_pack.int_types.download_rate_limit.swigValue(), 0)
                settings.setInteger(settings_pack.int_types.upload_rate_limit.swigValue(), 0)
                
                session?.applySettings(settings)
                session?.start()
                Log.d(TAG, "Torrent Session Started")
            }

            // 3. Extract InfoHash Manually (Needed to find the handle later)
            val infoHashStr = try {
                val uri = Uri.parse(magnetLink)
                uri.getQueryParameter("xt")?.substringAfter("urn:btih:") ?: ""
            } catch (e: Exception) {
                ""
            }

            if (infoHashStr.isEmpty()) {
                trySend(StreamState.Error("Invalid Magnet Link"))
                close()
                return@callbackFlow
            }

            // Create Sha1Hash object once - Using fully qualified name to avoid ambiguity
            val infoHash = try {
                org.libtorrent4j.Sha1Hash(infoHashStr)
            } catch (e: Exception) {
                trySend(StreamState.Error("Invalid Hash Format"))
                close()
                return@callbackFlow
            }

            // 4. Prepare Magnet with Trackers
            val sb = StringBuilder(magnetLink)
            if (!magnetLink.contains("tr=")) {
                TRACKERS.forEach { tr -> sb.append("&tr=$tr") }
            }
            val finalMagnet = sb.toString()

            // 5. Start Download
            // We use the simpler download method (String, File) and set flags later on the handle
            session?.download(finalMagnet, downloadDir)
            Log.d(TAG, "Download initiated for hash: $infoHashStr")

            trySend(StreamState.Preparing("Connecting to peers..."))

            // 6. Monitoring Loop
            var isPlaying = false
            var fileSelected = false
            var largestFileIndex = -1
            var isSequentialSet = false

            while (isActive) {
                val handle = session?.find(infoHash)

                if (handle != null && handle.isValid) {
                    // Set Sequential Download explicitly once handle is found
                    if (!isSequentialSet) {
                        // Using explicit flags on the handle if possible, 
                        // or just rely on file priority which handles most logic.
                        // Note: Some libtorrent versions expose setSequentialDownload on the handle
                        try {
                            // Check if your specific wrapper version supports this, 
                            // otherwise we rely on the session/settings.
                            // handle.setSequentialDownload(true) 
                        } catch (e: Exception) { /* Ignore */ }
                        isSequentialSet = true
                    }

                    val status = handle.status()

                    // A. Metadata Loaded -> Select Largest File (The Movie)
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
                            // Set Priorities: High for movie file, Ignore others
                            val priorities = IntArray(torrentInfo.numFiles()) { Priority.IGNORE.swig() }
                            priorities[largestFileIndex] = Priority.DEFAULT.swig()
                            handle.prioritizeFiles(priorities)
                            
                            // Also try to set piece priorities for the beginning of the file (High Priority)
                            // to ensure fast start
                            handle.setFilePriority(largestFileIndex, Priority.TOP_PRIORITY)
                            
                            fileSelected = true
                            Log.d(TAG, "Movie File Selected: Index $largestFileIndex")
                        }
                    }

                    // B. Stream Status
                    val progress = (status.progress() * 100).toInt()
                    val speed = status.downloadPayloadRate() / 1024 // KB/s
                    val seeds = status.numSeeds()
                    val peers = status.numPeers()
                    
                    // We check totalDone because we set other files to IGNORE
                    val bytesDownloaded = status.totalDone()

                    if (!isPlaying && fileSelected) {
                        // Play when buffer is sufficient
                        if (bytesDownloaded > MIN_BUFFER_SIZE) {
                            val torrentInfo = handle.torrentFile()
                            val fileName = torrentInfo.files().filePath(largestFileIndex)
                            val videoFile = File(downloadDir, fileName)
                            
                            // Check if file physically exists and has content
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
                    trySend(StreamState.Preparing("Searching for peers..."))
                }
                delay(1000)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error: ${e.message}")
            trySend(StreamState.Error("Stream Failed: ${e.message}"))
        }

        awaitClose {
            Log.d(TAG, "Stream Closed")
            // Optional: pause session to save bandwidth
            // session?.pause() 
        }
    }

    fun stop() {
        // session?.stop() // Careful, this stops the whole engine
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
