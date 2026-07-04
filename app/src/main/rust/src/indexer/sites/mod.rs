// src/indexer/sites/mod.rs
//
// Only sites whose scraping logic is too bespoke for the generic
// config-driven engine (indexer/config/generic_html.rs and
// generic_json.rs) live here now:
//   • kdrama  — regex infohash extraction, placeholder seed handling
//   • nyaa    — multi-category queries with per-category tag folding
//   • tokyotosho — pipe-delimited size/date field, optional magnet
//
// Every other site (1337x, TorrentGalaxy, KAT, KAT-WS, TorrentDownload,
// ExtraTorrent, TheRARBG, ThePirateBay) is now driven entirely by the
// remote indexer-config.json — see indexer/config/mod.rs.

pub mod kdrama;
pub mod eztvco;
pub mod nyaa;
pub mod tokyotosho;
