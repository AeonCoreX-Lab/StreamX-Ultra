package com.aeoncorex.streamx.ui.music

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object MusicManager {
    private var exoPlayer: ExoPlayer? = null
    private var progressJob: Job? = null

    private val _currentSong = MutableStateFlow<MusicTrack?>(null)
    val currentSong = _currentSong.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying = _isPlaying.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition = _currentPosition.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration = _duration.asStateFlow()

    fun initialize(context: Context) {
        if (exoPlayer == null) {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build()

            exoPlayer = ExoPlayer.Builder(context).build().apply {
                setAudioAttributes(audioAttributes, true)
                repeatMode = Player.REPEAT_MODE_ALL
                
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(playing: Boolean) {
                        _isPlaying.value = playing
                        if (playing) startProgressUpdater() else stopProgressUpdater()
                    }
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY) {
                            _duration.value = duration
                        }
                    }
                })
            }
        }
    }

    fun play(track: MusicTrack) {
        if (track.streamUrl.isBlank()) return

        _currentSong.value = track
        exoPlayer?.apply {
            stop()
            clearMediaItems()
            // Uri.parse ব্যবহার করে নিশ্চিত করা হলো যাতে স্পেস বা ক্যারেক্টার সমস্যা না করে
            val mediaItem = MediaItem.fromUri(Uri.parse(track.streamUrl))
            setMediaItem(mediaItem)
            prepare()
            playWhenReady = true // সরাসরি প্লে করার জন্য ফোর্স করা হলো
        }
    }

    fun togglePlayPause() {
        exoPlayer?.let {
            if (it.isPlaying) it.pause() else it.play()
        }
    }

    // লাইভ টিভি বা অন্য প্লেয়ার চালু হলে এটি কল হবে
    fun pause() {
        exoPlayer?.pause()
        _isPlaying.value = false
        stopProgressUpdater()
    }

    fun seekTo(position: Long) {
        exoPlayer?.seekTo(position)
    }

    private fun startProgressUpdater() {
        progressJob?.cancel()
        progressJob = CoroutineScope(Dispatchers.Main).launch {
            while (isActive) {
                exoPlayer?.let { 
                    _currentPosition.value = it.currentPosition
                    if (_duration.value == 0L && it.duration > 0) {
                        _duration.value = it.duration
                    }
                }
                delay(1000)
            }
        }
    }

    private fun stopProgressUpdater() {
        progressJob?.cancel()
    }

    fun release() {
        exoPlayer?.release()
        exoPlayer = null
    }
}
