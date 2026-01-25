package com.aeoncorex.streamx.ui.movie

import android.content.Context
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.swig.add_torrent_params
import org.libtorrent4j.swig.libtorrent
import org.libtorrent4j.swig.settings_pack
import org.libtorrent4j.swig.error_code
import java.io.File

object TorrentEngine {
    private var session: SessionManager? = null
    private const val TAG = "TorrentEngine"
    
    // Buffering minimum size (30 MB)
    private const val MIN_BUFFER_SIZE = 30L * 1024 * 1024 

    fun start(context: Context, magnetLink: String): Flow<StreamState> = callbackFlow {
        try {
            // 1. Setup Directory
            val rootDir = context.externalCacheDir ?: context.cacheDir
            val downloadDir = File(rootDir, "StreamX_Temp")
            if (!downloadDir.exists()) downloadDir.mkdirs()

            // 2. Initialize Session
            if (session == null) {
                session = SessionManager()
                
                val settings = SettingsPack()
                settings.setInteger(settings_pack.int_types.active_downloads.swigValue(), 4)
                settings.setBoolean(settings_pack.bool_types.enable_dht.swigValue(), true)
                settings.setString(settings_pack.string_types.dht_bootstrap_nodes.swigValue(), "router.bittorrent.com:6881")
                
                session?.apply(settings)
                session?.start()
            }

            // 3. Parse Magnet and Start Download
            Log.d(TAG, "Starting Engine for: $magnetLink")
            
            // Correct way to parse magnet in libtorrent4j
            val ec = error_code()
            val params = add_torrent_params.create_instance()
            libtorrent.parse_magnet_uri(magnetLink, params, ec)
            
            if (ec.value() != 0) {
                trySend(StreamState.Error("Invalid Magnet Link: ${ec.message()}"))
                close()
                return@callbackFlow
            }

            params.set_save_path(downloadDir.absolutePath)
            
            // Set flags for sequential download (vital for streaming)
            // Note: flags might be a Long or specialized type depending on swig version, 
            // but usually setting specific flags on params is done via bitmask or helper methods.
            // For libtorrent 1.2/2.0+ via libtorrent4j, we often set it on the handle later.

            // Download using the session manager helper to ensure it's tracked
            session?.download(params)

            // 4. Monitoring Loop
            while (isActive) {
                // Find the torrent handle using the info hash from params
                val handle = session?.swig()?.find_torrent(params.info_hash())
                
                if (handle != null && handle.is_valid()) {
                    val status = handle.status()
                    
                    // Correct properties access (snake_case methods)
                    val progress = status.progress() // 0.0 to 1.0
                    val seeds = status.num_seeds()
                    val peers = status.num_peers()
                    val speed = status.download_payload_rate().toLong()
                    val totalDone = status.total_done()
                    
                    // Force sequential download if not already set
                    // Sequential download is critical for streaming
                    handle.set_sequential_download(true)

                    // Check if we have enough metadata and file structure
                    if (handle.has_metadata()) {
                        val torrentInfo = handle.torrent_file()
                        
                        // Prioritize largest file (likely the movie)
                        val numFiles = torrentInfo.num_files()
                        var largestFileIndex = -1
                        var largestSize = 0L

                        for (i in 0 until numFiles) {
                            val fileSize = torrentInfo.files().file_size(i)
                            if (fileSize > largestSize) {
                                largestSize = fileSize
                                largestFileIndex = i
                            }
                        }

                        // Prioritize the largest file, ignore others
                        if (largestFileIndex != -1) {
                            handle.file_priority(largestFileIndex, 7) // 7 is top priority
                            
                            // Set 0 priority (do not download) for others to save bandwidth
                            for (i in 0 until numFiles) {
                                if (i != largestFileIndex) {
                                    handle.file_priority(i, 0)
                                }
                            }
                            
                            // Get path to the video file
                            val filePath = File(downloadDir, torrentInfo.files().file_path(largestFileIndex)).absolutePath
                            
                            // Streaming Logic
                            if (totalDone > MIN_BUFFER_SIZE || progress >= 1.0f) {
                                trySend(StreamState.Ready(filePath))
                            } else {
                                // Multiply progress by 100 for percentage
                                trySend(StreamState.Buffering((progress * 100).toInt(), speed, seeds, peers))
                            }
                        }
                    } else {
                         trySend(StreamState.Buffering((progress * 100).toInt(), speed, seeds, peers))
                    }
                } else {
                    trySend(StreamState.Preparing("Metadata: Connecting to network..."))
                }

                delay(1000)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Critical Error: ${e.message}")
            trySend(StreamState.Error("Engine Failed: ${e.message}"))
        }

        awaitClose {
            Log.d(TAG, "Stopping Stream Session")
            try {
                if (session != null) {
                    // Re-parse magnet to get the hash for removal
                    val ec = error_code()
                    val p = add_torrent_params.create_instance()
                    libtorrent.parse_magnet_uri(magnetLink, p, ec)
                    
                    val h = session?.swig()?.find_torrent(p.info_hash())
                    if (h != null && h.is_valid()) {
                        session?.remove(h) // Correct usage for removing specific torrent
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error removing torrent: ${e.message}")
            }
        }
    }

    fun stop() {
        // Singleton session kept alive
    }

    fun clearCache(context: Context) {
        try {
            val rootDir = context.externalCacheDir ?: context.cacheDir
            val downloadDir = File(rootDir, "StreamX_Temp")
            if (downloadDir.exists()) {
                downloadDir.deleteRecursively()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Cache Clear Failed")
        }
    }
}
