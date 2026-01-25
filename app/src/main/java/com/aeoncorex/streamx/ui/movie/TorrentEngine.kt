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
    
    // বাফারিং এর জন্য মিনিমাম সাইজ (৩০ মেগাবাইট)
    private const val MIN_BUFFER_SIZE = 30L * 1024 * 1024 

    fun start(context: Context, magnetLink: String): Flow<StreamState> = callbackFlow {
        try {
            // ১. ডিরেক্টরি সেটআপ
            val rootDir = context.externalCacheDir ?: context.cacheDir
            val downloadDir = File(rootDir, "StreamX_Temp")
            if (!downloadDir.exists()) downloadDir.mkdirs()

            // ২. সেশন ইনিশিয়ালিজেশন
            if (session == null) {
                session = SessionManager()
                session?.start()
                
                // Settings Pack (SWIG style)
                val sp = settings_pack()
                sp.set_int(settings_pack.int_types.active_downloads.swigValue(), 4)
                sp.set_bool(settings_pack.bool_types.enable_dht.swigValue(), true)
                sp.set_str(settings_pack.string_types.dht_bootstrap_nodes.swigValue(), "router.bittorrent.com:6881")
                
                session?.swig()?.apply_settings(sp)
            }

            Log.d(TAG, "Starting Engine for: $magnetLink")
            
            // ৩. Magnet Parsing (SWIG Correct Way)
            val ec = error_code()
            // আপনার দেওয়া ফাইল অনুযায়ী, এটি একটি গ্লোবাল ফাংশন যা params রিটার্ন করে
            val params = libtorrent.parse_magnet_uri(magnetLink, ec)
            
            if (ec.value() != 0) {
                trySend(StreamState.Error("Invalid Magnet Link: ${ec.message()}"))
                close()
                return@callbackFlow
            }

            // Save Path সেট করা
            params.set_save_path(downloadDir.absolutePath)
            
            // ৪. ডাউনলোডে যোগ করা (SessionManager এর বদলে সরাসরি SWIG session ব্যবহার)
            session?.swig()?.async_add_torrent(params)

            // ৫. মনিটরিং লুপ
            while (isActive) {
                val handle = session?.swig()?.find_torrent(params.info_hash())
                
                if (handle != null && handle.is_valid()) {
                    val status = handle.status()
                    
                    // SWIG methods are usually snake_case
                    val progress = status.progress()
                    val seeds = status.num_seeds()
                    val peers = status.num_peers()
                    val speed = status.download_payload_rate().toLong()
                    val totalDone = status.total_done()
                    
                    // Streaming এর জন্য Sequential Download জরুরি
                    handle.set_sequential_download(true)

                    if (handle.has_metadata()) {
                        val torrentInfo = handle.torrent_file()
                        val numFiles = torrentInfo.num_files()
                        var largestFileIndex = -1
                        var largestSize = 0L

                        // সবচেয়ে বড় ফাইল খুঁজে বের করা (মুভি ফাইল)
                        for (i in 0 until numFiles) {
                            val fileSize = torrentInfo.files().file_size(i)
                            if (fileSize > largestSize) {
                                largestSize = fileSize
                                largestFileIndex = i
                            }
                        }

                        if (largestFileIndex != -1) {
                            // শুধু মুভি ফাইলটি হাই প্রায়োরিটি দেওয়া (7 = Top Priority)
                            handle.file_priority(largestFileIndex, 7)
                            
                            // বাকি সব ফাইল ইগনোর করা (0 = Do not download)
                            for (i in 0 until numFiles) {
                                if (i != largestFileIndex) {
                                    handle.file_priority(i, 0)
                                }
                            }
                            
                            val filePath = File(downloadDir, torrentInfo.files().file_path(largestFileIndex)).absolutePath
                            
                            // বাফারিং লজিক
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
                    // রিমুভ করার জন্য হ্যাশ দরকার, তাই আবার পার্স করা হচ্ছে
                    val p = libtorrent.parse_magnet_uri(magnetLink, ec)
                    
                    if (ec.value() == 0) {
                        val h = session?.swig()?.find_torrent(p.info_hash())
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
