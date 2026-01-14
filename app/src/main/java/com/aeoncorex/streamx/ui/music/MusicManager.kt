package com.aeoncorex.streamx.ui.music

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object MusicManager {
    private var exoPlayer: ExoPlayer? = null
    private var progressJob: Job? = null

    // Playlist Queue
    private var playlist: List<MusicTrack> = emptyList()
    private var currentIndex: Int = -1

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
                repeatMode = Player.REPEAT_MODE_OFF 
                
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(playing: Boolean) {
                        _isPlaying.value = playing
                        if (playing) startProgressUpdater() else stopProgressUpdater()
                    }
                    
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY) {
                            _duration.value = duration
                        }
                        if (state == Player.STATE_ENDED) {
                            playNext()
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Log.e("MusicManager", "Error: ${error.message}")
                        _isPlaying.value = false
                        playNext() 
                    }
                })
            }
        }
    }

    fun playTrackList(list: List<MusicTrack>, startIndex: Int) {
        if (list.isEmpty()) return
        playlist = list
        currentIndex = startIndex.coerceIn(0, list.lastIndex)
        loadAndPlay(playlist[currentIndex])
    }

    fun play(track: MusicTrack) {
        playTrackList(listOf(track), 0)
    }

    private fun loadAndPlay(track: MusicTrack) {
        if (track.streamUrl.isBlank()) return

        _currentSong.value = track
        exoPlayer?.apply {
            stop()
            clearMediaItems()
            try {
                val mediaItem = MediaItem.fromUri(Uri.parse(track.streamUrl))
                setMediaItem(mediaItem)
                prepare()
                playWhenReady = true
            } catch (e: Exception) {
                Log.e("MusicManager", "Error loading media: ${e.message}")
            }
        }
    }

    fun togglePlayPause() {
        exoPlayer?.let {
            if (it.isPlaying) it.pause() else it.play()
        }
    }

    // --- FIX: Added this function to resolve the error in PlayerScreen.kt ---
    fun pause() {
        exoPlayer?.pause()
    }

    fun playNext() {
        if (playlist.isNotEmpty() && currentIndex < playlist.lastIndex) {
            currentIndex++
            loadAndPlay(playlist[currentIndex])
        } else {
             currentIndex = 0
             loadAndPlay(playlist[0])
        }
    }

    fun playPrevious() {
        if (playlist.isNotEmpty() && currentIndex > 0) {
            currentIndex--
            loadAndPlay(playlist[currentIndex])
        } else {
            seekTo(0)
        }
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
                    if (_duration.value <= 0L && it.duration > 0) {
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
