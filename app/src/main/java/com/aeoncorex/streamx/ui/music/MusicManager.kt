package com.aeoncorex.streamx.ui.music

import android.content.Context
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.schabi.newpipe.extractor.NewPipe // Import NewPipe

object MusicManager {
    private var exoPlayer: ExoPlayer? = null
    private var progressJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

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

    private val _lyrics = MutableStateFlow<String>("Loading Lyrics...")
    val lyrics = _lyrics.asStateFlow()
    
    private val _queue = MutableStateFlow<List<MusicTrack>>(emptyList())
    val queue = _queue.asStateFlow()

    fun initialize(context: Context) {
        // --- NEWPIPE INITIALIZATION ---
        // অ্যাপ স্টার্ট হওয়ার সময় একবার কল হবে
        if (exoPlayer == null) {
            // NewPipe Setup
            NewPipe.init(MusicRepository.getDownloader()) 
            
            // ExoPlayer Setup (Same as before)
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build()

            exoPlayer = ExoPlayer.Builder(context).build().apply {
                setAudioAttributes(audioAttributes, true)
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY) {
                            _duration.value = duration
                        }
                        if (state == Player.STATE_ENDED) {
                            playNext()
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        _isPlaying.value = isPlaying
                        if (isPlaying) startProgressUpdater() else stopProgressUpdater()
                    }
                    
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        Log.e("MusicManager", "Error: ${error.message}")
                        playNext()
                    }
                })
            }
        }
    }

    fun playCollection(collectionId: String, type: CollectionType) {
        scope.launch {
            exoPlayer?.stop()
            _isPlaying.value = false
            val tracks = MusicRepository.getCollectionTracks(collectionId, type)
            if (tracks.isNotEmpty()) {
                playTrackList(tracks, 0)
            }
        }
    }

    fun playTrackList(tracks: List<MusicTrack>, startIndex: Int) {
        playlist = tracks
        _queue.value = tracks
        currentIndex = startIndex
        if (playlist.isNotEmpty() && currentIndex in playlist.indices) {
            resolveAndPlay(playlist[currentIndex])
        }
    }

    fun pause() {
        exoPlayer?.pause()
        _isPlaying.value = false
    }

    fun release() {
        exoPlayer?.release()
        exoPlayer = null
        stopProgressUpdater()
        _isPlaying.value = false
        _currentSong.value = null
    }

    private fun resolveAndPlay(track: MusicTrack) {
        _currentSong.value = track
        _currentPosition.value = 0
        _duration.value = 0
        
        fetchLyrics(track)

        scope.launch {
            try {
                // If streamUrl is just an ID or URL (YouTube), resolve it using NewPipe
                val finalUrl = if (track.source == "YouTube") {
                     MusicRepository.getYouTubeAudioUrl(track.streamUrl)
                } else {
                     track.streamUrl
                }
                
                if (finalUrl.isNotEmpty()) {
                    val mediaItem = MediaItem.fromUri(finalUrl)
                    exoPlayer?.setMediaItem(mediaItem)
                    exoPlayer?.prepare()
                    exoPlayer?.play()
                } else {
                    Log.e("MusicManager", "Failed to resolve URL for ${track.title}")
                    playNext()
                }
            } catch (e: Exception) {
                Log.e("MusicManager", "Error loading media: ${e.message}")
                playNext()
            }
        }
    }

    private fun fetchLyrics(track: MusicTrack) {
        _lyrics.value = "Searching lyrics for ${track.title}..."
        scope.launch {
            try {
                val lyricsData = MusicRepository.getLyrics(track.title, track.artist)
                _lyrics.value = lyricsData?.plainLyrics ?: "No lyrics found for this track."
            } catch (e: Exception) {
                _lyrics.value = "Lyrics not available."
            }
        }
    }

    fun togglePlayPause() {
        exoPlayer?.let {
            if (it.isPlaying) it.pause() else it.play()
        }
    }

    fun playNext() {
        if (playlist.isNotEmpty() && currentIndex < playlist.lastIndex) {
            currentIndex++
            resolveAndPlay(playlist[currentIndex])
        } else if (playlist.isNotEmpty()) {
             currentIndex = 0
             resolveAndPlay(playlist[0])
        }
    }

    fun playPrevious() {
        if (playlist.isNotEmpty() && currentIndex > 0) {
            currentIndex--
            resolveAndPlay(playlist[currentIndex])
        } else {
            seekTo(0)
        }
    }

    fun seekTo(position: Long) {
        exoPlayer?.seekTo(position)
    }
    
    // --- NEW: Stop Player Completely (For Swipe to Remove) ---
    fun stopPlayer() {
        exoPlayer?.stop()
        exoPlayer?.clearMediaItems()
        _currentSong.value = null
        _isPlaying.value = false
        stopProgressUpdater()
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
}
