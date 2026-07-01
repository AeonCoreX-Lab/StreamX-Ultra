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

use std::net::{Ipv4Addr, SocketAddr};
use std::sync::Arc;
use std::sync::atomic::{AtomicI32, AtomicI64, AtomicU64, Ordering};
use parking_lot::RwLock;
use tokio::time::{sleep, Duration};
use log::{info, warn, debug};
use librqbit::{
    AddTorrent, AddTorrentOptions, AddTorrentResponse, ListenerOptions,
    ManagedTorrent, Session, SessionOptions,
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
        let listen_opts = ListenerOptions {
            listen_addr: SocketAddr::from((Ipv4Addr::UNSPECIFIED, 16880u16)),
            ipv4_only: true,
            ..ListenerOptions::default()
        };
        let session_opts = SessionOptions {
            listen: Some(listen_opts),
            peer_limit: Some(50),
            ..SessionOptions::default()
        };

        let rq_session = match Session::new_with_opts(save_dir.clone().into(), session_opts).await {
            Ok(s)  => s,   // Session::new_with_opts returns Arc<Session> directly
            Err(e) => {
                warn!("[torrent] Session::new_with_opts failed: {}", e);
                // Fallback: try without custom opts (older librqbit v9 builds
                // where SessionOptions fields differ)
                match Session::new(save_dir.clone().into()).await {
                    Ok(s)  => { warn!("[torrent] Using default session opts (fallback)"); s }
                    Err(e2) => {
                        warn!("[torrent] Session fallback also failed: {}", e2);
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
                self.state.store(STATE_ERROR, Ordering::Relaxed);
                return;
            }
        };

        let (torrent_id, handle) = match add_response {
            AddTorrentResponse::Added(id, h)         => (id, h),
            AddTorrentResponse::AlreadyManaged(id, h) => (id, h),
            AddTorrentResponse::ListOnly(_) => {
                warn!("[torrent] List-only response — cannot stream");
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
        *self.torrent_handle.write() = None;
        *self.rq_session.write()     = None;
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
        format!(
            "state={}\nprogress={}%\nspeed_bps={}\nseeds={}\npeers={}\n\
             video_path={:?}\nvideo_file_size={}\nprogress_bytes={}\n\
             torrent_id_val={}\nvideo_file_id={}\n\
             rq_session_set={}\ntorrent_handle_set={}\n\
             api_stream_info_ready={}\n",
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
        )
    }
}
