package com.aeoncorex.streamx.ui.movie

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID

// ═════════════════════════════════════════════════════════════════════════════
//  TorrentEngine.kt  v4  —  Rust-backed, per-movie subfolder storage
//
//  CHANGES from v3:
//    • FIX (wrong/stale movie plays again): start() used to take a single
//      shared save_dir (getExternalFilesDir("torrents")) reused by every
//      movie. If a previous movie's file wasn't fully cleared before the
//      next one started (crash, killed process, or a clearCache() race),
//      Rust's file-selection (max_by_key over files in save_dir) could pick
//      the OLD, larger, complete file instead of the new torrent's own —
//      so the app would start playing the previous movie again.
//    • FIX: start() now takes the PARENT torrents root and internally
//      allocates a fresh, uniquely-named subfolder (UUID) for this movie,
//      and deletes any sibling subfolders from previous movies before
//      handing the new subfolder to Rust. Each movie's files now live in
//      total isolation from any other movie's leftovers — the old bug
//      becomes structurally impossible, not just less likely.
//    • MoviePlayerScreen.kt now passes the torrents ROOT dir (not a
//      pre-resolved path) — see updated call site there.
//
//  CHANGES from v2 (C++ libtorrent):
//    • REMOVED: TorrentStreamServer.start(file)  → Rust HTTP server handles it
//    • REMOVED: TorrentStreamServer import
//    • ADDED:   setPlayheadNative(secs)  → piece prioritisation
//    • ADDED:   getLocalUrlNative()      → "http://127.0.0.1:8088/stream"
//    • ALL other JNI names are IDENTICAL to v2 — Kotlin code unchanged elsewhere
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
    private external fun clearCacheNative(dir: String): Boolean
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

    // Absolute path of the CURRENT movie's own subfolder (torrentsRoot/<uuid>/).
    // Tracked so clearCache() knows exactly which directory to remove without
    // needing the caller to keep re-deriving it.
    private var currentMovieDir: String? = null

    // ── Start ─────────────────────────────────────────────────────────────────
    // `torrentsRoot` is the SHARED parent directory (e.g.
    // context.getExternalFilesDir("torrents")) — NOT a per-movie path. This
    // function allocates a fresh, uniquely-named subfolder for this specific
    // movie, removes any leftover subfolders from previous movies, and only
    // then starts the Rust torrent session pointed at the new subfolder.
    //
    // This is what prevents a previous movie's leftover file from ever being
    // eligible for Rust's max_by_key() file-selection: it physically cannot
    // be in the same directory as the new torrent's files. See FIX 4 in
    // session.rs for the full root-cause writeup.
    fun start(magnet: String, torrentsRoot: String) {
        val movieDir = allocateFreshMovieDir(torrentsRoot)
        currentMovieDir = movieDir

        Log.d(TAG, "start  movieDir=$movieDir")

        startNative(magnet, movieDir)
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

    // ── Per-movie subfolder allocation ──────────────────────────────────────
    // Creates torrentsRoot/<uuid>/ and deletes every OTHER subfolder under
    // torrentsRoot first (leftovers from previous movies — normally already
    // cleared by clearCache() on dispose, but this is the same defensive,
    // unconditional sibling-cleanup Rust also performs in
    // cleanup_orphaned_leftovers_if_needed(), done here too so the Kotlin
    // side never even points Rust at a directory with stale siblings, and
    // so cache-cleared-on-app-start scenarios are covered even before any
    // Rust session has run.
    //
    // Runs synchronously — callers already invoke start() from a background
    // dispatcher (see MoviePlayerScreen.kt's LaunchedEffect on Dispatchers.IO
    // context for the surrounding torrent-start block), so blocking file I/O
    // here is safe and keeps the "clear old, then start new" ordering
    // guaranteed rather than racing.
    private fun allocateFreshMovieDir(torrentsRoot: String): String {
        val root = File(torrentsRoot)
        if (!root.exists()) {
            root.mkdirs()
        }

        val newDir = File(root, UUID.randomUUID().toString())

        // Remove every existing sibling under root — these can only be
        // leftovers from a previous movie (crash/kill before clearCache()
        // ran, or a clearCache() that failed silently). Unconditional, not
        // size-gated: identity (not-mine == stale) is what matters here.
        root.listFiles()?.forEach { sibling ->
            if (sibling.isDirectory) {
                val removed = sibling.deleteRecursively()
                if (!removed) {
                    Log.w(TAG, "failed to fully remove stale movie folder: ${sibling.absolutePath}")
                } else {
                    Log.d(TAG, "removed stale movie folder: ${sibling.absolutePath}")
                }
            }
        }

        newDir.mkdirs()
        return newDir.absolutePath
    }

    // ── Stop ─────────────────────────────────────────────────────────────────
    // FIX (main-thread block): stopNative() now calls into Rust's
    // rt.block_on(sess.stop()) (see engine.rs FIX comment) so that the
    // directory is guaranteed free of live handles before returning — this
    // closes the storage-cleanup race. BUT: MoviePlayerScreen.kt's
    // DisposableEffect.onDispose {} is a plain, non-suspend lambda that
    // Compose runs synchronously on the MAIN thread during composition
    // teardown. Calling a blocking JNI function directly from there would
    // block the UI thread on every screen exit.
    //
    // Fix: stop() (and clearCache()) now dispatch their native calls onto
    // this object's existing background `scope` (Dispatchers.IO, already
    // used for pollJob) instead of running inline. Callers that don't need
    // to await completion (e.g. onDispose) can call stop() fire-and-forget;
    // callers that DO need ordering guarantees (e.g. the torrent-start flow
    // stopping a previous session before starting a new one) should use
    // stopAndAwait() from a suspend context instead.
    fun stop() {
        pollJob?.cancel()
        pollJob  = null
        localUrl = ""
        _status.value = TorrentStatus()
        scope.launch { stopNative() }
        Log.d(TAG, "stop requested")
    }

    // Suspend variant — actually waits for native teardown to finish before
    // returning. Use this wherever the caller's next step depends on the
    // old session truly being gone (e.g. immediately re-starting a new
    // torrent, or needing clearCache() to see a handle-free directory).
    suspend fun stopAndAwait() {
        pollJob?.cancel()
        pollJob  = null
        localUrl = ""
        _status.value = TorrentStatus()
        withContext(Dispatchers.IO) { stopNative() }
        Log.d(TAG, "stopped (awaited)")
    }

    // ── Effective save directory of the movie currently (or last) playing,
    //    for callers that need the exact path — e.g. clearCache(). ─────────
    private fun activeMovieDirOrFallback(context: Context): String? =
        currentMovieDir ?: context.getExternalFilesDir("torrents")?.absolutePath

    // ── Playhead update — call from MPV time-pos observer ────────────────────
    // Rust uses this to keep piece priorities aligned with playback position.
    fun updatePlaybackPosition(secs: Double) {
        setPlayheadNative(secs)
    }

    // ── Cache cleanup ─────────────────────────────────────────────────────────
    // Clears the CURRENT movie's own subfolder (currentMovieDir) if one is
    // set, falling back to the shared torrents root only when start() was
    // never called this session (e.g. clearCache() invoked defensively on
    // app start before any playback). Clearing just the active subfolder
    // instead of the whole root is intentional: it's the precise scope of
    // "this movie's data," and sibling folders are already handled by the
    // unconditional sibling-cleanup in allocateFreshMovieDir()/Rust's
    // cleanup_orphaned_leftovers_if_needed() the next time a movie starts.
    //
    // Fire-and-forget: dispatches onto the background scope so it's safe to
    // call from a non-suspend context like onDispose {} without blocking
    // the caller's thread. Use clearCacheAndAwait() if you need to know the
    // result (e.g. showing a "couldn't free space" message).
    fun clearCache(context: Context) {
        val dir = activeMovieDirOrFallback(context) ?: return
        if (dir == currentMovieDir) currentMovieDir = null
        scope.launch {
            val cleared = clearCacheNative(dir)
            if (cleared) {
                Log.d(TAG, "cache cleared: $dir")
            } else {
                Log.w(TAG, "cache clear FAILED (will retry as stale sibling next start): $dir")
            }
        }
    }

    // Suspend variant returning whether the clear actually succeeded, for
    // callers that want to react to failure (e.g. a "Clear cache" Settings
    // button that shows a toast on failure instead of assuming success).
    suspend fun clearCacheAndAwait(context: Context): Boolean {
        val dir = activeMovieDirOrFallback(context) ?: return true
        if (dir == currentMovieDir) currentMovieDir = null
        val cleared = withContext(Dispatchers.IO) { clearCacheNative(dir) }
        if (cleared) {
            Log.d(TAG, "cache cleared: $dir")
        } else {
            Log.w(TAG, "cache clear FAILED (will retry as stale sibling next start): $dir")
        }
        return cleared
    }

    // ── Combined convenience for screen-exit cleanup ────────────────────────
    // What MoviePlayerScreen.kt's DisposableEffect.onDispose {} should call:
    // fire-and-forget stop + clear, in order, without blocking the UI thread
    // that's tearing down the Composable. Internally sequenced on the same
    // background scope so clearCache() still only runs after stop()'s native
    // teardown completes (preserving the race fix), while the CALLER
    // (onDispose) returns immediately.
    fun stopAndClearCache(context: Context) {
        pollJob?.cancel()
        pollJob  = null
        localUrl = ""
        _status.value = TorrentStatus()
        val dir = activeMovieDirOrFallback(context)
        if (dir == currentMovieDir) currentMovieDir = null
        scope.launch {
            stopNative()
            if (dir != null) {
                val cleared = clearCacheNative(dir)
                if (cleared) {
                    Log.d(TAG, "cache cleared: $dir")
                } else {
                    Log.w(TAG, "cache clear FAILED (will retry as stale sibling next start): $dir")
                }
            }
        }
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
