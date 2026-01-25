package com.aeoncorex.streamx.ui.movie

import android.content.Context
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import org.libtorrent4j.*
import org.libtorrent4j.swig.settings_pack
import java.io.File

object TorrentEngine {
    private var session: SessionManager? = null
    private const val TAG = "TorrentEngine"

    // User request: Increased buffer to 10MB for stability
    private const val MIN_BUFFER_SIZE = 10L * 1024 * 1024 

    fun start(context: Context, magnetLink: String): Flow<StreamState> = callbackFlow {
        try {
            // 1. Setup Directories
            val rootDir = context.externalCacheDir ?: context.cacheDir
            val downloadDir = File(rootDir, "StreamX_Temp")
            if (!downloadDir.exists()) downloadDir.mkdirs()

            // 2. Initialize LibTorrent Session
            if (session == null) {
                session = SessionManager()
                val settings = SettingsPack()
                settings.setInteger(settings_pack.int_types.active_downloads.swigValue(), 4)
                settings.setBoolean(settings_pack.bool_types.enable_dht.swigValue(), true)
                settings.setString(settings_pack.string_types.listen_interfaces.swigValue(), "0.0.0.0:6881")
                // Increase download rate limits (0 = infinite)
                settings.setInteger(settings_pack.int_types.download_rate_limit.swigValue(), 0)
                session?.start(settings)
            }

            // 3. Add Torrent
            val params = AddTorrentParams.parseMagnetUri(magnetLink)
            params.savePath(downloadDir.absolutePath)
            
            // Allow more strict checking of the Magnet URI
            val handle = session!!.addTorrent(params)

            // --- CRITICAL FIX: SEQUENTIAL DOWNLOAD ---
            // This forces the torrent to download pieces in order (1, 2, 3...)
            // Essential for video streaming.
            handle.setSequentialDownload(true)

            trySend(StreamState.Preparing("Fetching Metadata..."))

            var videoFile: File? = null
            var isReadyToPlay = false
            var prioritiesSet = false

            while (isActive) {
                val status = handle.status()
                val progress = (status.progress() * 100).toInt()
                val seeds = status.numSeeds()
                val peers = status.numPeers()
                val speed = status.downloadPayloadRate() / 1024 // KB/s

                if (handle.hasMetadata()) {
                    val ti = handle.torrentFile()

                    // 4. Identify the Largest File (The Movie)
                    if (videoFile == null) {
                        var largestFileSize = 0L
                        var videoIndex = -1

                        for (i in 0 until ti.numFiles()) {
                            val fileSize = ti.files().fileSize(i)
                            if (fileSize > largestFileSize) {
                                largestFileSize = fileSize
                                videoIndex = i
                            }
                        }

                        if (videoIndex != -1) {
                            val filePath = ti.files().filePath(videoIndex)
                            videoFile = File(downloadDir, filePath)
                            Log.d(TAG, "Target File: $filePath")
                        }
                    }

                    // 5. Set Priorities (Header & Footer)
                    // We need the start and end of the file for the player to read metadata correctly.
                    if (!prioritiesSet && videoFile != null) {
                        val numPieces = ti.numPieces()
                        // Prioritize first 10 pieces (Header)
                        for (i in 0 until 10.coerceAtMost(numPieces)) {
                            handle.setPiecePriority(i, Priority.SEVEN)
                        }
                        // Prioritize last 5 pieces (Footer/Moov Atom)
                        for (i in (numPieces - 5).coerceAtLeast(0) until numPieces) {
                            handle.setPiecePriority(i, Priority.SEVEN)
                        }
                        // Set the rest to Normal priority but Sequential mode handles the order
                        prioritiesSet = true
                    }

                    // 6. Check Ready State
                    val downloadedBytes = status.totalDone()
                    
                    // If we have enough data (10MB) AND the file actually exists on disk
                    if (downloadedBytes >= MIN_BUFFER_SIZE && !isReadyToPlay) {
                        videoFile?.let {
                            if (it.exists() && it.length() > 0) {
                                isReadyToPlay = true
                                trySend(StreamState.Ready(it.absolutePath))
                            }
                        }
                    }

                    // Update UI
                    if (!isReadyToPlay) {
                        trySend(StreamState.Buffering(progress, speed.toLong(), seeds, peers))
                    } else {
                        // Keep sending stats even while playing
                        trySend(StreamState.Buffering(progress, speed.toLong(), seeds, peers))
                    }
                } else {
                    trySend(StreamState.Preparing("Connecting to $seeds seeds..."))
                }

                delay(1000)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error: ${e.message}")
            trySend(StreamState.Error("Stream Failed: ${e.message}"))
        }

        awaitClose {
            Log.d(TAG, "Stopping Session")
            // Clean up: Remove torrent to stop background downloading when user exits
            // But keep session alive for DHT nodes if needed later
            val handle = session?.findTorrent(AddTorrentParams.parseMagnetUri(magnetLink).infoHash)
            if (handle != null && handle.isValid) {
                session?.remove(handle)
            }
        }
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
