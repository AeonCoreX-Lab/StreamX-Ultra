package com.aeoncorex.streamx.ui.movie

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import org.libtorrent4j.AddTorrentParams
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.swig.settings_pack
import java.io.File

object TorrentEngine {
    private var session: SessionManager? = null
    private const val TAG = "TorrentEngine"

    // ৩ মেগাবাইট বাফার হলেই ভিডিও প্লে শুরু হবে
    private const val MIN_BUFFER_SIZE = 3L * 1024 * 1024 

    // অতিরিক্ত ট্র্যাকার (ডাউনলোড স্পিড বাড়ানোর জন্য)
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
            // ১. ক্যাশ ডিরেক্টরি সেটআপ (গ্যালারিতে যাবে না)
            // externalCacheDir ব্যবহার করলে অ্যান্ড্রয়েড এটিকে "Temporary" ফাইল হিসেবে দেখে এবং গ্যালারিতে স্ক্যান করে না।
            val rootDir = context.externalCacheDir ?: context.cacheDir
            val downloadDir = File(rootDir, "StreamX_Temp")
            
            if (!downloadDir.exists()) downloadDir.mkdirs()

            // ২. সেশন কনফিগারেশন এবং স্টার্ট
            if (session == null) {
                session = SessionManager()
                val settings = SettingsPack()
                
                // নেটওয়ার্ক অপ্টিমাইজেশন এবং DHT অন করা (খুব গুরুত্বপূর্ণ)
                settings.setInteger(settings_pack.int_types.active_downloads.swigValue(), 4)
                settings.setBoolean(settings_pack.bool_types.enable_dht.swigValue(), true) 
                settings.setBoolean(settings_pack.bool_types.enable_lsd.swigValue(), true)
                settings.setInteger(settings_pack.int_types.connections_limit.swigValue(), 200)
                
                session?.applySettings(settings)
                session?.start()
                Log.d(TAG, "Torrent Session Started with DHT")
            }

            // ৩. ম্যাগনেট লিঙ্কে ট্র্যাকার যুক্ত করা (যদি না থাকে)
            var finalMagnet = magnetLink
            if (!magnetLink.contains("tr=")) {
                val sb = StringBuilder(magnetLink)
                TRACKERS.forEach { tr -> sb.append("&tr=$tr") }
                finalMagnet = sb.toString()
            }

            // ৪. টরেন্ট প্যারামিটার সেটআপ
            val p = AddTorrentParams.parseMagnetUri(finalMagnet)
            p.savePath(downloadDir.absolutePath)
            
            // Sequential Download: ভিডিওর শুরুর অংশ আগে নামবে
            val flags = p.flags()
            flags.and_(TorrentFlags.SEQUENTIAL_DOWNLOAD) 
            p.flags(flags)

            session?.download(p)
            Log.d(TAG, "Downloading started at: ${downloadDir.absolutePath}")

            trySend(StreamState.Preparing("Connecting to peers..."))

            // ৫. মনিটরিং লুপ
            var isPlaying = false
            var fileSelected = false
            var largestFileIndex = -1

            while (isActive) {
                val handle = session?.find(p.infoHash())
                
                if (handle != null && handle.isValid) {
                    val status = handle.status()
                    
                    // মেটাডেটা লোড হওয়ার পর মুভি ফাইল সিলেক্ট করা
                    if (!fileSelected && status.hasMetadata()) {
                        val torrentInfo = handle.torrentFile()
                        
                        // সবচেয়ে বড় ফাইলটি খুঁজে বের করা (এটাই মুভি)
                        var maxFileSize = 0L
                        for (i in 0 until torrentInfo.numFiles()) {
                            val fileSize = torrentInfo.files().fileSize(i)
                            if (fileSize > maxFileSize) {
                                maxFileSize = fileSize
                                largestFileIndex = i
                            }
                        }

                        if (largestFileIndex != -1) {
                            // শুধু মুভি ফাইলটির প্রায়োরিটি হাই করা, বাকিগুলো ইগনোর
                            val priorities = Array(torrentInfo.numFiles()) { Priority.IGNORE }
                            priorities[largestFileIndex] = Priority.SEVEN // Highest priority
                            handle.filePriority(priorities)
                            fileSelected = true
                            Log.d(TAG, "Movie File Selected: Index $largestFileIndex")
                        }
                    }

                    // স্ট্যাটাস আপডেট
                    val progress = (status.progress() * 100).toInt()
                    val speed = status.downloadPayloadRate() / 1024 // KB/s
                    val seeds = status.numSeeds()
                    val peers = status.numPeers()
                    
                    // বাফার চেক: শুধুমাত্র মুভি ফাইলের কতটুকু নামল
                    val bytesDownloaded = if (largestFileIndex != -1) status.totalDone() else 0L

                    if (!isPlaying && fileSelected) {
                        // বাফার ৩ মেগাবাইট পার হলে অথবা ১% ডাউনলোড হলে প্লে হবে
                        val isReadyToPlay = bytesDownloaded > MIN_BUFFER_SIZE
                        
                        if (isReadyToPlay) {
                            val torrentInfo = handle.torrentFile()
                            val fileName = torrentInfo.files().filePath(largestFileIndex)
                            val videoFile = File(downloadDir, fileName)
                            
                            if (videoFile.exists()) {
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
                        trySend(StreamState.Preparing("Fetching Metadata... Seeds: $seeds"))
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
            stop() 
        }
    }

    fun stop() {
        // অ্যাপ বন্ধ হলে সেশন পজ করা যেতে পারে, তবে স্টোরেজ ক্লিন করার জন্য আলাদা ফাংশন রাখা ভালো
        // session?.pause() 
    }
    
    // ক্যাশ ক্লিয়ার করার জন্য এই ফাংশনটি অ্যাপ এক্সিট করার সময় কল করতে পারেন
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
