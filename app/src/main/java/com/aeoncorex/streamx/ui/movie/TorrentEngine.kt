package com.aeoncorex.streamx.ui.movie

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// ═════════════════════════════════════════════════════════════════════════════
//  TorrentEngine.kt  v3  —  Rust-backed
//
//  CHANGES from v2 (C++ libtorrent):
//    • REMOVED: TorrentStreamServer.start(file)  → Rust HTTP server handles it
//    • REMOVED: TorrentStreamServer import
//    • ADDED:   setPlayheadNative(secs)  → piece prioritisation
//    • ADDED:   getLocalUrlNative()      → "http://127.0.0.1:8088/stream"
//    • ALL other JNI names are IDENTICAL to v2 — Kotlin code unchanged elsewhere
//
//  Used by MoviePlayerScreen.kt — that file needs zero changes.
// ═════════════════════════════════════════════════════════════════════════════
object TorrentEngine {

    private const val TAG = "TorrentEngine"

    // ── Rust JNI declarations ─────────────────────────────────────────────────
    // These map to lib.rs JNI functions — names are identical to old C++ version
    // EXCEPT: getLocalUrlNative + setPlayheadNative are new

    private external fun initNative()
    private external fun startNative(magnet: String, savePath: String)
    private external fun stopNative()
    private external fun getStatusNative(): LongArray     // [progress,speed,seeds,peers,state]
    private external fun getFilePathNative(): String
    private external fun clearCacheNative(dir: String)
    // Tier 3 #16: works in release builds too (no HTTP surface, unlike the
    // debug_assertions-gated /debug route) — for a user-initiated
    // "Copy Diagnostics" button.
    private external fun getDebugDumpNative(): String

    // ── New Rust-only methods ─────────────────────────────────────────────────
    private external fun getLocalUrlNative(): String      // "http://127.0.0.1:8088/stream"
    private external fun setPlayheadNative(secs: Double)  // tells Rust piece picker

    init {
        System.loadLibrary("streamx-native")
        initNative()
        Log.d(TAG, "Rust torrent engine ready")
    }

    // ── State types ───────────────────────────────────────────────────────────
    // Values match C++ TorrentSystem constants (0-4) — MoviePlayerScreen unchanged
    enum class State(val code: Int) {
        IDLE(0), METADATA(1), BUFFERING(2), READY(3), ERROR(4);
        companion object { fun from(code: Long) = values().firstOrNull { it.code.toLong() == code } ?: IDLE }
    }

    data class TorrentStatus(
        val progress:  Int   = 0,
        val state:     State = State.IDLE,
        val speedBps:  Long  = 0L,
        val seeds:     Int   = 0,
        val peers:     Int   = 0,
        val filePath:  String = "",
        val streamUrl: String = ""
    ) {
        val isReady     get() = state == State.READY
        val isBuffering get() = state == State.BUFFERING || state == State.METADATA
        val speedMbps   get() = "%.1f Mb/s".format(speedBps / 1_000_000.0)
    }

    // ── Internal state ────────────────────────────────────────────────────────
    private val _status  = MutableStateFlow(TorrentStatus())
    val status: StateFlow<TorrentStatus> = _status.asStateFlow()

    private val scope     = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob:  Job? = null
    private var localUrl: String = ""

    // ── Start ─────────────────────────────────────────────────────────────────
    fun start(magnet: String, saveDir: String) {
        Log.d(TAG, "start  saveDir=$saveDir")

        startNative(magnet, saveDir)
        localUrl = getLocalUrlNative()          // "http://127.0.0.1:8088/stream"

        // NO TorrentStreamServer.start() — Rust HTTP server handles this now

        _status.value = TorrentStatus(state = State.METADATA, streamUrl = localUrl)

        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                try {
                    val arr  = getStatusNative()  // [progress, speed, seeds, peers, state]
                    val path = getFilePathNative()
                    _status.value = TorrentStatus(
                        progress  = arr[0].toInt(),
                        speedBps  = arr[1],
                        seeds     = arr[2].toInt(),
                        peers     = arr[3].toInt(),
                        state     = State.from(arr[4]),
                        filePath  = path,
                        streamUrl = localUrl
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "poll error: ${e.message}")
                }
                delay(250)
            }
        }
    }

    // ── Stop ─────────────────────────────────────────────────────────────────
    fun stop() {
        pollJob?.cancel()
        pollJob  = null
        localUrl = ""
        stopNative()
        _status.value = TorrentStatus()
        Log.d(TAG, "stopped")
    }

    // ── Playhead update — call from MPV time-pos observer ────────────────────
    // Rust uses this to keep piece priorities aligned with playback position.
    fun updatePlaybackPosition(secs: Double) {
        setPlayheadNative(secs)
    }

    // ── Cache cleanup ─────────────────────────────────────────────────────────
    fun clearCache(context: Context) {
        val dir = context.getExternalFilesDir("torrents")?.absolutePath ?: return
        clearCacheNative(dir)
        Log.d(TAG, "cache cleared: $dir")
    }

    // ── Diagnostics export (Tier 3 #16) ─────────────────────────────────────
    // Returns the same torrent-session state that /debug used to expose,
    // for a user-initiated "Copy Diagnostics" / "Report Issue" button.
    // Safe to call anytime (returns a "no torrent active" message if
    // nothing is running) and works identically in release builds.
    fun getDiagnostics(): String = try {
        getDebugDumpNative()
    } catch (e: Exception) {
        "diagnostics unavailable: ${e.message}\n"
    }
}
