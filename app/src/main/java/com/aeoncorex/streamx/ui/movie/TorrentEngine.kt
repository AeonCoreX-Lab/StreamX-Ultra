package com.aeoncorex.streamx.ui.movie

import android.content.Context
import android.os.Environment
import android.util.Log
import com.github.se_bastiaan.torrentstream.StreamStatus
import com.github.se_bastiaan.torrentstream.Torrent
import com.github.se_bastiaan.torrentstream.TorrentOptions
import com.github.se_bastiaan.torrentstream.TorrentStream
import com.github.se_bastiaan.torrentstream.listeners.TorrentListener
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.File

object TorrentEngine {
    private var torrentStream: TorrentStream? = null
    private var currentMagnet: String? = null

    fun init(context: Context) {
        if (torrentStream == null) {
            val options = TorrentOptions.Builder()
                .saveLocation(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES))
                .removeFilesAfterStop(true) // Cache clear after playing
                .build()
            
            torrentStream = TorrentStream.init(options)
        }
    }

    // Returns a Flow that emits the STREAMABLE URL when ready
    fun startStreaming(magnet: String): Flow<StreamState> = callbackFlow {
        if (torrentStream == null) {
            trySend(StreamState.Error("Engine not initialized"))
            close()
            return@callbackFlow
        }

        if (currentMagnet == magnet && torrentStream?.isStreaming == true) {
            // Already streaming this, just return current status if possible
            // But for simplicity, we restart for new requests
        }

        currentMagnet = magnet
        
        val listener = object : TorrentListener {
            override fun onStreamPrepared(torrent: Torrent) {
                Log.d("TorrentEngine", "Prepared")
                trySend(StreamState.Preparing("Buffering metadata..."))
                torrent.startDownload()
            }

            override fun onStreamStarted(torrent: Torrent) {
                Log.d("TorrentEngine", "Started")
                trySend(StreamState.Buffering(0))
            }

            override fun onStreamError(torrent: Torrent, e: Exception) {
                Log.e("TorrentEngine", "Error: ${e.message}")
                trySend(StreamState.Error(e.message ?: "Unknown Error"))
            }

            override fun onStreamReady(torrent: Torrent) {
                // The library creates a local file, we can stream it via FileStream or LocalServer
                // TorrentStream lib usually exposes the video file path.
                val videoFile = torrent.videoFile
                if (videoFile != null) {
                    Log.d("TorrentEngine", "Ready: ${videoFile.absolutePath}")
                    trySend(StreamState.Ready(videoFile.absolutePath))
                }
            }

            override fun onStreamProgress(torrent: Torrent, status: StreamStatus) {
                if (status.bufferProgress < 100) {
                    trySend(StreamState.Buffering(status.bufferProgress))
                }
            }
        }

        torrentStream?.addListener(listener)
        torrentStream?.startStream(magnet)

        awaitClose {
            torrentStream?.removeListener(listener)
            // We don't stop stream immediately on close to allow background play if needed,
            // but usually we should stop to save battery.
        }
    }

    fun stop() {
        torrentStream?.stopStream()
        currentMagnet = null
    }
}

sealed class StreamState {
    data class Preparing(val message: String) : StreamState()
    data class Buffering(val progress: Int) : StreamState()
    data class Ready(val filePath: String) : StreamState()
    data class Error(val message: String) : StreamState()
}
