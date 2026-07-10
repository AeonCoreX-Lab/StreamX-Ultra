//! MovieBox direct-stream provider.
//!
//! Separate from `indexer/` (torrent search) — this module talks directly
//! to MovieBox's mobile-bff API and returns ready-to-play MP4/HLS URLs, no
//! magnet/piece-download involved. See `client.rs` for the HTTP layer,
//! `crypto.rs` for request signing, `types.rs` for wire formats.

pub mod client;
pub mod crypto;
pub mod helpers;
pub mod types;

pub use types::{
    CaptionFile, CaptionResult, DubOption, ItemDetails, SearchItem, SeasonInfo, SeasonItem,
    StreamFile, StreamResult,
};
