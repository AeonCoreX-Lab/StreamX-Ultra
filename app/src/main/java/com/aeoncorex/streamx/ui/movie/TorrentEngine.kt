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
    
    // বাফারিং এর জন্য মিনিমাম সাইজ (৩০ মেগাবাইট - ভালো কোয়ালিটির জন্য একটু বাড়ানো হলো)
    private const val MIN_BUFFER_SIZE = 30L * 1024 * 1024 

    fun start(context: Context, magnetLink: String): Flow<StreamState> = callbackFlow {
        try {
            // ১. ডিরেক্টরি সেটআপ (App Cache - No Permission Needed)
            val rootDir = context.externalCacheDir ?: context.cacheDir
            val downloadDir = File(rootDir, "StreamX_Temp")
            if (!downloadDir.exists()) downloadDir.mkdirs()

            // ২. সেশন ইনিশিয়ালিজেশন (অপটিমাইজড সেটিংস সহ)
            if (session == null) {
                session = SessionManager()
                val settings = SettingsPack()
                
                settings.setInteger(settings_pack.int_types.active_downloads.swigValue(), 4)
                settings.setBoolean(settings_pack.bool_types.enable_dht.swigValue(), true)
                
                // --- ফিক্স: র‍্যান্ডম পোর্ট ব্যবহার (ব্লকিং এড়াতে) ---
                settings.setString(settings_pack.string_types.listen_interfaces.swigValue(), "0.0.0.0:0")
                
                // স্পিড লিমিট আনলক
                settings.setInteger(settings_pack.int_types.download_rate_limit.swigValue(), 0)
                settings.setInteger(settings_pack.int_types.upload_rate_limit.swigValue(), 0)
                
                // কানেকশন অপটিমাইজেশন
                settings.setInteger(settings_pack.int_types.connections_limit.swigValue(), 200)
                
                session?.start(settings)
            }

            // ৩. টরেন্ট যোগ করা
            val params = AddTorrentParams.parseMagnetUri(magnetLink)
            params.savePath(downloadDir.absolutePath)
            
            // ডুপ্লিকেট টরেন্ট চেক
            val infoHash = params.infoHash
            var handle = session?.findTorrent(infoHash)
            if (handle == null) {
                handle = session?.addTorrent(params)
            }

            // শুরুতেই সিকুয়েন্সিয়াল ডাউনলোড অন করা
            handle?.setSequentialDownload(true)

            trySend(StreamState.Preparing("Metadata Fetching..."))

            var videoFile: File? = null
            var isReadyToPlay = false
            var isConfigured = false

            while (isActive) {
                if (handle == null || !handle.isValid) {
                    trySend(StreamState.Error("Invalid Torrent Handle"))
                    break
                }

                val status = handle.status()
                val progress = (status.progress() * 100).toInt()
                val seeds = status.numSeeds()
                val peers = status.numPeers()
                val speed = status.downloadPayloadRate() / 1024 // KB/s
                val downloadedBytes = status.totalDone()

                // মেটাডেটা পাওয়ার পর আসল কনফিগারেশন
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
                            
                            // --- CRITICAL FIX: অন্য সব ফাইল ইগনোর করা ---
                            if (!isConfigured) {
                                val priorities = IntArray(ti.numFiles()) { 0 } // 0 = Ignore
                                priorities[videoIndex] = 7 // 7 = Top Priority for Video
                                handle.prioritizeFiles(priorities)
                                
                                // ভিডিওর শুরু (Header) এবং শেষ (Footer) অংশ আগে নামানো (প্লেয়ারের জন্য জরুরি)
                                val numPieces = ti.numPieces()
                                val startPiece = ti.mapFile(videoIndex, 0L, 1).piece
                                val endPiece = ti.mapFile(videoIndex, ti.files().fileSize(videoIndex) - 1, 1).piece
                                
                                // প্রথম ১০ পিস এবং শেষের ৫ পিস হাই প্রায়োরিটি
                                for (i in startPiece until (startPiece + 15).coerceAtMost(numPieces)) {
                                    handle.setPiecePriority(i, Priority.TOP_PRIORITY)
                                    handle.setPieceDeadline(i, 1000) // ১ সেকেন্ডের মধ্যে চাওয়ার চেষ্টা
                                }
                                for (i in (endPiece - 5).coerceAtLeast(0) until endPiece + 1) {
                                    handle.setPiecePriority(i, Priority.TOP_PRIORITY)
                                }
                                
                                isConfigured = true
                            }
                        }
                    }

                    // ৫. বাফারিং চেক (ফাইলের অস্তিত্ব এবং সাইজ)
                    if (downloadedBytes >= MIN_BUFFER_SIZE && !isReadyToPlay) {
                        videoFile?.let {
                            if (it.exists() && it.length() > MIN_BUFFER_SIZE) {
                                isReadyToPlay = true
                                trySend(StreamState.Ready(it.absolutePath))
                            }
                        }
                    }

                    // স্ট্যাটাস আপডেট
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
            // অ্যাপ বন্ধ করলে ডাউনলোড পজ করা বা রিমুভ করা
            // সেশন পুরোপুরি বন্ধ না করে শুধু টরেন্ট পজ করাই ভালো যাতে রিস্টার্টে দ্রুত হয়
            // তবে মেমোরি লিক এড়াতে রিমুভ অপশন রাখা হলো:
             val handle = session?.findTorrent(AddTorrentParams.parseMagnetUri(magnetLink).infoHash)
             if (handle != null) {
                 session?.remove(handle) 
             }
        }
    }

    fun stop() {
        // ম্যানুয়ালি সেশন ক্লিনআপ যদি লাগে
        // session?.stop() // সাধারণত দরকার নেই যদি সিঙ্গেলটন রাখো
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
