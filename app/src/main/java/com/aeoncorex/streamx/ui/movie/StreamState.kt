package com.aeoncorex.streamx.ui.movie

sealed class StreamState {
    data class Preparing(val message: String) : StreamState()
    data class Buffering(val progress: Int, val speed: Long, val seeds: Int, val peers: Int) : StreamState()
    data class Ready(val filePath: String) : StreamState()
    data class Error(val message: String) : StreamState()
}
