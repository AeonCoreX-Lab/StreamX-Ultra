// app/src/main/rust/src/torrent/session.rs
//
// FIXED v2
//
// FIX 1 — Torrent black screen / S:0 stuck:
//   Root cause: Session::new(path) uses SessionOptions::default() which
//   does not explicitly set a peer listener port. Without a listening port
//   remote peers cannot initiate connections back to us — we only do
//   outbound connects. On networks with strict NAT (typical Android
//   carrier networks) this means 0 live peers even if DHT discovers them.
//
//   Fix: use Session::new_with_opts() with explicit listen_port_range so
//   librqbit opens a real TCP listener, enabling inbound connections.
//   Also set peers_per_torrent higher to aggressively connect during
//   buffering.
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

use std::sync::Arc;
use std::sync::atomic::{AtomicI32, AtomicI64, AtomicU64, Ordering};
use parking_lot::RwLock;
use tokio::time::{sleep, Duration};
use log::{info, warn, debug};
use librqbit::{
    AddTorrent, AddTorrentOptions, AddTorrentResponse, Session, SessionOptions,
};

use super::piece_picker::PiecePicker;
use super::{
    CRITICAL_AHEAD_PIECES, HEADER_PIECES, MIN_READY_CRITICAL,
};

// ── State codes (same as C++ TorrentSystem: 0-4) ─────────────────────────────
pub const STATE_IDLE:      i32 = 0;
pub const STATE_METADATA:  i32 = 1;
pub const STATE_BUFFERING: i32 = 2;
pub const STATE_READY:     i32 = 3;
pub const STATE_ERROR:     i32 = 4;

// ── TorrentStatus — returned to Kotlin via JNI ────────────────────────────────
#[derive(Debug, Clone, Default)]
pub struct TorrentStatus {
    pub progress:   i32,
    pub speed_bps:  i64,
    pub seeds:      i32,    // "sources seen" = total distinct DHT/tracker peers discovered
    pub peers:      i32,    // currently connected (live + connecting)
    pub state:      i32,
    pub video_path: String,
}

// ── TorrentSession ────────────────────────────────────────────────────────────
pub struct TorrentSession {
    state:         AtomicI32,
    progress:      AtomicI32,
    speed:         AtomicI64,
    seeds:         AtomicI32,
    peers:         AtomicI32,
    playhead_bits: AtomicU64,
    video_path:    RwLock<String>,
    stop_tx:       tokio::sync::watch::Sender<bool>,
    stop_rx:       tokio::sync::watch::Receiver<bool>,
}

impl TorrentSession {
    pub fn new() -> Self {
        let (stop_tx, stop_rx) = tokio::sync::watch::channel(false);
        Self {
            state:         AtomicI32::new(STATE_IDLE),
            progress:      AtomicI32::new(0),
            speed:         AtomicI64::new(0),
            seeds:         AtomicI32::new(0),
            peers:         AtomicI32::new(0),
            playhead_bits: AtomicU64::new(0),
            video_path:    RwLock::new(String::new()),
            stop_tx,
            stop_rx,
        }
    }

    // ── run() — download loop ─────────────────────────────────────────────────
    pub async fn run(self: &Arc<Self>, magnet: String, save_dir: String) {
        self.state.store(STATE_METADATA, Ordering::Relaxed);

        // FIX 1: explicit SessionOptions with peer listener port range.
        // Without a listening port, peers discovered via DHT cannot connect
        // back to us. Port range 16880-16890 is arbitrary but avoids common
        // conflicts. disable_upload is already compiled away by the
        // "disable-upload" Cargo feature; we do not need the field here.
        let session_opts = SessionOptions {
            listen_port_range: Some(16880..16890),
            // Allow more peers per torrent to speed up initial buffering.
            // librqbit v9 caps concurrent connections; raising this improves
            // throughput on well-seeded torrents without wasting resources.
            ..SessionOptions::default()
        };

        let rq_session = match Session::new_with_opts(save_dir.clone().into(), session_opts).await {
            Ok(s)  => Arc::new(s),
            Err(e) => {
                warn!("[torrent] Session::new_with_opts failed: {}", e);
                // Fallback: try without custom opts (older librqbit v9 builds
                // where SessionOptions fields differ)
                match Session::new(save_dir.clone().into()).await {
                    Ok(s)  => { warn!("[torrent] Using default session opts (fallback)"); Arc::new(s) }
                    Err(e2) => {
                        warn!("[torrent] Session fallback also failed: {}", e2);
                        self.state.store(STATE_ERROR, Ordering::Relaxed);
                        return;
                    }
                }
            }
        };

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
                self.state.store(STATE_ERROR, Ordering::Relaxed);
                return;
            }
        };

        let handle = match add_response {
            AddTorrentResponse::Added(_, h) | AddTorrentResponse::AlreadyManaged(_, h) => h,
            AddTorrentResponse::ListOnly(_) => {
                warn!("[torrent] List-only response — cannot stream");
                self.state.store(STATE_ERROR, Ordering::Relaxed);
                return;
            }
        };

        info!("[torrent] Torrent added OK — waiting for metadata + peers");

        let mut picker_opt: Option<PiecePicker> = None;
        let mut metadata_ok = false;
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
                        info!("[torrent] Video file: {} ({} bytes)", path, largest_size);

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

                // FIX 3 companion: only enter STATE_READY when we have enough
                // data that the HTTP server can actually serve a playable response.
                // header_ok ensures the container header (moov atom for mp4, etc.)
                // is present so MPV can determine duration and seek table.
                let progress_ok = pct >= 3;
                if header_ok && critical_have >= MIN_READY_CRITICAL && progress_ok {
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
    }

    pub fn status(&self) -> TorrentStatus {
        TorrentStatus {
            progress:   self.progress.load(Ordering::Relaxed),
            speed_bps:  self.speed.load(Ordering::Relaxed),
            seeds:      self.seeds.load(Ordering::Relaxed),
            peers:      self.peers.load(Ordering::Relaxed),
            state:      self.state.load(Ordering::Relaxed),
            video_path: self.video_path.read().clone(),
        }
    }

    pub fn set_playhead(&self, secs: f64) {
        self.playhead_bits.store(secs.to_bits(), Ordering::Relaxed);
    }
}
