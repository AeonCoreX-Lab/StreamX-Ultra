// app/src/main/rust/src/torrent/mod.rs

pub mod engine;
pub mod session;
pub mod piece_picker;
pub mod http_server;

pub use engine::{TorrentEngineHandle, TorrentEngine};
pub use session::{TorrentSession, TorrentStatus};

// ── Piece prioritisation constants ────────────────────────────────────────────
pub const CRITICAL_AHEAD_PIECES: u32 = 30;    // highest priority zone
pub const HIGH_AHEAD_PIECES:     u32 = 90;    // high priority zone
pub const BUFFER_AHEAD_PIECES:   u32 = 200;   // ~60s at typical bitrate
pub const HEADER_PIECES:         u32 = 30;    // first N pieces for container header
pub const TAIL_PIECES:           u32 = 10;    // last N for duration detection
pub const MIN_READY_CRITICAL:    u32 = 20;    // pieces needed before signalling READY
pub const LOCAL_HTTP_PORT:       u16 = 8088;  // same port Ktor used — MPV unchanged

// ── Public trackers (same list as C++ torrent-engine.cpp) ────────────────────
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
