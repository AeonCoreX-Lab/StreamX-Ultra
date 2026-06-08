// app/src/main/rust/src/torrent/session.rs

use std::sync::Arc;
use std::sync::atomic::{AtomicI32, AtomicI64, AtomicU64, Ordering};
use parking_lot::RwLock;
use tokio::time::{sleep, Duration};
use log::{info, warn};
use librqbit::{
    AddTorrent, AddTorrentOptions, AddTorrentResponse, Session,
};

use super::piece_picker::PiecePicker;
use super::{
    CRITICAL_AHEAD_PIECES, HEADER_PIECES,
    MIN_READY_CRITICAL, TAIL_PIECES,
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
    pub seeds:      i32,
    pub peers:      i32,
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

        // Create librqbit session (v9 API - simpler constructor)
        let rq_session = match Session::new(save_dir.clone().into()).await {
            Ok(s)  => Arc::new(s),
            Err(e) => { warn!("Session error: {}", e); self.state.store(STATE_ERROR, Ordering::Relaxed); return; }
        };

        // Add torrent (v9 API - trackers handled automatically)
        let mut opts   = AddTorrentOptions::default();
        opts.overwrite = true;

        let add_response = match rq_session.add_torrent(AddTorrent::from_url(&magnet), Some(opts)).await {
            Ok(r)  => r,
            Err(e) => { warn!("Add torrent: {}", e); self.state.store(STATE_ERROR, Ordering::Relaxed); return; }
        };

        // Extract handle from response (v9 API - pattern match on enum)
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

            // Update shared atomics (v9 API - stats.live is Option<LiveStats>)
            let pct = if stats.total_bytes > 0 {
                (stats.progress_bytes as f64 / stats.total_bytes as f64 * 100.0) as i32
            } else { 0 };
            self.progress.store(pct, Ordering::Relaxed);

            // v9 API: peer count from live stats snapshot
            let peer_count = stats.live.as_ref()
                .map(|l| l.snapshot.peer_stats.len() as i32)
                .unwrap_or(0);
            self.peers.store(peer_count, Ordering::Relaxed);

            // v9 API: speed from live stats
            let speed = stats.live.as_ref()
                .map(|l| l.download_speed.as_bytes() as i64)
                .unwrap_or(0);
            self.speed.store(speed, Ordering::Relaxed);

            if !metadata_ok {
                // Waiting for metadata (v9 API - use with_metadata)
                self.state.store(STATE_METADATA, Ordering::Relaxed);

                let has_metadata = handle.with_metadata(|_| ()).is_ok();
                if has_metadata {
                    metadata_ok = true;
                    self.state.store(STATE_BUFFERING, Ordering::Relaxed);

                    // Identify video file + build piece picker (v9 API)
                    let _ = handle.with_metadata(|metadata| {
                        let files = &metadata.file_infos;
                        let (largest_idx, largest_size) = files.iter().enumerate()
                            .max_by_key(|(_, f)| f.len)
                            .map(|(i, f)| (i, f.len))
                            .unwrap_or((0, 0));

                        let path = format!("{}/{}", save_dir, files[largest_idx].relative_filename.display());
                        *self.video_path.write() = path.clone();
                        info!("Video file: {} ({} bytes)", path, largest_size);

                        let total_pieces = metadata.lengths().total_pieces() as u32;
                        let piece_len    = metadata.lengths().default_piece_length() as u64;
                        let file_offset: u64 = files[..largest_idx].iter().map(|f| f.len).sum();
                        let first_piece  = (file_offset / piece_len) as u32;
                        let last_piece   = ((file_offset + largest_size) / piece_len).min(total_pieces - 1) as u32;

                        let picker = PiecePicker::new(total_pieces, first_piece, last_piece, piece_len);
                        picker_opt = Some(picker);
                    });
                }
                continue;
            }

            // ── Downloading phase ─────────────────────────────────────────────
            let playhead_secs = f64::from_bits(self.playhead_bits.load(Ordering::Relaxed));

            if let Some(ref mut picker) = picker_opt {
                // v9 API: Check piece availability via with_chunk_tracker
                let header_ok = {
                    let header_range = picker.first_piece..picker.first_piece + HEADER_PIECES.min(picker.last_piece - picker.first_piece + 1);
                    handle.with_chunk_tracker(|ct| {
                        header_range.all(|p| {
                            let idx = p as usize;
                            idx < ct.get_have_pieces().as_slice().len() && ct.get_have_pieces().as_slice()[idx]
                        })
                    }).unwrap_or(false)
                };

                let critical_start = picker.playhead_piece().max(picker.first_piece);
                let critical_end   = (critical_start + CRITICAL_AHEAD_PIECES).min(picker.last_piece);
                let critical_have  = {
                    let range = critical_start..critical_end;
                    handle.with_chunk_tracker(|ct| {
                        let have = ct.get_have_pieces().as_slice();
                        range.filter(|&p| {
                            let idx = p as usize;
                            idx < have.len() && have[idx]
                        }).count() as u32
                    }).unwrap_or(0)
                };

                let progress_ok = pct >= 3;

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
