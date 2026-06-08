// app/src/main/rust/src/torrent/session.rs

use std::sync::Arc;
// FIX: Added AtomicI64 — was missing from import, used by `speed` field
use std::sync::atomic::{AtomicI32, AtomicI64, AtomicU64, Ordering};
use parking_lot::RwLock;
use tokio::time::{sleep, Duration};
use log::{info, warn};
// FIX: Added AddTorrentResponse — needed to pattern-match the return of add_torrent()
use librqbit::{
    AddTorrent, AddTorrentOptions, AddTorrentResponse, Session, SessionOptions,
};

use super::piece_picker::PiecePicker;
use super::{
    CRITICAL_AHEAD_PIECES, HEADER_PIECES,
    MIN_READY_CRITICAL, TAIL_PIECES,
};

// ── State codes (same as C++ TorrentSystem: 0-4) ─────────────────────────────
// TorrentEngine.kt reads these as-is — unchanged.
pub const STATE_IDLE:      i32 = 0;
pub const STATE_METADATA:  i32 = 1;
pub const STATE_BUFFERING: i32 = 2;
pub const STATE_READY:     i32 = 3;
pub const STATE_ERROR:     i32 = 4;

// ── TorrentStatus — returned to Kotlin via JNI ────────────────────────────────
// Fields match the jlongArray[5] the old C++ getStatusNative() returned:
//   [0]=progress [1]=speed [2]=seeds [3]=peers [4]=state
// Plus video_path (returned by getFilePathNative).
#[derive(Debug, Clone, Default)]
pub struct TorrentStatus {
    pub progress:   i32,
    pub speed_bps:  i64,
    pub seeds:      i32,
    pub peers:      i32,
    pub state:      i32,
    pub video_path: String,
}

// ── TorrentSession ────────────────────────────────────────────────────────────
pub struct TorrentSession {
    state:         AtomicI32,
    progress:      AtomicI32,
    speed:         AtomicI64,   // FIX: AtomicI64 now correctly imported
    seeds:         AtomicI32,
    peers:         AtomicI32,
    playhead_bits: AtomicU64,        // f64 stored as u64 bits
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
            speed:         AtomicI64::new(0),   // FIX: AtomicI64 now correctly imported
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

        // Create librqbit session
        let rq_session = match Session::new_with_opts(
            save_dir.clone().into(),
            SessionOptions { disable_dht: false, ..Default::default() }
        ).await {
            Ok(s)  => Arc::new(s),
            Err(e) => { warn!("Session error: {}", e); self.state.store(STATE_ERROR, Ordering::Relaxed); return; }
        };

        // Add torrent
        // FIX: opts.trackers field was removed in librqbit v4 — trackers in magnet URL are
        //      used automatically. Only keep fields that exist on AddTorrentOptions v4.
        let mut opts   = AddTorrentOptions::default();
        opts.overwrite = true;

        // FIX: add_torrent() returns AddTorrentResponse (an enum in librqbit v4).
        //      Pattern-match to extract the actual torrent handle from the enum variant.
        let add_response = match rq_session.add_torrent(AddTorrent::from_url(&magnet), Some(opts)).await {
            Ok(r)  => r,
            Err(e) => { warn!("Add torrent: {}", e); self.state.store(STATE_ERROR, Ordering::Relaxed); return; }
        };

        // Extract inner ManagedTorrentHandle from the response enum
        let handle = match add_response {
            AddTorrentResponse::Added(_, h) | AddTorrentResponse::AlreadyManaged(_, h) => h,
            AddTorrentResponse::ListOnly(_) => {
                warn!("Torrent returned list-only response — cannot start playback");
                self.state.store(STATE_ERROR, Ordering::Relaxed);
                return;
            }
        };

        let mut picker_opt:  Option<PiecePicker> = None;
        let mut metadata_ok  = false;
        let mut stop_rx      = self.stop_rx.clone();

        // ── Monitor loop (replaces C++ updateLoop) ────────────────────────────
        loop {
            tokio::select! {
                _ = stop_rx.changed() => { if *stop_rx.borrow() { break; } }
                _ = sleep(Duration::from_millis(250)) => {}
            }

            let stats = handle.stats();

            // Update shared atomics
            let pct   = (stats.progress_bytes as f64 / stats.total_bytes.max(1) as f64 * 100.0) as i32;
            self.progress.store(pct, Ordering::Relaxed);
            self.peers.store(stats.live_peers as i32, Ordering::Relaxed);

            if !metadata_ok {
                // Waiting for metadata
                self.state.store(STATE_METADATA, Ordering::Relaxed);
                if handle.shared().info.get().is_some() {
                    metadata_ok = true;
                    self.state.store(STATE_BUFFERING, Ordering::Relaxed);

                    // Identify video file + build piece picker
                    if let Some(info) = handle.shared().info.get() {
                        let files    = &info.info.files;
                        let (largest_idx, largest_size) = files.iter().enumerate()
                            .max_by_key(|(_, f)| f.len)
                            .map(|(i, f)| (i, f.len))
                            .unwrap_or((0, 0));

                        let path = format!("{}/{}", save_dir, files[largest_idx].name);
                        *self.video_path.write() = path.clone();
                        info!("Video file: {} ({} bytes)", path, largest_size);

                        let total_pieces = info.pieces.len() as u32;
                        let piece_len    = info.info.piece_length as u64;
                        let file_offset: u64 = files[..largest_idx].iter().map(|f| f.len).sum();
                        let first_piece  = (file_offset / piece_len) as u32;
                        let last_piece   = ((file_offset + largest_size) / piece_len).min(total_pieces - 1) as u32;

                        let picker = PiecePicker::new(total_pieces, first_piece, last_piece, piece_len);

                        // Prioritise header + tail immediately
                        for i in 0..HEADER_PIECES.min(last_piece - first_piece + 1) {
                            let _ = handle.set_piece_priority((first_piece + i) as usize, 7);
                        }
                        for i in 0..TAIL_PIECES.min(last_piece - first_piece + 1) {
                            let _ = handle.set_piece_priority((last_piece - i) as usize, 6);
                        }

                        picker_opt = Some(picker);
                    }
                }
                continue;
            }

            // ── Downloading phase ─────────────────────────────────────────────
            let playhead_secs = f64::from_bits(self.playhead_bits.load(Ordering::Relaxed));

            if let Some(picker) = picker_opt.as_mut() {
                // FIX: update_priorities now takes a callback — avoids naming the
                //      ManagedTorrentHandle type that isn't re-exported in librqbit v4.0.1 root
                picker.update_priorities(playhead_secs, |p, prio| {
                    let _ = handle.set_piece_priority(p, prio);
                });

                // Check ready gate (matches C++ logic exactly)
                let header_ok = (picker.first_piece..picker.first_piece + HEADER_PIECES.min(picker.last_piece - picker.first_piece + 1))
                    .all(|p| handle.have_piece(p as usize));

                let critical_start = picker.playhead_piece().max(picker.first_piece);
                let critical_end   = (critical_start + CRITICAL_AHEAD_PIECES).min(picker.last_piece);
                let critical_have  = (critical_start..critical_end)
                    .filter(|&p| handle.have_piece(p as usize))
                    .count() as u32;

                let progress_ok = pct >= 3;   // same threshold as C++ MIN_PROGRESS

                if header_ok && critical_have >= MIN_READY_CRITICAL && progress_ok {
                    self.state.store(STATE_READY, Ordering::Relaxed);
                } else {
                    self.state.store(STATE_BUFFERING, Ordering::Relaxed);
                }
            }
        }

        info!("Session loop ended");
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