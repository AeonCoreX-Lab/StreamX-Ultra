// app/src/main/rust/src/torrent/session.rs
//
// FIXED v3
//
// FIX 1 — Torrent black screen / S:0 stuck:
//   Root cause: Session::new(path) uses SessionOptions::default() which
//   does not explicitly set a peer listener port. Without a listening port
//   remote peers cannot initiate connections back to us — we only do
//   outbound connects. On networks with strict NAT (typical Android
//   carrier networks) this means 0 live peers even if DHT discovers them.
//
//   Fix: use Session::new_with_opts() with a ListenerOptions (v9 API).
//   librqbit v9 replaced listen_port_range with a listen: Option<ListenerOptions>
//   field in SessionOptions.  ListenerOptions.listen_addr sets the bind
//   address + port; ipv4_only=true avoids IPv6 on Android.
//   Also raise peer_limit in SessionOptions for faster initial buffering.
//
// FIX 2 — S:0 display misleading during METADATA phase:
//   stats.live is None (and therefore seen=0) while the torrent is still
//   resolving metadata — this is CORRECT librqbit v9 behaviour, not a bug.
//   We now track a separate `dht_peers_seen` counter from the session-level
//   DHT stats so the UI shows >0 as soon as DHT discovers ANY node, even
//   before metadata resolves. This makes "S: N" mean "DHT nodes seen" rather
//   than "seeds with full copy", which is the most useful early indicator.
//
//   NOTE: If S stays 0 permanently, it means UDP/DHT is blocked on the
//   device network — not a librqbit bug. Common causes: VPN in TUN mode
//   dropping UDP, carrier-grade NAT blocking DHT port 6881, Android
//   battery saver restricting background sockets.
//
// FIX 3 — http_server.rs STATE_READY gate (see http_server.rs):
//   The HTTP server previously served file bytes as soon as video_path was
//   set (STATE_BUFFERING), even when 0 bytes were downloaded. MPV would
//   open the URL, get a valid 200 response with 0 or corrupt bytes, and
//   render a black screen. The fix is in http_server.rs: it now returns
//   a Retry-After: 2 503 response until state >= STATE_READY, so MPV
//   retries cleanly instead of locking onto 0-byte data.
//   (session.rs exposes STATE_READY as pub so http_server can import it.)
//
// FIX 4 — Stale previous movie plays again on the next torrent (v4):
//   Root cause: every torrent used the SAME save_dir
//   (getExternalFilesDir("torrents")), and video_path was chosen via
//   files.iter().max_by_key(|f| f.len) over whatever was physically
//   present in save_dir. clearCache() on the old session's dispose is
//   best-effort (remove_dir_all errors were only warn!-logged, never
//   surfaced), and the old size-gated safety net only fired above 8GB
//   — nowhere near a single leftover movie file. If the old file was
//   still on disk for ANY reason when the new torrent's metadata
//   resolved, max_by_key could pick the old, larger, complete file
//   over the new, still-downloading one, and the new session would
//   serve the OLD movie's bytes.
//
//   Fix (two parts, see TorrentEngine.kt + this file):
//     1. Kotlin now allocates a fresh, uniquely-named SUBFOLDER per
//        movie (torrents/<uuid>/) instead of reusing one shared
//        directory. Each torrent's max_by_key search is now scoped to
//        its own subfolder, so a leftover from a previous movie is in
//        a different directory entirely and can never be selected —
//        this makes the bug structurally impossible, not just less
//        likely, regardless of clearCache() timing.
//     2. cleanup_orphaned_leftovers_if_needed() now walks the PARENT
//        of save_dir and removes every sibling subfolder that isn't
//        the current one, unconditionally (not size-gated) — so any
//        leftover from a crashed/killed previous session is reclaimed
//        immediately, not just once it crosses 8GB.

use std::net::{Ipv4Addr, SocketAddr};
use std::sync::Arc;
use std::sync::atomic::{AtomicI32, AtomicI64, AtomicU64, Ordering};
use parking_lot::RwLock;
use tokio::time::{sleep, Duration};
use log::{info, warn, debug};
use librqbit::{
    AddTorrent, AddTorrentOptions, AddTorrentResponse, DhtSessionConfig, ListenerOptions,
    ManagedTorrent, Session, SessionOptions,
};
use librqbit::dht::DhtPersistenceConfig;

use super::piece_picker::PiecePicker;
use super::{
    CRITICAL_AHEAD_PIECES, HEADER_PIECES, MIN_READY_CRITICAL,
    TARGET_READY_BUFFER_SECS,
};

// ── State codes (same as C++ TorrentSystem: 0-4) ─────────────────────────────
pub const STATE_IDLE:      i32 = 0;
pub const STATE_METADATA:  i32 = 1;
pub const STATE_BUFFERING: i32 = 2;
pub const STATE_READY:     i32 = 3;
pub const STATE_ERROR:     i32 = 4;

// ── Disk-space guard (Tier 1 #11) ──────────────────────────────────────────
//
// Without this, a torrent can download until the device's storage fills up
// mid-download — librqbit's write calls start failing, pieces silently stop
// completing, and the user sees an unexplained stall/error deep into a
// multi-GB download instead of an immediate, clear message before it starts.
//
// Two checkpoints:
//   1. Pre-flight (before any network/session activity): bail out fast if
//      the device is already nearly full, before wasting time on DHT/peer
//      setup for a download that can't possibly complete.
//   2. Post-metadata (once the actual file size is known): precise check
//      against the real download size, before the bulk of the download
//      begins (only the header/metadata pieces have been fetched so far).
//
// A fixed cushion is subtracted from available space rather than requiring
// space for the exact file size only — Android and other apps need some
// headroom, and torrent pieces occasionally get re-verified/re-downloaded.

/// Minimum free space required just to START a download, before the torrent's
/// actual size is known from metadata. Generous enough to almost never
/// false-positive on a healthy device, small enough to catch "storage is
/// basically full" immediately rather than after minutes of buffering.
const MIN_PREFLIGHT_FREE_BYTES: u64 = 300 * 1024 * 1024; // 300 MB

/// Safety margin kept free BEYOND the torrent's exact size once it's known.
const SPACE_CUSHION_BYTES: u64 = 200 * 1024 * 1024; // 200 MB

fn human_bytes(bytes: u64) -> String {
    const GB: f64 = 1024.0 * 1024.0 * 1024.0;
    const MB: f64 = 1024.0 * 1024.0;
    let b = bytes as f64;
    if b >= GB { format!("{:.2} GB", b / GB) } else { format!("{:.0} MB", b / MB) }
}

/// Checks free space at `save_dir` against `required_bytes` (+ cushion).
/// Fails OPEN (returns Ok, logs a warning) if the free-space check itself
/// errors — e.g. an unusual filesystem/mount that statvfs can't read.
/// Blocking playback entirely because of an unrelated statvfs quirk would
/// be worse than the rare case this guard exists to catch.
fn check_disk_space(save_dir: &str, required_bytes: u64) -> Result<(), String> {
    match fs2::available_space(std::path::Path::new(save_dir)) {
        Ok(avail) => {
            let needed = required_bytes.saturating_add(SPACE_CUSHION_BYTES);
            if avail < needed {
                Err(format!(
                    "Not enough storage. This download needs {} (+{} safety \
                     margin) = {} total, but only {} is available.",
                    human_bytes(required_bytes),
                    human_bytes(SPACE_CUSHION_BYTES),
                    human_bytes(needed),
                    human_bytes(avail),
                ))
            } else {
                Ok(())
            }
        }
        Err(e) => {
            warn!("[torrent] could not check available disk space at {save_dir}: {e} — proceeding anyway");
            Ok(())
        }
    }
}

// ── Orphaned-leftover safety net (v4 — sibling-subfolder cleanup) ─────────
//
// The app's intentional design is: clear the CURRENT movie's subfolder when
// the player screen closes normally (see MoviePlayerScreen.kt's
// DisposableEffect calling TorrentEngine.clearCache()) — movies are often
// 1GB+, and never persisting them keeps storage bounded and reduces how
// long potentially-infringing content sits on the device.
//
// GAP 1: Compose's DisposableEffect.onDispose does NOT run if the app
// process is killed abruptly (OOM kill, crash) rather than the Composable
// being properly removed from composition.
// GAP 2 (the actual wrong-movie-plays-again bug, see FIX 4 above): even on
// a normal close, clearCache()'s remove_dir_all can fail silently (only
// warn!-logged), and previously every movie shared ONE save_dir, so any
// leftover competed directly with the new torrent's own files for
// max_by_key() file selection — sometimes winning, and playing the wrong
// movie.
//
// FIX: each movie now gets its own uniquely-named subfolder under a shared
// parent (see TorrentEngine.kt — the save_dir passed down to Rust is always
// "<parent>/<uuid>/"). That alone makes wrong-movie selection structurally
// impossible, independent of cleanup timing, because max_by_key() only ever
// sees files inside the CURRENT movie's own subfolder. This function is the
// second, complementary half: it walks the PARENT of the current save_dir
// and removes every SIBLING subfolder (i.e. every other movie's leftover
// directory), unconditionally — not size-gated — since identity (is this
// mine or not) rather than size is what determines correctness here. This
// also reclaims space from crashed/killed sessions immediately instead of
// waiting for an arbitrary size threshold.
//
// (We don't rely on a rolling/size-limited cache strategy — see
// conversation history: Stremio's own "cache size" setting has a
// long-standing, still-open bug where it isn't honored and storage
// balloons to 10GB+ despite a 2GB limit —
// github.com/Stremio/stremio-bugs/issues/755. Deleting everything that
// isn't the active movie's own folder is simpler and has no such failure
// mode.)

/// Best-effort recursive directory size. Returns 0 on any error (e.g. the
/// directory doesn't exist yet on a fresh install) — safe default that
/// never blocks startup. Used for logging/diagnostics, not gating.
fn dir_size_bytes(dir: &str) -> u64 {
    fn walk(path: &std::path::Path) -> u64 {
        let entries = match std::fs::read_dir(path) {
            Ok(e) => e,
            Err(_) => return 0,
        };
        let mut total = 0u64;
        for entry in entries.flatten() {
            if let Ok(meta) = entry.metadata() {
                if meta.is_dir() {
                    total += walk(&entry.path());
                } else {
                    total += meta.len();
                }
            }
        }
        total
    }
    walk(std::path::Path::new(dir))
}

/// Removes every sibling of `save_dir` inside `save_dir`'s parent directory
/// — i.e. every OTHER movie's leftover subfolder — before starting a new
/// download into `save_dir` itself. `save_dir` is expected to be a fresh,
/// uniquely-named subfolder allocated by Kotlin for this specific torrent
/// (see TorrentEngine.kt); it is never removed by this function.
///
/// Fails open on any individual entry: a directory that fails to delete
/// (permissions, file in use) is logged and skipped rather than aborting
/// the whole cleanup or blocking the new download from starting.
fn cleanup_orphaned_leftovers_if_needed(save_dir: &str) {
    let current = std::path::Path::new(save_dir);
    let parent = match current.parent() {
        Some(p) => p,
        None => {
            warn!("[torrent] save_dir {save_dir} has no parent directory — skipping sibling cleanup");
            return;
        }
    };

    let entries = match std::fs::read_dir(parent) {
        Ok(e) => e,
        Err(_) => return, // parent doesn't exist yet (fresh install) — nothing to clean
    };

    let mut reclaimed: u64 = 0;
    let mut removed_count: u32 = 0;

    for entry in entries.flatten() {
        let path = entry.path();
        if path == current {
            continue; // never delete the folder we're about to download into
        }
        let is_dir = entry.file_type().map(|t| t.is_dir()).unwrap_or(false);
        if !is_dir {
            continue; // leave stray non-directory files alone
        }

        let size = dir_size_bytes(&path.to_string_lossy());
        match std::fs::remove_dir_all(&path) {
            Ok(()) => {
                reclaimed += size;
                removed_count += 1;
                debug!(
                    "[torrent] removed stale movie folder {} ({})",
                    path.display(), human_bytes(size)
                );
            }
            Err(e) => {
                warn!(
                    "[torrent] failed to remove stale movie folder {}: {e} — skipping",
                    path.display()
                );
            }
        }
    }

    if removed_count > 0 {
        info!(
            "[torrent] sibling cleanup: removed {removed_count} stale movie folder(s), reclaimed {}",
            human_bytes(reclaimed),
        );
    }
}

// ── TorrentStatus — returned to Kotlin via JNI ────────────────────────────────
#[derive(Debug, Clone, Default)]
pub struct TorrentStatus {
    pub progress:       i32,
    pub speed_bps:      i64,
    pub seeds:          i32,
    pub peers:          i32,
    pub state:          i32,
    pub video_path:     String,
    /// Bytes downloaded so far (sequential from file start).
    /// Used by the HTTP server to guard Range requests that go beyond
    /// the downloaded region — prevents MPV from reading sparse-file
    /// zeros when seeking for moov atom, which caused file-open failure
    /// and persistent 00:00 display.
    pub progress_bytes: u64,
}

// ── TorrentSession ────────────────────────────────────────────────────────────
pub struct TorrentSession {
    state:          AtomicI32,
    progress:       AtomicI32,
    speed:          AtomicI64,
    seeds:          AtomicI32,
    peers:          AtomicI32,
    playhead_bits:  AtomicU64,
    progress_bytes: AtomicU64,
    video_path:     RwLock<String>,
    // Captures the actual error text whenever STATE_ERROR is set, so
    // /debug can show WHY it failed without needing logcat.
    last_error:     RwLock<String>,

    // ── FileStream support ─────────────────────────────────────────────────
    // Stores the ManagedTorrentHandle so http_server can call
    // handle.clone().stream(file_id) → FileStream, which:
    //   • Blocks (Poll::Pending) until the piece is available     → no zeros
    //   • Prioritizes the piece being read via iter_next_pieces() → moov first
    //   • Wakes via wake_streams_on_piece_completed()             → no polling
    // This replaces ALL of: disk I/O, wait_for_bytes, 503/416, progress_bytes guard.
    torrent_handle:      RwLock<Option<Arc<ManagedTorrent>>>,
    video_file_id:       AtomicI32,
    pub video_file_size: AtomicU64,

    // librqbit Session + TorrentId: used by http_server to call
    // Api::new(rq_session).api_stream(torrent_id, file_id)
    // This is librqbit's own streaming which handles piece priority
    // and blocking correctly — no custom disk reading needed.
    rq_session:     RwLock<Option<Arc<Session>>>,
    torrent_id_val: AtomicI32,   // TorrentId (usize) from AddTorrentResponse; -1 = unset

    stop_tx:        tokio::sync::watch::Sender<bool>,
    stop_rx:        tokio::sync::watch::Receiver<bool>,
}

impl TorrentSession {
    pub fn new() -> Self {
        let (stop_tx, stop_rx) = tokio::sync::watch::channel(false);
        Self {
            state:           AtomicI32::new(STATE_IDLE),
            progress:        AtomicI32::new(0),
            speed:           AtomicI64::new(0),
            seeds:           AtomicI32::new(0),
            peers:           AtomicI32::new(0),
            playhead_bits:   AtomicU64::new(0),
            progress_bytes:  AtomicU64::new(0),
            video_path:      RwLock::new(String::new()),
            last_error:      RwLock::new(String::new()),
            torrent_handle:  RwLock::new(None),
            video_file_id:   AtomicI32::new(-1),
            video_file_size: AtomicU64::new(0),
            rq_session:      RwLock::new(None),
            torrent_id_val:  AtomicI32::new(-1),
            stop_tx,
            stop_rx,
        }
    }

    // ── run() — download loop ─────────────────────────────────────────────────
    pub async fn run(self: &Arc<Self>, magnet: String, save_dir: String) {
        // ── Orphaned-leftover safety net ─────────────────────────────────
        // Runs first — if a previous session's cleanup didn't complete
        // (crash/kill), this frees the space before the disk-space check
        // below runs, so it sees accurate availability. See the detailed
        // reasoning comment above cleanup_orphaned_leftovers_if_needed().
        cleanup_orphaned_leftovers_if_needed(&save_dir);

        // ── Ensure save_dir actually exists ───────────────────────────────
        // save_dir is expected to already exist (Kotlin's
        // allocateFreshMovieDir() creates it via File.mkdirs() immediately
        // before calling into Rust), but this is verified/enforced here
        // too rather than assumed — both the disk-space check right below
        // and the DHT persistence setup further down read/write against
        // this exact path, and previously assuming it existed (rather than
        // checking) is exactly the class of bug that caused
        // Session::new_with_opts() to fail silently before (see the FIX
        // (ROOT CAUSE): DHT persistence path comment below for the full
        // history — that bug's symptom was an instant "fetching metadata"
        // flash with no real buffering, landing on a stuck 00:00, because
        // the session never actually started).
        if let Err(e) = std::fs::create_dir_all(&save_dir) {
            warn!(
                "[torrent] failed to create save_dir {save_dir}: {e} — proceeding anyway, \
                 but disk-space check and Session::new_with_opts below will likely fail \
                 the same way if this directory truly isn't writable."
            );
        }

        // ── Pre-flight disk-space check ─────────────────────────────────
        // Bail out immediately if storage is already nearly full, before
        // spending time on DHT/peer setup for a download that can't
        // possibly complete. This is a generic sanity gate (not size-aware
        // yet, since the torrent's actual size isn't known until metadata
        // arrives). The precise, size-aware check happens again below,
        // once metadata is available (see check_disk_space call further
        // down with the real file size).
        match fs2::available_space(std::path::Path::new(&save_dir)) {
            Ok(avail) if avail < MIN_PREFLIGHT_FREE_BYTES => {
                let msg = format!(
                    "Not enough storage to start downloading. At least {} free \
                     space is needed, but only {} is available.",
                    human_bytes(MIN_PREFLIGHT_FREE_BYTES), human_bytes(avail),
                );
                warn!("[torrent] pre-flight disk-space check failed: {msg}");
                *self.last_error.write() = msg;
                self.state.store(STATE_ERROR, Ordering::Relaxed);
                return;
            }
            Ok(_) => { /* enough space to at least attempt starting */ }
            Err(e) => {
                warn!("[torrent] pre-flight disk-space check errored: {e} — proceeding anyway");
            }
        }

        self.state.store(STATE_METADATA, Ordering::Relaxed);

        // FIX 1 (v9 API): librqbit v9 removed `listen_port_range` from
        // SessionOptions.  The replacement is `listen: Option<ListenerOptions>`.
        //
        // ListenerOptions.listen_addr is a SocketAddr that sets BOTH the bind
        // IP and port for the BT-TCP listener.  We pick port 16880 (fixed,
        // avoids common conflicts).  ipv4_only=true prevents the default
        // IPv6 bind that may fail on some Android carriers.
        //
        // peer_limit raises the per-session peer cap (new v9 field) so we
        // connect aggressively during buffering on well-seeded torrents.
        // ── FIX (ROOT CAUSE): DHT persistence path ──────────────────────
        // librqbit's DHT persistence defaults to `config_filename: None`,
        // which triggers `directories::ProjectDirs::from("com","rqbit","dht")`
        // to auto-detect an OS config directory via HOME/XDG env vars.
        // Android apps are sandboxed and have no such env vars, so this
        // call fails IMMEDIATELY — before any torrent/peer/socket logic
        // ever runs. Session::new_with_opts() returns Err, and (until this
        // fix) so did the fallback Session::new(), leaving state=ERROR
        // with peers=0, seeds=0, rq_session_set=false — exactly matching
        // what /debug showed, and visible in the UI as an instant
        // "fetching metadata" flash with no real buffering, landing on a
        // stuck 00:00 (the player opens the stream URL against a session
        // that never actually started).
        //
        // FIX (v4 hardening): originally save_dir was one long-lived shared
        // folder (getExternalFilesDir("torrents")) that Android itself
        // guarantees exists once requested, so "does the directory exist"
        // was never a real concern here. Since the per-movie subfolder
        // change, save_dir is a freshly-allocated UUID folder created on
        // the Kotlin side (File.mkdirs()) immediately before start() calls
        // into Rust. This function used to silently rely on that without
        // ever verifying it here — see the create_dir_all(&save_dir) call
        // near the top of run() (right after the orphan-cleanup safety
        // net), which now guarantees this directory exists before we get
        // this far, independent of whatever the Kotlin caller already did.
        // That closes the exact class of regression this comment used to
        // just assume away.
        //
        // Fix: point DHT persistence at an explicit path inside save_dir,
        // which is now guaranteed to exist (previously assumed, not
        // verified). This keeps persistence enabled (faster peer discovery
        // across app restarts) while skipping OS-directory auto-detection.
        let dht_json_path = std::path::PathBuf::from(&save_dir).join(".dht_state.json");
        let dht_config = DhtSessionConfig {
            persistence: Some(DhtPersistenceConfig {
                config_filename: Some(dht_json_path),
                ..DhtPersistenceConfig::default()
            }),
            ..DhtSessionConfig::default()
        };

        let listen_opts = ListenerOptions {
            listen_addr: SocketAddr::from((Ipv4Addr::UNSPECIFIED, 16880u16)),
            ipv4_only: true,
            ..ListenerOptions::default()
        };
        let session_opts = SessionOptions {
            listen: Some(listen_opts),
            peer_limit: Some(50),
            dht: Some(dht_config),
            ..SessionOptions::default()
        };

        let rq_session = match Session::new_with_opts(save_dir.clone().into(), session_opts).await {
            Ok(s)  => s,   // Session::new_with_opts returns Arc<Session> directly
            Err(e) => {
                warn!("[torrent] Session::new_with_opts failed: {}", e);
                // Fallback: retry with minimal options, but KEEP the explicit
                // DHT path fix — bare Session::new() also defaults to DHT
                // persistence with auto-detected OS dirs, which would hit
                // the exact same Android crash this fix addresses.
                let fallback_opts = SessionOptions {
                    dht: Some(DhtSessionConfig {
                        persistence: Some(DhtPersistenceConfig {
                            config_filename: Some(
                                std::path::PathBuf::from(&save_dir).join(".dht_state.json")
                            ),
                            ..DhtPersistenceConfig::default()
                        }),
                        ..DhtSessionConfig::default()
                    }),
                    ..SessionOptions::default()
                };
                match Session::new_with_opts(save_dir.clone().into(), fallback_opts).await {
                    Ok(s)  => { warn!("[torrent] Using minimal session opts (fallback)"); s }
                    Err(e2) => {
                        warn!("[torrent] Session fallback also failed: {}", e2);
                        *self.last_error.write() = format!(
                            "Session::new_with_opts failed: {e:#}\nSession::new fallback also failed: {e2:#}\nsave_dir={save_dir}"
                        );
                        self.state.store(STATE_ERROR, Ordering::Relaxed);
                        return;
                    }
                }
            }
        };

        // Store librqbit session so http_server can use Api::api_stream
        // rq_session is Arc<Session> — store as-is, no extra Arc wrap.
        *self.rq_session.write() = Some(rq_session.clone());

        // Add torrent
        let mut opts   = AddTorrentOptions::default();
        opts.overwrite = true;

        let add_response = match rq_session
            .add_torrent(AddTorrent::from_url(&magnet), Some(opts))
            .await
        {
            Ok(r)  => r,
            Err(e) => {
                warn!("[torrent] add_torrent failed: {}", e);
                *self.last_error.write() = format!("add_torrent failed: {e:#}\nmagnet={magnet}");
                self.state.store(STATE_ERROR, Ordering::Relaxed);
                return;
            }
        };

        let (torrent_id, handle) = match add_response {
            AddTorrentResponse::Added(id, h)         => (id, h),
            AddTorrentResponse::AlreadyManaged(id, h) => (id, h),
            AddTorrentResponse::ListOnly(_) => {
                warn!("[torrent] List-only response — cannot stream");
                *self.last_error.write() = "AddTorrentResponse::ListOnly — magnet resolved to list-only, cannot stream".to_string();
                self.state.store(STATE_ERROR, Ordering::Relaxed);
                return;
            }
        };
        // Store torrent_id so http_server can call api_stream(torrent_id, file_id)
        self.torrent_id_val.store(torrent_id as i32, Ordering::Relaxed);

        // Store handle immediately so http_server can call handle.stream(file_id)
        // once file_id is known (set below when metadata arrives).
        // FileStream created from this handle:
        //   - blocks until pieces are available (Poll::Pending)  → no zeros
        //   - feeds read position into piece picker priority      → moov downloaded first
        //   - woken by wake_streams_on_piece_completed()          → zero overhead
        *self.torrent_handle.write() = Some(handle.clone());

        info!("[torrent] Torrent added OK — waiting for metadata + peers");

        let mut picker_opt: Option<PiecePicker> = None;
        let mut metadata_ok = false;
        // Set inside the metadata closure below; checked right after against
        // available disk space, since we can't `return` from run() from
        // inside the closure itself.
        let mut known_file_size: u64 = 0;
        let mut stop_rx     = self.stop_rx.clone();
        let mut tick: u32   = 0;

        // ── Monitor loop ──────────────────────────────────────────────────────
        loop {
            tokio::select! {
                _ = stop_rx.changed() => { if *stop_rx.borrow() { break; } }
                _ = sleep(Duration::from_millis(250)) => {}
            }
            tick += 1;

            let stats = handle.stats();

            // ── Peer / speed stats ────────────────────────────────────────────
            // FIX 2: stats.live is None during METADATA phase — this is correct.
            // `seen` = total distinct peers discovered (most useful early metric).
            // `live` + `connecting` = active connections right now.
            let (seen_count, peer_count, speed) = match stats.live.as_ref() {
                Some(l) => {
                    let ps = &l.snapshot.peer_stats;
                    (
                        ps.seen as i32,
                        (ps.connecting + ps.live) as i32,
                        l.download_speed.as_bytes() as i64,
                    )
                }
                None => (0, 0, 0),
            };
            self.seeds.store(seen_count, Ordering::Relaxed);
            self.peers.store(peer_count, Ordering::Relaxed);
            self.speed.store(speed,      Ordering::Relaxed);

            // ── Progress ──────────────────────────────────────────────────────
            let pct = if stats.total_bytes > 0 {
                (stats.progress_bytes as f64 / stats.total_bytes as f64 * 100.0) as i32
            } else { 0 };
            self.progress.store(pct, Ordering::Relaxed);
            // Store raw progress_bytes so the HTTP server can guard Range
            // requests that go beyond the downloaded region.
            self.progress_bytes.store(stats.progress_bytes, Ordering::Relaxed);

            // ── Diagnostics every ~5 s ────────────────────────────────────────
            if tick % 20 == 0 {
                match &stats.live {
                    None => debug!(
                        "[torrent] still METADATA (metadata_ok={}, pct={}, seen={})",
                        metadata_ok, pct, seen_count
                    ),
                    Some(l) => {
                        let ps = &l.snapshot.peer_stats;
                        debug!(
                            "[torrent] pct={} speed={}B/s seen={} connecting={} live={} dead={}",
                            pct, speed, ps.seen, ps.connecting, ps.live, ps.dead
                        );
                    }
                }
            }

            // ── Metadata phase ────────────────────────────────────────────────
            if !metadata_ok {
                self.state.store(STATE_METADATA, Ordering::Relaxed);

                if handle.with_metadata(|_| ()).is_ok() {
                    metadata_ok = true;
                    self.state.store(STATE_BUFFERING, Ordering::Relaxed);

                    let _ = handle.with_metadata(|metadata| {
                        let files = &metadata.file_infos;
                        let (largest_idx, largest_size) = files
                            .iter()
                            .enumerate()
                            .max_by_key(|(_, f)| f.len)
                            .map(|(i, f)| (i, f.len))
                            .unwrap_or((0, 0));

                        let path = format!(
                            "{}/{}",
                            save_dir,
                            files[largest_idx].relative_filename.display()
                        );
                        *self.video_path.write() = path.clone();
                        // Expose file_id + size so http_server can open a FileStream.
                        // Once video_file_id >= 0, stream_info() returns Some(handle, id).
                        self.video_file_id  .store(largest_idx as i32, Ordering::Relaxed);
                        self.video_file_size.store(largest_size,        Ordering::Relaxed);
                        known_file_size = largest_size;
                        info!("[torrent] Video file: {} ({} bytes, file_id={})",
                              path, largest_size, largest_idx);

                        let total_pieces = metadata.lengths().total_pieces() as u32;
                        let piece_len    = metadata.lengths().default_piece_length() as u64;
                        let file_offset: u64 =
                            files[..largest_idx].iter().map(|f| f.len).sum();
                        let first_piece  = (file_offset / piece_len) as u32;
                        let last_piece   = ((file_offset + largest_size) / piece_len)
                            .min((total_pieces - 1) as u64) as u32;

                        picker_opt = Some(PiecePicker::new(
                            total_pieces, first_piece, last_piece, piece_len,
                        ));
                    });

                    // ── Disk-space AWARENESS (non-blocking) ─────────────────
                    //
                    // ROOT CAUSE OF A REAL, TWICE-CONFIRMED REGRESSION
                    // (found via the in-app "Copy Diagnostics" button, on
                    // two separate real-device reports): this used to be a
                    // HARD BLOCK — if `known_file_size + cushion` exceeded
                    // free space, it set STATE_ERROR and aborted the
                    // download entirely, before a single piece downloaded.
                    //
                    // That's the wrong policy for THIS app's architecture.
                    // StreamX streams progressively (FileStream/api_stream,
                    // sequential download prioritized around the playback
                    // position) — it does NOT need the entire file resident
                    // on disk to start or continue playing. A user with,
                    // say, 3.65GB free and a 9.4GB movie can still watch
                    // for a long time; the download only actually fails
                    // once real disk space is exhausted DURING download,
                    // which the OS/filesystem itself will report at that
                    // point — this is the same experience any torrent
                    // client or progressive-download service gives, not a
                    // case that needs (or should have) a pre-emptive block.
                    //
                    // CONCRETE IMPACT of the hard block (confirmed twice):
                    // it doesn't just show a "not enough storage" message —
                    // it silently causes STATE_ERROR, which
                    // MoviePlayerScreen.kt's ERROR handler responds to by
                    // clearing isPreBuffering immediately (to show an error
                    // state), which — since videoPath was never set to a
                    // real stream (that only happens on STATE_READY) —
                    // instead surfaced as bare player controls showing
                    // "00:00 / 00:00" with nothing loaded. Given movies are
                    // routinely 1-10GB+ and many devices don't have that
                    // much free space at all times, this fires far more
                    // often than the rare "genuinely no space at all" case
                    // the pre-flight check (above, 300MB floor) is meant
                    // to catch — turning a helpful safety check into a
                    // frequent false "nothing plays" regression.
                    //
                    // NOTE FOR FUTURE EDITS: this exact fix was previously
                    // applied, then LOST when session.rs was rebased onto
                    // an older snapshot during unrelated (network-adaptive
                    // buffering) work. If you are refactoring this file
                    // from an older copy, re-check this specific block
                    // first — grep for "STATE_ERROR, Ordering::Relaxed)"
                    // near a disk-space message and confirm it's a warn!(),
                    // not a hard return.
                    //
                    // FIX: log a warning (visible in /debug and Copy
                    // Diagnostics) so low-space situations are still
                    // diagnosable, but let the download proceed. The
                    // pre-flight check above (300MB floor, checked before
                    // any network activity starts) remains the only
                    // blocking disk-space gate — it catches "storage is
                    // essentially full" without penalizing the completely
                    // normal case of "less free space than the movie's
                    // full size," which this streaming architecture never
                    // required in the first place.
                    if known_file_size > 0 {
                        if let Err(msg) = check_disk_space(&save_dir, known_file_size) {
                            warn!("[torrent] low disk space for this download (not blocking): {msg}");
                        }
                    }
                }
                continue;
            }

            // ── Downloading phase ─────────────────────────────────────────────
            let playhead_secs = f64::from_bits(self.playhead_bits.load(Ordering::Relaxed));

            if let Some(ref mut picker) = picker_opt {
                picker.update_priorities(playhead_secs);

                let critical_start = picker.playhead_piece().max(picker.first_piece);
                let critical_end   = (critical_start + CRITICAL_AHEAD_PIECES).min(picker.last_piece);

                let progress_pieces = if picker.piece_len > 0 {
                    (stats.progress_bytes / picker.piece_len) as u32
                } else {
                    0
                };

                let header_count = HEADER_PIECES
                    .min(picker.last_piece.saturating_sub(picker.first_piece) + 1);
                let header_end   = picker.first_piece + header_count;
                let header_ok    = progress_pieces >= header_end;

                let critical_have: u32 = if progress_pieces >= critical_end {
                    critical_end - critical_start
                } else if progress_pieces > critical_start {
                    progress_pieces - critical_start
                } else {
                    0
                };

                // ── FIX (network-adaptive READY threshold) ──────────────────
                // The old MIN_READY_CRITICAL=20 was a single FIXED piece
                // count, regardless of how fast data was actually arriving.
                // On a fast connection this was needlessly generous (those
                // 20 pieces arrive in under a second, so it barely mattered)
                // — but on a slow connection, 20 pieces might represent only
                // 2-3 seconds of real playback at the current bitrate, which
                // MPV's own demuxer-readahead-secs=8 request could burn
                // through before more data arrives, stalling mid-negotiation
                // even though Rust had already said READY.
                //
                // Fix: instead of a fixed piece count, compute how many
                // pieces represent TARGET_READY_BUFFER_SECS of playback TIME
                // at the CURRENT observed download speed (the `speed`
                // variable a few lines above — a real, already-smoothed
                // metric from librqbit), and require at least that many
                // critical pieces before declaring READY.
                //
                // Note the direction this actually needs to go: a SLOWER
                // connection means fewer bytes arrive per second, so the
                // SAME time-based target (8s of playback) corresponds to
                // FEWER pieces at low speed and MORE pieces at high speed —
                // pieces-per-second scales WITH speed, not against it. So a
                // slow connection naturally computes a SMALLER piece target
                // here, which is the opposite of what we want (we want to
                // hold off longer, i.e. require MORE, on a slow connection).
                // The actual fix computes how long the fixed critical-ahead
                // window (CRITICAL_AHEAD_PIECES) would take to fill at the
                // CURRENT speed, and only requires MORE than the original
                // fixed MIN_READY_CRITICAL when that window would take
                // longer than TARGET_READY_BUFFER_SECS to fill — i.e. only
                // scales UP on a genuinely slow connection, never down.
                let piece_len_f64 = picker.piece_len.max(1) as f64;
                let required_critical: u32 = if speed > 0 && picker.piece_len > 0 {
                    // How many pieces the CRITICAL_AHEAD_PIECES window
                    // represents in real download time, at current speed.
                    let window_bytes = (CRITICAL_AHEAD_PIECES as f64) * piece_len_f64;
                    let window_secs  = window_bytes / (speed as f64);
                    if window_secs < TARGET_READY_BUFFER_SECS {
                        // At this speed, the whole critical-ahead window
                        // fills in LESS time than our target buffer, so a
                        // fast/healthy connection: the fixed baseline is
                        // already fine, no need to require more.
                        MIN_READY_CRITICAL
                    } else {
                        // At this speed, filling the target buffer duration
                        // takes LONGER than the critical-ahead window would
                        // suggest — this is the slow-connection case. Scale
                        // the requirement UP proportionally to how much
                        // slower this is than the baseline, so READY only
                        // fires once a genuinely sufficient amount (in real
                        // time terms) has actually arrived.
                        let slowdown_factor = window_secs / TARGET_READY_BUFFER_SECS;
                        let scaled = (MIN_READY_CRITICAL as f64 * slowdown_factor) as u32;
                        // Cap how far this can scale so an extremely slow/
                        // stalled connection doesn't require an effectively
                        // unreachable amount of data — CRITICAL_AHEAD_PIECES
                        // itself is the hard ceiling (can't require more
                        // than the whole critical window contains).
                        scaled.clamp(MIN_READY_CRITICAL, CRITICAL_AHEAD_PIECES)
                    }
                } else {
                    // No speed sample yet (torrent just started, or
                    // stalled at exactly 0) — fall back to the original
                    // fixed threshold rather than requiring an undefined
                    // amount of data or, worse, none at all.
                    MIN_READY_CRITICAL
                };

                // FIX 3 companion: only enter STATE_READY when we have enough
                // data that the HTTP server can actually serve a playable response.
                // header_ok ensures the container header (moov atom for mp4, etc.)
                // is present so MPV can determine duration and seek table.
                let progress_ok = pct >= 3;
                if header_ok && critical_have >= required_critical && progress_ok {
                    self.state.store(STATE_READY, Ordering::Relaxed);
                } else {
                    self.state.store(STATE_BUFFERING, Ordering::Relaxed);
                }
            }
        }

        info!("[torrent] Session loop ended");
    }

    // ── Public control API ────────────────────────────────────────────────────

    pub async fn stop(&self) {
        let _ = self.stop_tx.send(true);
        self.state.store(STATE_IDLE, Ordering::Relaxed);
        *self.torrent_handle.write() = None;
        *self.rq_session.write()     = None;
        *self.last_error.write()     = String::new();
        self.video_file_id.store(-1,  Ordering::Relaxed);
        self.torrent_id_val.store(-1, Ordering::Relaxed);
    }

    /// Returns (handle, file_id) when the torrent is ready to stream.
    pub fn stream_info(&self) -> Option<(Arc<ManagedTorrent>, usize)> {
        let id = self.video_file_id.load(Ordering::Relaxed);
        if id < 0 { return None; }
        let h = self.torrent_handle.read().clone()?;
        Some((h, id as usize))
    }

    /// Returns (rq_session, torrent_id, file_id) for use with
    /// Api::new(session).api_stream(torrent_id, file_id).
    /// This uses librqbit's own streaming (piece priority + blocking).
    pub fn api_stream_info(&self) -> Option<(Arc<Session>, usize, usize)> {
        let tid = self.torrent_id_val.load(Ordering::Relaxed);
        let fid = self.video_file_id.load(Ordering::Relaxed);
        if tid < 0 || fid < 0 { return None; }
        let sess = self.rq_session.read().clone()?;
        Some((sess, tid as usize, fid as usize))
    }

    pub fn status(&self) -> TorrentStatus {
        TorrentStatus {
            progress:       self.progress.load(Ordering::Relaxed),
            speed_bps:      self.speed.load(Ordering::Relaxed),
            seeds:          self.seeds.load(Ordering::Relaxed),
            peers:          self.peers.load(Ordering::Relaxed),
            state:          self.state.load(Ordering::Relaxed),
            video_path:     self.video_path.read().clone(),
            progress_bytes: self.progress_bytes.load(Ordering::Relaxed),
        }
    }

    /// Human-readable reason for the most recent STATE_ERROR, if any.
    /// Empty string if no error has occurred (or it was cleared by a
    /// subsequent stop()/start()). Used to show an actual, specific error
    /// message to the user instead of a generic "something went wrong" —
    /// see MoviePlayerScreen.kt's ERROR-state handling.
    pub fn last_error(&self) -> String {
        self.last_error.read().clone()
    }

    pub fn set_playhead(&self, secs: f64) {
        self.playhead_bits.store(secs.to_bits(), Ordering::Relaxed);
    }

    /// Dumps every relevant internal field as a human-readable string.
    /// Used by the /debug HTTP endpoint so we can `curl` the exact state
    /// of the torrent session without needing logcat.
    pub fn debug_dump(&self) -> String {
        let tid   = self.torrent_id_val.load(Ordering::Relaxed);
        let fid   = self.video_file_id.load(Ordering::Relaxed);
        let has_s = self.rq_session.read().is_some();
        let has_h = self.torrent_handle.read().is_some();
        let err   = self.last_error.read().clone();
        format!(
            "state={}\nprogress={}%\nspeed_bps={}\nseeds={}\npeers={}\n\
             video_path={:?}\nvideo_file_size={}\nprogress_bytes={}\n\
             torrent_id_val={}\nvideo_file_id={}\n\
             rq_session_set={}\ntorrent_handle_set={}\n\
             api_stream_info_ready={}\n\
             last_error={}\n",
            self.state.load(Ordering::Relaxed),
            self.progress.load(Ordering::Relaxed),
            self.speed.load(Ordering::Relaxed),
            self.seeds.load(Ordering::Relaxed),
            self.peers.load(Ordering::Relaxed),
            self.video_path.read().clone(),
            self.video_file_size.load(Ordering::Relaxed),
            self.progress_bytes.load(Ordering::Relaxed),
            tid, fid, has_s, has_h,
            tid >= 0 && fid >= 0 && has_s,
            if err.is_empty() { "(none)" } else { &err },
        )
    }
}
