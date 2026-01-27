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
import org.libtorrent4j.swig.int_vector // Required for file priorities
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

            // FIX: Use property assignment for save_path (mapped to save_path(String) setter)
            params.save_path = downloadDir.absolutePath

            // FIX: Explicitly type sha1_hash to avoid ambiguity
            val targetInfoHash: sha1_hash = params.info_hashes.v1
            
            // Note: sequential_download is handled via flags usually, but we will rely on piece priority
            // automatically handled by libtorrent or the file priority logic below.
            
            session?.swig()?.async_add_torrent(params)

            // 5. Monitoring Loop
            while (isActive) {
                val handle: torrent_handle? = session?.swig()?.find_torrent(targetInfoHash)
                
                if (handle != null && handle.is_valid) {
                    val status: torrent_status = handle.status()
                    
                    // FIX: 'status' fields are properties (Direct field access in SWIG)
                    val progress = status.progress
                    val seeds = status.num_seeds
                    val peers = status.num_peers
                    val speed = status.download_payload_rate.toLong()
                    val totalDone = status.total_done
                    
                    // FIX: has_metadata is a property on status
                    if (status.has_metadata) {
                        // FIX: torrent_file() is a METHOD on handle
                        val torrentInfo: torrent_info = handle.torrent_file()
                        val numFiles = torrentInfo.num_files()
                        var largestFileIndex = -1
                        var largestSize = 0L

                        // Find the largest file (The Movie)
                        for (i in 0 until numFiles) {
                            // FIX: files() is a method returning file_storage, file_size(i) is a method
                            val fileSize = torrentInfo.files().file_size(i)
                            if (fileSize > largestSize) {
                                largestSize = fileSize
                                largestFileIndex = i
                            }
                        }

                        if (largestFileIndex != -1) {
                            // FIX: Use int_vector for setting priorities (modern libtorrent way)
                            val priorities = int_vector()
                            for (i in 0 until numFiles) {
                                if (i == largestFileIndex) {
                                    priorities.push_back(7) // Top Priority
                                } else {
                                    priorities.push_back(0) // Do not download
                                }
                            }
                            
                            // Apply priorities
                            handle.prioritize_files(priorities)
                            
                            val filePath = File(downloadDir, torrentInfo.files().file_path(largestFileIndex)).absolutePath
                            
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
                            // FIX: Use swig remove_torrent
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
