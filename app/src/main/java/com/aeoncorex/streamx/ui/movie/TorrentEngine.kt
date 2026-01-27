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
import org.libtorrent4j.swig.sha1_hash
import org.libtorrent4j.swig.torrent_handle
import org.libtorrent4j.swig.torrent_status
import org.libtorrent4j.swig.torrent_info
import org.libtorrent4j.swig.int_vector
import org.libtorrent4j.swig.file_storage
import java.io.File

object TorrentEngine {
    private var session: SessionManager? = null
    private const val TAG = "TorrentEngine"
    
    // Minimum buffer size for streaming (30 MB)
    private const val MIN_BUFFER_SIZE = 30L * 1024 * 1024 

    fun start(context: Context, magnetLink: String): Flow<StreamState> = callbackFlow {
        try {
            // 1. Directory Setup
            val rootDir = context.externalCacheDir ?: context.cacheDir
            val downloadDir = File(rootDir, "StreamX_Temp")
            if (!downloadDir.exists()) downloadDir.mkdirs()

            // 2. Session Initialization
            if (session == null) {
                session = SessionManager()
                session?.start()
                
                // Settings Pack
                val sp = settings_pack()
                sp.set_int(settings_pack.int_types.active_downloads.swigValue(), 4)
                sp.set_bool(settings_pack.bool_types.enable_dht.swigValue(), true)
                sp.set_str(settings_pack.string_types.dht_bootstrap_nodes.swigValue(), "router.bittorrent.com:6881")
                
                session?.swig()?.apply_settings(sp)
            }

            Log.d(TAG, "Starting Engine for: $magnetLink")
            
            // 3. Magnet Parsing
            val ec = error_code()
            val params = libtorrent.parse_magnet_uri(magnetLink, ec)
            
            if (ec.value() != 0) {
                trySend(StreamState.Error("Invalid Magnet Link: ${ec.message()}"))
                close()
                return@callbackFlow
            }

            // Set save path via property
            params.save_path = downloadDir.absolutePath

            // Explicit type for info hash
            val targetInfoHash: sha1_hash = params.info_hashes.v1
            
            session?.swig()?.async_add_torrent(params)

            // 5. Monitoring Loop
            while (isActive) {
                val handle: torrent_handle? = session?.swig()?.find_torrent(targetInfoHash)
                
                if (handle != null && handle.is_valid) {
                    val status: torrent_status = handle.status()
                    
                    val progress = status.progress
                    val seeds = status.num_seeds
                    val peers = status.num_peers
                    val speed = status.download_payload_rate.toLong()
                    val totalDone = status.total_done
                    
                    if (status.has_metadata) {
                        // FIX: Simplified retrieval. If it fails, it throws or returns null implicitly handled.
                        // We use the 'files()' method which returns file_storage.
                        val torrentInfo = handle.torrent_file_ptr()

                        if (torrentInfo != null && torrentInfo.is_valid) {
                            val fs: file_storage = torrentInfo.files()
                            val numFiles = fs.num_files()
                            
                            var largestFileIndex = -1
                            var largestSize = 0L

                            // Find the largest file (The Movie)
                            for (i in 0 until numFiles) {
                                val fileSize = fs.file_size(i)
                                if (fileSize > largestSize) {
                                    largestSize = fileSize
                                    largestFileIndex = i
                                }
                            }

                            if (largestFileIndex != -1) {
                                // FIX: Loop through files and set priority individually using 'file_priority(index, priority)'
                                // This replaces the problematic 'prioritize_files(vector)' and works on all versions.
                                for (i in 0 until numFiles) {
                                    val priority = if (i == largestFileIndex) 7 else 0
                                    handle.file_priority(i, priority)
                                }
                                
                                val filePath = File(downloadDir, fs.file_path(largestFileIndex)).absolutePath
                                
                                // Buffer Check
                                if (totalDone > MIN_BUFFER_SIZE || progress >= 1.0f) {
                                    trySend(StreamState.Ready(filePath))
                                } else {
                                    trySend(StreamState.Buffering((progress * 100).toInt(), speed, seeds, peers))
                                }
                            }
                        } else {
                             trySend(StreamState.Buffering((progress * 100).toInt(), speed, seeds, peers))
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
                    val ec = error_code()
                    val p = libtorrent.parse_magnet_uri(magnetLink, ec)
                    
                    if (ec.value() == 0) {
                        val h = session?.swig()?.find_torrent(p.info_hashes.v1)
                        if (h != null && h.is_valid) {
                            session?.swig()?.remove_torrent(h)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error removing torrent: ${e.message}")
            }
        }
    }

    fun stop() {
        // Session is kept alive for performance
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
