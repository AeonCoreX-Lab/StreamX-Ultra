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
    
    // বাফারিং এর জন্য মিনিমাম সাইজ (৩০ মেগাবাইট)
    private const val MIN_BUFFER_SIZE = 30L * 1024 * 1024 

    fun start(context: Context, magnetLink: String): Flow<StreamState> = callbackFlow {
        try {
            // ১. ডিরেক্টরি সেটআপ
            val rootDir = context.externalCacheDir ?: context.cacheDir
            val downloadDir = File(rootDir, "StreamX_Temp")
            if (!downloadDir.exists()) downloadDir.mkdirs()

            // ২. সেশন ইনিশিয়ালিজেশন (FIXED for Libtorrent4j 2.x)
            if (session == null) {
                session = SessionManager()
                
                val settings = SettingsPack()
                settings.setInteger(settings_pack.int_types.active_downloads.swigValue(), 4)
                settings.setBoolean(settings_pack.bool_types.enable_dht.swigValue(), true)
                settings.setString(settings_pack.string_types.listen_interfaces.swigValue(), "0.0.0.0:0")
                settings.setInteger(settings_pack.int_types.download_rate_limit.swigValue(), 0)
                settings.setInteger(settings_pack.int_types.upload_rate_limit.swigValue(), 0)
                settings.setInteger(settings_pack.int_types.connections_limit.swigValue(), 200)

                // FIX: SettingsPack কে SessionParams এ র‍্যাপ করা হয়েছে
                val sessionParams = SessionParams(settings)
                session?.start(sessionParams)
            }

            // ৩. টরেন্ট প্যারামিটার পার্সিং
            val params = AddTorrentParams.parseMagnetUri(magnetLink)
            params.savePath(downloadDir.absolutePath)
            
            // FIX: infoHash() মেথড কল এবং swig() এর ব্যবহার
            val infoHash = params.infoHash() 
            
            // SessionManager এর ভেতর থেকে নেটিভ হ্যান্ডেল (swig) বের করে টরেন্ট চেক করা
            var handle = session?.swig()?.find_torrent(infoHash)
            
            if (handle == null || !handle.isValid) {
                // টরেন্ট অ্যাড করা (সরাসরি নেটিভ মেথড দিয়ে যাতে হ্যান্ডেল সাথে সাথে পাওয়া যায়)
                // অথবা SessionManager.download(params) ব্যবহার করা যেত, কিন্তু আমরা হ্যান্ডেল রিটার্ন চাই
                session?.download(params)
                
                // একটু অপেক্ষা করা যাতে হ্যান্ডেলটি তৈরি হয়
                var retries = 0
                while (retries < 5 && (handle == null || !handle.isValid)) {
                    handle = session?.swig()?.find_torrent(infoHash)
                    if (handle == null) delay(200)
                    retries++
                }
            }

            if (handle != null) {
                // শুরুতেই সিকুয়েন্সিয়াল ডাউনলোড অন করা
                handle.setSequentialDownload(true)
            }

            trySend(StreamState.Preparing("Metadata Fetching..."))

            var videoFile: File? = null
            var isReadyToPlay = false
            var isConfigured = false

            while (isActive) {
                if (handle == null || !handle.isValid) {
                    // লুপের মধ্যে হ্যান্ডেল চেক (যদি অ্যাড হতে দেরি হয়)
                    handle = session?.swig()?.find_torrent(infoHash)
                    if (handle == null || !handle.isValid) {
                         // যদি অনেকক্ষণ চেষ্টার পরেও হ্যান্ডেল না পাওয়া যায়
                         // তবে অপেক্ষা করা হচ্ছে (Break না করে)
                         delay(1000)
                         continue 
                    }
                }

                // এখন handle safe
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
                                // FIX: Priority সেট করা (IntArray এর বদলে Priority Array ব্যবহার করা ভালো, 
                                // তবে লিবিটরেন্ট ভার্সন ভেদে IntArray কাজ করতে পারে, এখানে Priority Enum ব্যবহার করা হলো)
                                
                                val priorities = Array(ti.numFiles()) { Priority.IGNORE }
                                priorities[videoIndex] = Priority.SEVEN // High Priority
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

                    val msg = if (isReadyToPlay) "Streaming" else "Buffering"
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
            // ক্লিনআপ: টরেন্ট রিমুভ করা
            try {
                val p = AddTorrentParams.parseMagnetUri(magnetLink)
                // FIX: swig() ব্যবহার করে রিমুভ করা
                val h = session?.swig()?.find_torrent(p.infoHash())
                if (h != null && h.isValid) {
                    session?.session()?.removeTorrent(h)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error removing torrent: ${e.message}")
            }
        }
    }

    fun stop() {
        // সিঙ্গেলটন সেশন তাই স্টপ করার দরকার নেই যদি না অ্যাপ বন্ধ হয়
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
