// app/src/main/rust/src/torrent/mod.rs

pub mod engine;
pub mod session;
pub mod piece_picker;
pub mod http_server;

// FIX (Warning): These pub-use re-exports were flagged as unused because:
//   • lib.rs imports TorrentEngineHandle directly via `use torrent::engine::TorrentEngineHandle`
//   • http_server.rs imports TorrentSession via `use super::session::TorrentSession`
//   • Nothing accesses these through the torrent:: re-export path
// Removed to eliminate "unused imports" warnings. Internal modules still import
// from each other via their direct paths (super::session, super::engine etc.).

// ── Piece prioritisation constants ────────────────────────────────────────────
//
// WHY #[allow(dead_code)] on some constants:
//   librqbit v9 performs sequential downloading and handles piece prioritisation
//   internally. The priority-zone logic that used HIGH_AHEAD_PIECES,
//   BUFFER_AHEAD_PIECES, TAIL_PIECES, and TRACKERS was removed in the v9
//   migration (session.rs uses byte-progress readiness instead).
//   The constants are kept here as configuration documentation — they describe
//   the intended streaming buffer zones — so CI warnings are suppressed rather
//   than deleting them outright.
//
pub const CRITICAL_AHEAD_PIECES: u32 = 30;    // highest priority zone (used in session.rs)
pub const HEADER_PIECES:         u32 = 30;    // first N pieces for container header (used in session.rs)
pub const MIN_READY_CRITICAL:    u32 = 20;    // pieces needed before signalling READY (used in session.rs)
pub const LOCAL_HTTP_PORT:       u16 = 8088;  // same port Ktor used — MPV unchanged (used in engine.rs)

// ── Network-adaptive READY threshold (used in session.rs) ─────────────────────
// See the FIX (network-adaptive READY threshold) comment at the call site in
// session.rs for the full reasoning. Summary: instead of always requiring a
// fixed MIN_READY_CRITICAL pieces before declaring READY, the actual
// requirement is computed from the CURRENT observed download speed, so slow
// connections wait for a genuinely sufficient buffer and fast connections
// aren't held back by an unnecessarily large fixed floor.
//
// TARGET_READY_BUFFER_SECS: how many seconds' worth of data (at current
// speed) we want buffered ahead of the playhead before calling it READY.
// This is deliberately close to MPV's own demuxer-readahead-secs=8 (see
// mpv_handler.cpp) — the two numbers represent the same real requirement
// (MPV wants ~8s buffered ahead), so Rust's readiness signal and MPV's own
// buffering behavior are pointed at the same target instead of being tuned
// independently and potentially disagreeing.
pub const TARGET_READY_BUFFER_SECS: f64 = 8.0;

// MIN_READY_CRITICAL now doubles as both the fast-connection baseline and
// the floor of the adaptive scale-up (see session.rs) — no separate floor
// constant needed; CRITICAL_AHEAD_PIECES serves as the natural ceiling.

#[allow(dead_code)]
pub const HIGH_AHEAD_PIECES:     u32 = 90;    // high priority zone

#[allow(dead_code)]
pub const BUFFER_AHEAD_PIECES:   u32 = 200;   // ~60s at typical bitrate

#[allow(dead_code)]
pub const TAIL_PIECES:           u32 = 10;    // last N for duration detection

// ── Public trackers (same list as C++ torrent-engine.cpp) ────────────────────
//
// FIX (Warning): TRACKERS is unused because librqbit v9 announces to trackers
// automatically from the magnet link; explicit tracker injection is no longer
// needed.  Kept for reference so the list can be restored if a future librqbit
// API exposes add_tracker().
//
#[allow(dead_code)]
pub const TRACKERS: &[&str] = &[
    "http://tracker.bt4g.com:2095/announce",
    "https://tracker.bt4g.com:443/announce",
    "https://tracker.nanoha.org:443/announce",
    "udp://tracker.opentrackr.org:1337/announce",
    "udp://open.demonii.com:1337/announce",
    "udp://tracker.openbittorrent.com:80/announce",
    "udp://tracker.torrent.eu.org:451/announce",
    "udp://tracker.leechers-paradise.org:6969/announce",
    "udp://exodus.desync.com:6969/announce",
    "udp://tracker.moeking.me:6969/announce",
];
