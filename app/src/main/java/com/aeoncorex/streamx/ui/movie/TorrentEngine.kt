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
import org.libtorrent4j.swig.sha1_hash
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
                
                val settings = SettingsPack()
                settings.setInteger(settings_pack.int_types.active_downloads.swigValue(), 4)
                settings.setBoolean(settings_pack.bool_types.enable_dht.swigValue(), true)
                settings.setString(settings_pack.string_types.listen_interfaces.swigValue(), "0.0.0.0:0")
                settings.setInteger(settings_pack.int_types.download_rate_limit.swigValue(), 0)
                settings.setInteger(settings_pack.int_types.upload_rate_limit.swigValue(), 0)
                settings.setInteger(settings_pack.int_types.connections_limit.swigValue(), 200)

                val sessionParams = SessionParams(settings)
                
                // Safe call to start
                session?.let { mgr ->
                    if (!mgr.isRunning) {
                        mgr.start(sessionParams)
                    }
                }
            }

            // ৩. টরেন্ট প্যারামিটার পার্সিং
            val params = AddTorrentParams.parseMagnetUri(magnetLink)
            params.savePath(downloadDir.absolutePath)
            
            // FIX: v1 Hash (SHA1) বের করা - এটি find_torrent এর অ্যামবিগুইটি দূর করবে
            // Libtorrent 2.x এ info_hashes().v1 সরাসরি sha1_hash দেয়
            val infoHash: sha1_hash = params.info_hashes().v1
            
            // SessionManager এর ভেতর থেকে নেটিভ হ্যান্ডেল বের করা
            var handle = session?.swig()?.find_torrent(infoHash)
            
            if (handle == null || !handle.isValid) {
                // FIX: Add torrent directly via SWIG to get handle immediately
                session?.swig()?.add_torrent(params)
                
                // হ্যান্ডেলের জন্য অপেক্ষা
                var retries = 0
                while (retries < 10 && (handle == null || !handle.isValid)) {
                    delay(200)
                    handle = session?.swig()?.find_torrent(infoHash)
                    retries++
                }
            }

            if (handle != null) {
                // সিকুয়েন্সিয়াল ডাউনলোড অন করা
                handle.setSequentialDownload(true)
            }

            trySend(StreamState.Preparing("Metadata Fetching..."))

            var videoFile: File? = null
            var isReadyToPlay = false
            var isConfigured = false

            while (isActive) {
                if (handle == null || !handle.isValid) {
                    handle = session?.swig()?.find_torrent(infoHash)
                    if (handle == null || !handle.isValid) {
                         delay(1000)
                         continue 
                    }
                }

                val status = handle.status()
                val progress = (status.progress() * 100).toInt()
                val seeds = status.numSeeds()
                val peers = status.numPeers()
                val speed = status.downloadPayloadRate() / 1024 // KB/s
                val downloadedBytes = status.totalDone()

                if (handle.hasMetadata()) {
                    val ti = handle.torrentFile()

                    // ৪. সবচেয়ে বড় ফাইলটি (মুভি) খুঁজে বের করা
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
                            Log.d(TAG, "Target File: $filePath, Index: $videoIndex")
                            
                            if (!isConfigured) {
                                // FIX: Priority.TOP_PRIORITY ব্যবহার করা
                                val priorities = Array(ti.numFiles()) { Priority.IGNORE }
                                priorities[videoIndex] = Priority.TOP_PRIORITY // Changed from SEVEN to TOP_PRIORITY
                                handle.prioritizeFiles(priorities)
                                
                                val numPieces = ti.numPieces()
                                val startPiece = ti.mapFile(videoIndex, 0L, 1).piece
                                val endPiece = ti.mapFile(videoIndex, ti.files().fileSize(videoIndex) - 1, 1).piece
                                
                                for (i in startPiece until (startPiece + 15).coerceAtMost(numPieces)) {
                                    handle.setPiecePriority(i, Priority.TOP_PRIORITY)
                                    handle.setPieceDeadline(i, 1000)
                                }
                                for (i in (endPiece - 5).coerceAtLeast(0) until endPiece + 1) {
                                    handle.setPiecePriority(i, Priority.TOP_PRIORITY)
                                }
                                
                                isConfigured = true
                            }
                        }
                    }

                    // ৫. বাফারিং চেক
                    if (downloadedBytes >= MIN_BUFFER_SIZE && !isReadyToPlay) {
                        videoFile?.let {
                            if (it.exists() && it.length() > MIN_BUFFER_SIZE) {
                                isReadyToPlay = true
                                trySend(StreamState.Ready(it.absolutePath))
                            }
                        }
                    }

                    trySend(StreamState.Buffering(progress, speed.toLong(), seeds, peers))
                    
                } else {
                    trySend(StreamState.Preparing("Metadata: Connecting to $seeds seeds..."))
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
                // FIX: Public API ব্যবহার করে টরেন্ট রিমুভ করা
                if (session != null) {
                    val p = AddTorrentParams.parseMagnetUri(magnetLink)
                    val h = session?.swig()?.find_torrent(p.info_hashes().v1)
                    if (h != null && h.isValid) {
                        session?.remove(h)
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
