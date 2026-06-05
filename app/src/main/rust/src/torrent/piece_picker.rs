// app/src/main/rust/src/torrent/piece_picker.rs

use librqbit::ManagedTorrentHandle;
use log::debug;
use super::{CRITICAL_AHEAD_PIECES, HIGH_AHEAD_PIECES, HEADER_PIECES, TAIL_PIECES};

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

    pub fn set_duration(&mut self, secs: f64) {
        self.duration_secs = secs;
    }

    pub fn playhead_piece(&self) -> u32 {
        self.last_playhead.saturating_sub(0).max(self.first_piece)
    }

    // ── Called every 250 ms ───────────────────────────────────────────────────
    pub fn update_priorities(&mut self, handle: &ManagedTorrentHandle, playhead_secs: f64) {
        let playhead = self.secs_to_piece(playhead_secs);

        // Skip if playhead hasn't moved more than 2 pieces
        if self.last_playhead != u32::MAX && playhead.abs_diff(self.last_playhead) < 2 {
            return;
        }
        self.last_playhead = playhead;
        debug!("PiecePicker: playhead={:.1}s piece={}", playhead_secs, playhead);

        // Assign priorities across the whole file
        for piece in self.first_piece..=self.last_piece {
            let prio = self.priority_for(piece, playhead);
            let _ = handle.set_piece_priority(piece as usize, prio);
        }

        // Always re-enforce header + tail at max
        for i in 0..HEADER_PIECES.min(self.last_piece - self.first_piece + 1) {
            let _ = handle.set_piece_priority((self.first_piece + i) as usize, 7);
        }
        for i in 0..TAIL_PIECES.min(self.last_piece - self.first_piece + 1) {
            let _ = handle.set_piece_priority((self.last_piece - i) as usize, 6);
        }
    }

    // ── Priority table ────────────────────────────────────────────────────────
    fn priority_for(&self, piece: u32, playhead: u32) -> u8 {
        if piece < playhead.saturating_sub(5) { return 1; }
        let ahead = piece.saturating_sub(playhead);
        match ahead {
            0..=29    => 7,
            30..=89   => 5,
            90..=199  => 3,
            _         => 1,
        }
    }

    // ── secs → piece index ────────────────────────────────────────────────────
    fn secs_to_piece(&self, secs: f64) -> u32 {
        if self.duration_secs <= 0.0 || secs <= 0.0 { return self.first_piece; }
        let span  = self.last_piece - self.first_piece;
        let ratio = (secs / self.duration_secs).clamp(0.0, 1.0);
        (self.first_piece + (span as f64 * ratio) as u32).min(self.last_piece)
    }
}
