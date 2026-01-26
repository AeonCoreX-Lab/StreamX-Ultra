package com.aeoncorex.streamx.ui.movie

import android.content.Context
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import org.libtorrent4j.SessionManager
import org.libtorrent4j.swig.add_torrent_params
import org.libtorrent4j.swig.libtorrent
import org.libtorrent4j.swig.settings_pack
import org.libtorrent4j.swig.error_code
import java.io.File

object TorrentEngine {
    private var session: SessionManager? = null
    private const val TAG = "TorrentEngine"
    
    // Buffer minimum size (30MB)
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
                session?.start()
                
                // Settings Pack
                val sp = settings_pack()
                sp.set_int(settings_pack.int_types.alert_mask.swigValue(), 0) // Disable alerts for performance
                sp.set_int(settings_pack.int_types.active_downloads.swigValue(), 1)
                sp.set_int(settings_pack.int_types.connections_limit.swigValue(), 200)
                sp.set_bool(settings_pack.bool_types.enable_dht.swigValue(), true)
                session?.swig()?.apply_settings(sp)
            }

            // 3. Parse Magnet
            val ec = error_code()
            val p = libtorrent.parse_magnet_uri(magnetLink, ec)

            if (ec.value() != 0) {
                trySend(StreamState.Error("Invalid Magnet Link"))
                close()
                return@callbackFlow
            }

            // FIX 1: set_save_path -> setSave_path
            p.setSave_path(downloadDir.absolutePath)
            
            // Configure params
            // FIX: Using swig generated setters/getters if needed, or default params are usually fine.
            // p.flags is a bitfield, usually set via params in modern libtorrent, 
            // but parse_magnet_uri handles most.
            
            // 4. Add Torrent
            // FIX 2: info_hash() -> getInfo_hashes().getV1()
            // We check if it already exists to avoid duplication errors
            var handle = session?.swig()?.find_torrent(p.getInfo_hashes().getV1())

            if (handle == null || !handle.is_valid()) {
                session?.swig()?.async_add_torrent(p)
                // We need to wait a bit for the handle to become valid or use the alert loop
                // For simplicity in this flow, we'll poll briefly
                var retries = 0
                while (retries < 10) {
                    handle = session?.swig()?.find_torrent(p.getInfo_hashes().getV1())
                    if (handle != null && handle.is_valid()) break
                    delay(500)
                    retries++
                }
            }

            if (handle == null || !handle.is_valid()) {
                trySend(StreamState.Error("Failed to add torrent"))
                close()
                return@callbackFlow
            }

            // 5. Monitoring Loop
            while (isActive) {
                val status = handle.status()
                val state = status.state()
                
                // Progress
                val progress = (status.progress() * 100).toInt()
                val seeds = status.num_seeds()
                val peers = status.num_peers()
                val speed = status.download_rate().toLong()
                val downloaded = status.total_done()

                // Sequential Download (Critical for streaming)
                handle.set_sequential_download(true)
                
                // Prioritize first and last pieces (Pseudo-buffering)
                // This logic can be enhanced, but sequential is main key

                if (downloaded > MIN_BUFFER_SIZE || progress > 5) {
                    // Find the largest file (the movie)
                    // Note: This requires torrent_info to be ready (metadata downloaded)
                    if (status.has_metadata()) {
                        val ti = handle.torrent_file()
                        if (ti != null) {
                            val numFiles = ti.num_files()
                            var largestFileIndex = 0
                            var largestSize = 0L
                            
                            for (i in 0 until numFiles) {
                                val fileSize = ti.files().file_size(i)
                                if (fileSize > largestSize) {
                                    largestSize = fileSize
                                    largestFileIndex = i
                                }
                            }
                            
                            val fileName = ti.files().file_name(largestFileIndex)
                            val filePath = File(downloadDir, fileName).absolutePath
                            
                            // Check if file physically exists and has some data
                            val fileObj = File(filePath)
                            if (fileObj.exists() && fileObj.length() > MIN_BUFFER_SIZE) {
                                trySend(StreamState.Ready(filePath))
                            } else {
                                trySend(StreamState.Buffering(progress, speed, seeds, peers))
                            }
                        }
                    } else {
                         trySend(StreamState.Preparing("Downloading Metadata... $progress%"))
                    }
                } else {
                    trySend(StreamState.Buffering(progress, speed, seeds, peers))
                }
                
                if (status.errc().value() != 0) {
                    trySend(StreamState.Error("Torrent Error: ${status.errc().message()}"))
                }
                
                if (seeds == 0 && peers == 0 && progress == 0) {
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
                    val ec = error_code()
                    // Re-parse to get the hash for removal
                    val p = libtorrent.parse_magnet_uri(magnetLink, ec)
                    
                    if (ec.value() == 0) {
                        // FIX 3: info_hash() -> getInfo_hashes().getV1()
                        val h = session?.swig()?.find_torrent(p.getInfo_hashes().getV1())
                        if (h != null && h.is_valid()) {
                            session?.remove(h)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error removing torrent: ${e.message}")
            }
        }
    }

    fun stop() {
        // Session kept alive specifically
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
