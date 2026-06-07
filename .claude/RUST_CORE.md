# StreamX Rust Core

## Location
`app/src/main/rust/` — integrated directly into the Android app repo.
**Not a separate repo** — builds as part of the Android Gradle build.

## Build
```bash
# Install tools (one time)
cargo install cargo-ndk
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android

# Build (runs automatically via Gradle cargoBuild task)
cd app/src/main/rust
ANDROID_NDK_HOME=$ANDROID_NDK_HOME \
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 build --release
```

Output: `app/src/main/rust/target/{triple}/release/libstreamx_core.a`
CMake links it into `libstreamx-native.so` via `--whole-archive`.

## Module structure

```
src/
├── lib.rs               JNI bridge — all extern "system" functions
└── torrent/
    ├── mod.rs           Constants: ports, trackers, piece counts
    ├── engine.rs        TorrentEngine singleton, Tokio runtime
    ├── session.rs       Download loop, piece prioritization
    ├── piece_picker.rs  Zero-buffer algorithm
    └── http_server.rs   /stream + /sub/{filename} HTTP server
```

## JNI function names (identical to old C++ — Kotlin unchanged)

```rust
// In lib.rs — maps to Kotlin external fun declarations:
Java_com_aeoncorex_streamx_ui_movie_TorrentEngine_initNative
Java_com_aeoncorex_streamx_ui_movie_TorrentEngine_startNative
Java_com_aeoncorex_streamx_ui_movie_TorrentEngine_stopNative
Java_com_aeoncorex_streamx_ui_movie_TorrentEngine_getStatusNative  → jlongArray[5]
Java_com_aeoncorex_streamx_ui_movie_TorrentEngine_getFilePathNative → jstring
Java_com_aeoncorex_streamx_ui_movie_TorrentEngine_setPlayheadNative → void (NEW)
Java_com_aeoncorex_streamx_ui_movie_TorrentEngine_getLocalUrlNative → jstring (NEW)
Java_com_aeoncorex_streamx_ui_movie_TorrentEngine_clearCacheNative  → void

// TMDB key (unchanged from before):
Java_com_aeoncorex_streamx_ui_movie_StreamXCore_getTmdbKey

// Addon HTTP transport (NEW):
Java_com_aeoncorex_streamx_streaming_StreamXNative_nativeAddonFetchStreams
```

## getStatusNative return format
```kotlin
// jlongArray[5] = [progress, speedBps, seeds, peers, state]
// State codes (same as old C++):
0 = IDLE
1 = METADATA    (fetching torrent metadata)
2 = BUFFERING   (downloading, not ready)
3 = READY       (can start playback)
4 = ERROR
```

## Zero-buffer piece picker

```rust
// PiecePicker.update_priorities() — called every 250ms:
fn priority_for(&self, piece: u32, playhead: u32) -> u8 {
    if piece < playhead.saturating_sub(5) { return 1; }  // behind
    let ahead = piece.saturating_sub(playhead);
    match ahead {
        0..=29    => 7,   // CRITICAL — must have NOW
        30..=89   => 5,   // HIGH
        90..=199  => 3,   // NORMAL
        _         => 1,   // LOW
    }
}

// Readiness check:
header_ok       = pieces 0..30 all present
critical_have   = count of pieces playhead..playhead+30 present
progress_ok     = download progress >= 3%
ready           = header_ok && critical_have >= 20 && progress_ok
```

## HTTP server endpoints (port 8088)
```
GET /stream              → torrent video (Range support for seeking)
GET /sub/{filename}      → subtitle files from same directory
HEAD /stream             → Content-Length + Accept-Ranges

Replaces: TorrentStreamServer.kt (Ktor-based, now deleted)
MPV URL: http://127.0.0.1:8088/stream (unchanged)
```

## Kotlin side (TorrentEngine.kt)

```kotlin
object TorrentEngine {
    // Loads streamx-native.so which includes Rust .a
    init { System.loadLibrary("streamx-native"); initNative() }

    // Called from MPV time-pos observer:
    fun updatePlaybackPosition(secs: Double) = setPlayheadNative(secs)

    // REMOVED: TorrentStreamServer.start(file)  ← Rust handles HTTP now
}
```

## Key dependencies (Cargo.toml)
```toml
librqbit    = "4"      # pure Rust BitTorrent client
hyper       = "1"      # HTTP server (port 8088)
reqwest     = "0.12"   # HTTP client (addon transport)
tokio       = "1"      # async runtime
jni         = "0.21"   # JNI bridge
android_logger = "0.13" # logcat
```

## Files DELETED from C++ (replaced by Rust)
- `app/src/main/cpp/torrent-engine.cpp`
- `app/src/main/cpp/torrent_system.hpp`
- `app/src/main/java/.../ui/movie/TorrentStreamServer.kt`

## Future targets (same Rust core)
- **Web**: `wasm-pack build --target web` → WASM for browser
- **Desktop**: Tauri app uses the `rlib` directly
- All three share identical business logic, only bridge differs
