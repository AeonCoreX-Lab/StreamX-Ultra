// src/indexer/config/schema.rs
//
// Rust types mirroring the remote indexer-config.json schema.
// See docs/indexer-config.example.json in this repo for the canonical
// example this was designed against.

use serde::{Deserialize, Serialize};
use std::collections::HashMap;

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct IndexerConfig {
    pub schema_version: u32,
    #[serde(default)]
    pub updated: String,
    pub sites: HashMap<String, SiteConfig>,
    /// Lightweight overrides for sites whose scraping logic is too
    /// bespoke for the generic HTML/JSON engine (regex-based infohash
    /// extraction, multi-category queries, placeholder seed handling —
    /// see kdrama.rs, nyaa.rs, tokyotosho.rs). These still get their
    /// MIRRORS and enabled/disabled state from the remote config, just
    /// not their full selector set — a dead Korean-tracker domain or a
    /// broken Nyaa mirror is still fixable without an APK release, even
    /// though a changed CSS selector on those specific sites isn't.
    #[serde(default)]
    pub special_sites: HashMap<String, SpecialSiteOverride>,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct SpecialSiteOverride {
    #[serde(default = "default_true")]
    pub enabled: bool,
    #[serde(default)]
    pub mirrors: Vec<String>,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct SiteConfig {
    #[serde(default = "default_true")]
    pub enabled: bool,
    pub display_name: String,
    pub kind: SiteKind,
    pub mirrors: Vec<String>,
    pub search_path: String,
    #[serde(default)]
    pub imdb_path: Option<String>,
    #[serde(default = "default_one")]
    pub pages: u32,
    #[serde(default)]
    pub selectors: Option<HtmlSelectors>,
    #[serde(default)]
    pub json_fields: Option<JsonFields>,
    #[serde(default)]
    pub request: RequestConfig,
}

fn default_true() -> bool { true }
fn default_one() -> u32 { 1 }

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum SiteKind {
    Html,
    Json,
}

/// CSS selector set for an HTML-scraped site. Fields are plain strings so
/// they can be edited in the remote JSON without touching Rust code;
/// `Selector::parse()` is called at request time, not at deploy time, so
/// a bad selector fails that one site's fetch rather than the build.
#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct HtmlSelectors {
    pub row: String,
    pub title: String,
    /// "text" = use element text content; anything else = read that attribute
    #[serde(default = "default_text")]
    pub title_attr: String,

    // Listing-page magnet (used when magnet_location == "listing")
    #[serde(default)]
    pub magnet: Option<String>,
    #[serde(default)]
    pub magnet_attr: Option<String>,
    /// If set, the magnet isn't the attribute value directly — it's
    /// URL-encoded inside a querystring parameter of that attribute's
    /// value (e.g. kickass.ws wraps magnets in a redirector href like
    /// "/download?url=magnet%3A...". Set this to the param name ("url")
    /// to extract and decode it instead of using the raw attr value.
    #[serde(default)]
    pub magnet_querystring_param: Option<String>,

    #[serde(default = "default_listing")]
    pub magnet_location: String, // "listing" | "detail"

    // Detail-page magnet (used when magnet_location == "detail")
    #[serde(default)]
    pub detail_link: Option<String>,
    #[serde(default)]
    pub detail_link_attr: Option<String>,
    #[serde(default)]
    pub detail_magnet_selector: Option<String>,
    #[serde(default)]
    pub detail_magnet_selector_fallback: Option<String>,

    pub size: String,
    pub seeds: String,
    #[serde(default)]
    pub seeds_index: usize,
    pub peers: String,
    #[serde(default)]
    pub peers_index: usize,

    /// Optional: when the visible title text is truncated by the site
    /// (1337x renders long titles ending in "..." and expects you to
    /// visit the detail page for the full name — see Jackett's
    /// 1337x.yml title_optional field), this selector points at the
    /// SAME anchor's href instead, from which the real title is decoded
    /// via title_fallback_href_segment. Only used when the title read
    /// via `title`/`title_attr` ends in the literal string "...".
    #[serde(default)]
    pub title_fallback_href_selector: Option<String>,
    /// 0-indexed path segment (split on "/") of the href to treat as
    /// the URL-encoded full title. 1337x's detail links look like
    /// "/torrent/1234567/Movie-Name-2024-1080p-BluRay-x264-GROUP/" —
    /// the title is segment 3 (0: "", 1: "torrent", 2: "1234567",
    /// 3: the slug). Matches Jackett's `split` filter args: ["/", 3].
    #[serde(default = "default_title_fallback_segment")]
    pub title_fallback_href_segment: usize,

    /// Optional: site's own category label selector, folded into
    /// audio_tags if it hints at a dub/region we'd otherwise miss
    /// (e.g. ExtraTorrent's "in Bollywood" category span).
    #[serde(default)]
    pub category: Option<String>,
}

fn default_text() -> String { "text".to_string() }
fn default_listing() -> String { "listing".to_string() }
fn default_title_fallback_segment() -> usize { 3 }

/// Field-name map for a JSON-API site (TheRARBG-style). Values are the
/// JSON key names in that site's own response shape.
#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct JsonFields {
    /// Dotted path to the array of results, e.g. "results" or "data.items".
    /// Empty string means the response body IS the array.
    #[serde(default)]
    pub results_array: String,
    pub title: String,
    /// Either "infohash" (raw hex hash, we build the magnet) or "magnet"
    /// (field already contains a full magnet: URI)
    pub infohash: String,
    #[serde(default)]
    pub infohash_is_full_magnet: bool,
    pub size: String,
    pub seeds: String,
    pub peers: String,
    #[serde(default)]
    pub category: Option<String>,
    #[serde(default)]
    pub imdb: Option<String>,
    /// If true, apply Jackett-equivalent query cleanup before encoding
    /// the query into the search path: strip standalone "it's", collapse
    /// runs of CJK (Chinese/Japanese/Korean) characters (plus any
    /// adjacent non-word punctuation) into a single ".", and lowercase
    /// the result. Mirrors thepiratebay.yml's keywordsfilters — apibay's
    /// search engine handles both cases poorly untreated. Opt-in per
    /// site (not every JSON site necessarily needs or wants this).
    #[serde(default)]
    pub apply_tpb_query_cleanup: bool,
}

#[derive(Debug, Clone, Default, Deserialize, Serialize)]
pub struct RequestConfig {
    #[serde(default)]
    pub headers: HashMap<String, String>,
    #[serde(default)]
    pub delay_ms: u64,
    #[serde(default)]
    pub min_seeds_for_detail_fetch: Option<u32>,
}
