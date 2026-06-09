// app/src/main/rust/src/torrent/piece_picker.rs

use log::debug;
// FIX (Warning): HEADER_PIECES and TAIL_PIECES were imported but never used
// in this file. Priority logic is now handled in session.rs via byte-progress.
// Removed both to silence unused-imports warnings.

// ── PiecePicker ───────────────────────────────────────────────────────────────
// Priority zones (updated every 250 ms or on seek):
//
//  Zone         │ Pieces ahead of playhead   │ Priority
//  ─────────────┼────────────────────────────┼─────────
//  DONE/behind  │ < 0                        │ 1 (low)
//  CRITICAL     │ 0 .. +30                   │ 7 (highest)
//  HIGH         │ +30 .. +90                 │ 5
//  NORMAL       │ +90 .. +200                │ 3
//  LOW          │ > +200                     │ 1
//  HEADER       │ first 30 pieces            │ 7 (always)
//  TAIL         │ last 10 pieces             │ 6 (always)

pub struct PiecePicker {
    // FIX (Warning): total_pieces is stored by new() but never read — librqbit
    // v9 handles piece prioritisation internally; only first_piece / last_piece
    // / piece_len are consumed by the readiness check in session.rs.
    // Suppressed rather than removed so the field is available if a future
    // librqbit API exposes per-piece control again.
    #[allow(dead_code)]
    pub total_pieces:  u32,
    pub first_piece:   u32,
    pub last_piece:    u32,
    pub piece_len:     u64,
    pub duration_secs: f64,
    last_playhead:     u32,
}

impl PiecePicker {
    pub fn new(total: u32, first: u32, last: u32, piece_len: u64) -> Self {
        Self {
            total_pieces:  total,
            first_piece:   first,
            last_piece:    last,
            piece_len,
            duration_secs: 0.0,
            last_playhead: u32::MAX,
        }
    }

    // FIX (Warning): set_duration is never called from session.rs because
    // librqbit v9 metadata does not expose the video duration directly —
    // duration must come from the media demuxer (MPV) after playback starts.
    // The Kotlin side updates playhead via TorrentEngine.updatePlaybackPosition()
    // but does not push duration back to Rust.
    //
    // Consequence: duration_secs stays 0.0, so secs_to_piece() always returns
    // first_piece (safe no-op — librqbit v9 sequential download means the
    // playhead-relative priority logic is not needed anyway).
    //
    // Suppressed rather than removed; re-wire it when a future version of the
    // Kotlin bridge calls setDurationNative() after MPV reports the duration.
    #[allow(dead_code)]
    pub fn set_duration(&mut self, secs: f64) {
        self.duration_secs = secs;
    }

    pub fn playhead_piece(&self) -> u32 {
        self.last_playhead.saturating_sub(0).max(self.first_piece)
    }

    // ── Called every 250 ms ───────────────────────────────────────────────────
    // In v9, piece priority is handled automatically by librqbit's streaming system.
    // This method now only tracks the playhead position for readiness checks.
    pub fn update_priorities(&mut self, playhead_secs: f64) {
        let playhead = self.secs_to_piece(playhead_secs);

        // Skip if playhead hasn't moved more than 2 pieces
        if self.last_playhead != u32::MAX && playhead.abs_diff(self.last_playhead) < 2 {
            return;
        }
        self.last_playhead = playhead;
        debug!("PiecePicker: playhead={:.1}s piece={}", playhead_secs, playhead);
    }

    // ── secs → piece index ────────────────────────────────────────────────────
    fn secs_to_piece(&self, secs: f64) -> u32 {
        if self.duration_secs <= 0.0 || secs <= 0.0 { return self.first_piece; }
        let span  = self.last_piece - self.first_piece;
        let ratio = (secs / self.duration_secs).clamp(0.0, 1.0);
        (self.first_piece + (span as f64 * ratio) as u32).min(self.last_piece)
    }
}
