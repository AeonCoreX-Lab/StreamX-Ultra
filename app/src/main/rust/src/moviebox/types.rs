//! Wire types for MovieBox API responses.
//!
//! Field names and shapes below are taken directly from real recorded
//! responses in the `moviebox-api` reference repo's `assets/recons2/` and
//! `assets/recons/` sample JSON — NOT guessed. In particular `DubModel`
//! matches `assets/recons2/item-details-series.json`'s `dubs` array
//! byte-for-byte (subjectId / lanName / lanCode / original / type).

use serde::{Deserialize, Serialize};

// ── Dub / language ──────────────────────────────────────────────────────────

/// One entry in a subject's `dubs[]` array. Each dub is a **fully separate
/// subject_id** with its own resources/streams/captions — selecting a dub
/// means re-fetching everything downstream with `subject_id` swapped to
/// this dub's `subject_id`, not passing an extra query param.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct DubOption {
    #[serde(rename = "subjectId")]
    pub subject_id: String,
    #[serde(rename = "lanName")]
    pub lan_name_raw: String,
    #[serde(rename = "lanCode")]
    pub lan_code: String,
    pub original: bool,
    #[serde(rename = "type")]
    pub dub_type: i32,
}

impl DubOption {
    /// Mirrors the reference lib's `DubModel.validate_lan_name`:
    /// "Original Audio" -> "Original", "Hindi dub" -> "Hindi".
    pub fn display_name(&self) -> String {
        let lower = self.lan_name_raw.to_lowercase();
        if lower.starts_with("original") {
            "Original".to_string()
        } else {
            self.lan_name_raw
                .to_lowercase()
                .replace("dub", "")
                .trim()
                .to_string()
                .split_whitespace()
                .map(|w| {
                    let mut c = w.chars();
                    match c.next() {
                        Some(f) => f.to_uppercase().collect::<String>() + c.as_str(),
                        None => String::new(),
                    }
                })
                .collect::<Vec<_>>()
                .join(" ")
        }
    }
}

// ── Seasons (MovieBox's own authoritative episode counts) ──────────────────

/// One season's episode count, straight from MovieBox — NOT from TMDB.
/// Matches `SeasonItemModel` in the reference lib: `se` (season number),
/// `maxEp` (episode count for that season under THIS subject_id — matters
/// because a dub's subject_id can have a different available episode
/// count than the original). Use this, not TMDB's season metadata, to
/// decide what episode range to offer when the active subject is a dub.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SeasonItem {
    pub se: u32,
    #[serde(rename = "maxEp")]
    pub max_ep: u32,
    #[serde(rename = "allEp", default)]
    pub all_ep: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct SeasonInfo {
    #[serde(rename = "subjectId", default)]
    pub subject_id: Option<String>,
    #[serde(default)]
    pub seasons: Vec<SeasonItem>,
}

impl SeasonInfo {
    pub fn episode_count_for(&self, season: u32) -> Option<u32> {
        self.seasons.iter().find(|s| s.se == season).map(|s| s.max_ep)
    }
}

// ── Item / subject details ──────────────────────────────────────────────────

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ItemDetails {
    #[serde(rename = "subjectId")]
    pub subject_id: String,
    pub title: String,
    #[serde(rename = "detailPath", default)]
    pub detail_path: Option<String>,
    #[serde(default)]
    pub dubs: Vec<DubOption>,
    #[serde(default)]
    pub description: Option<String>,
    #[serde(default)]
    pub genre: Option<serde_json::Value>,
    #[serde(rename = "subjectType", default)]
    pub subject_type: Option<i32>,
    #[serde(flatten)]
    pub extra: serde_json::Value,
}

// ── Search ───────────────────────────────────────────────────────────────────

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SearchItem {
    pub title: String,
    #[serde(rename = "subjectId")]
    pub subject_id: String,
    #[serde(rename = "detailPath", default)]
    pub detail_path: Option<String>,
    #[serde(default)]
    pub cover: Option<Cover>,
    #[serde(rename = "subjectType", default)]
    pub subject_type: Option<i32>,
    #[serde(default)]
    pub year: Option<String>,
    /// Present directly on search results (confirmed field:
    /// `ResultsSubjectModel.hasResource` in the reference lib) — lets
    /// callers skip subjects with no playable resource at all without
    /// needing a separate item-details or stream-resolve call first.
    #[serde(rename = "hasResource", default)]
    pub has_resource: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Cover {
    pub url: Option<String>,
}

// ── Streams / resources ─────────────────────────────────────────────────────

/// One direct playable file. Real responses only ever populate `streams[]`
/// with progressive MP4s (`hls`/`dash` are consistently empty in sampled
/// data) — see `assets/recons/series/specific_episode_stream_details.json`.
/// The player must therefore treat "instant play" as "direct MP4 URL",
/// not assume HLS.
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct StreamFile {
    pub format: String,
    pub id: String,
    pub url: String,
    /// Comes back as a JSON string in some responses ("360") and a bare
    /// int/number of resolutions in others — normalize defensively.
    #[serde(deserialize_with = "crate::moviebox::helpers::de_resolution")]
    pub resolutions: u32,
    #[serde(default)]
    pub size: Option<String>,
    #[serde(default)]
    pub duration: Option<i64>,
    #[serde(rename = "codecName", default)]
    pub codec_name: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct StreamResult {
    #[serde(rename = "subjectId")]
    pub subject_id: String,
    pub se: u32,
    pub ep: u32,
    #[serde(rename = "hasResource")]
    pub has_resource: bool,
    pub sources: Vec<StreamFile>,
    #[serde(default)]
    pub hls: Vec<serde_json::Value>,
    #[serde(default)]
    pub dash: Vec<serde_json::Value>,
    #[serde(rename = "freeEpisodes")]
    pub free_episodes: Option<i64>,
    pub limited: bool,
    pub note: Option<String>,
}

impl StreamResult {
    /// Highest-resolution direct MP4, or the first HLS/DASH manifest URL if
    /// (rarely) present. Returns `None` if nothing playable was found.
    pub fn best_playable_url(&self) -> Option<String> {
        if let Some(hls) = self.hls.first() {
            if let Some(u) = hls.get("url").and_then(|v| v.as_str()) {
                return Some(u.to_string());
            }
        }
        self.sources
            .iter()
            .max_by_key(|s| s.resolutions)
            .map(|s| s.url.clone())
    }
}

// ── Captions ─────────────────────────────────────────────────────────────────

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CaptionFile {
    pub id: String,
    pub lan: String,
    #[serde(rename = "lanName")]
    pub lan_name: String,
    pub url: String,
    #[serde(default)]
    pub size: Option<i64>,
    #[serde(default)]
    pub delay: Option<i64>,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct CaptionResult {
    #[serde(rename = "extCaptions", default)]
    pub ext_captions: Vec<CaptionFile>,
    #[serde(rename = "subjectId", default)]
    pub subject_id: Option<String>,
}
