package com.aeoncorex.streamx.ui.music

import android.content.Context
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
            exoPlayer = ExoPlayer.Builder(context).build().apply {
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(playing: Boolean) {
                        _isPlaying.value = playing
                        if (playing) startProgressUpdater() else stopProgressUpdater()
                    }
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY) _duration.value = duration
                    }
                })
            }
        }
    }

    fun play(track: MusicTrack) {
        _currentSong.value = track
        exoPlayer?.apply {
            setMediaItem(MediaItem.fromUri(track.streamUrl))
            prepare()
            play()
        }
    }

    fun togglePlayPause() {
        exoPlayer?.let { if (it.isPlaying) it.pause() else it.play() }
    }

    fun seekTo(position: Long) { exoPlayer?.seekTo(position) }

    private fun startProgressUpdater() {
        progressJob?.cancel()
        progressJob = CoroutineScope(Dispatchers.Main).launch {
            while (isActive) {
                exoPlayer?.let { _currentPosition.value = it.currentPosition }
                delay(1000)
            }
        }
    }

    private fun stopProgressUpdater() { progressJob?.cancel() }
    fun release() { exoPlayer?.release(); exoPlayer = null }
}
