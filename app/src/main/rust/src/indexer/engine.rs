// src/indexer/engine.rs
//
// Orchestrates parallel search across all indexer sites and merges results.
// This is the ONLY entry point lib.rs (JNI layer) should call.
//
// IMPORTANT: This module does NOT touch torrent::engine or MPV in any way.
// It only produces magnet URIs. The existing TorrentEngine::start(magnet, dir)
// pipeline (unchanged) is what actually downloads/streams — see lib.rs
// Java_..._TorrentEngine_startNative, which Kotlin calls separately once the
// user picks a result from this search.
//
// ── Dynamic config (no-APK-update site fixes) ─────────────────────────────
// Site mirrors, CSS selectors, and enabled/disabled state are no longer
// hardcoded here. They're loaded from a remote JSON file
// (AeonCoreX-Lab/streamx-addons: indexer-config.json) via
// config::loader::get_config(), cached on disk, and fall back to a
// bundled compile-time snapshot if the network is unreachable. Every
// search_*() function below fetches the current config once at the top
// and threads it through config::search_sites() / search_site() calls —
// see indexer/config/mod.rs for the dispatch logic and
// indexer/config/generic_html.rs + generic_json.rs for the actual
// config-driven scrapers.
//
// Three sites (TorrentQQ, Torrentsome, Nyaa, Tokyo Toshokan) have scraping
// logic too bespoke for the generic engine (regex infohash extraction,
// multi-category queries, placeholder seed handling) — they keep their
// hand-written modules in indexer/sites/, but still pull their MIRROR
// list from the remote config's `special_sites` block, so a dead domain
// there is still fixable without a release.

use once_cell::sync::Lazy;
use std::path::PathBuf;
use std::time::Duration;

use super::config::{self, schema::IndexerConfig};
use super::sites;
use super::types::TorrentResult;

// Shared reqwest client — reused across all site calls (connection pooling)
static HTTP: Lazy<reqwest::Client> = Lazy::new(|| {
    reqwest::Client::builder()
        .timeout(Duration::from_secs(15))
        .user_agent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
        .build()
        .expect("failed to build indexer HTTP client")
});

/// Where the config cache file lives on disk. Set once at startup from
/// Kotlin's Context.cacheDir via lib.rs — see indexer::config::init().
static CACHE_DIR: Lazy<parking_lot::RwLock<Option<PathBuf>>> =
    Lazy::new(|| parking_lot::RwLock::new(None));

/// Called once from lib.rs at native init time with the app's cache
/// directory (Android's Context.cacheDir), so the config loader has
/// somewhere to persist the fetched JSON between launches.
pub fn init_cache_dir(dir: PathBuf) {
    *CACHE_DIR.write() = Some(dir);
}

fn cache_dir() -> PathBuf {
    CACHE_DIR.read().clone().unwrap_or_else(std::env::temp_dir)
}

async fn get_config() -> IndexerConfig {
    config::loader::get_config(&cache_dir()).await
}

/// Mirror list for a "special" (non-generic-engine) site, pulled from the
/// remote config's special_sites block. Empty Vec if disabled or absent
/// — callers' own hardcoded FALLBACK constants cover that case.
fn special_mirrors(config: &IndexerConfig, site_id: &str) -> Vec<String> {
    match config.special_sites.get(site_id) {
        Some(o) if o.enabled => o.mirrors.clone(),
        Some(_) => vec![], // explicitly disabled — empty list, caller's
                            // fallback const still applies, but see
                            // is_special_site_enabled() below for the
                            // actual skip logic.
        None => vec![],
    }
}

fn is_special_site_enabled(config: &IndexerConfig, site_id: &str) -> bool {
    config.special_sites.get(site_id).map(|o| o.enabled).unwrap_or(true)
}

// ── Universal fallback (1337x) ────────────────────────────────────────────────
//
// MovieBox's own indexer treats 1337x as the catch-all: whatever the
// category — movie, series, anime, any dub language — if the dedicated
// sources for that category come up short, 1337x gets queried broadly
// (no category restriction) as a last resort, since it's simply the
// largest general-purpose library of the sites we cover.
const FALLBACK_MIN_RESULTS: usize = 3;

/// Query 1337x broadly (plain keyword, no dub/category filter) and merge
/// it in only if `existing` doesn't already have enough results.
async fn with_1337x_fallback(
    client:   &reqwest::Client,
    config:   &IndexerConfig,
    query:    &str,
    existing: Vec<TorrentResult>,
) -> Vec<TorrentResult> {
    if existing.len() >= FALLBACK_MIN_RESULTS {
        return existing;
    }
    log::info!(
        "[fallback] only {} result(s) from dedicated sources for \"{}\" — \
         querying 1337x broadly",
        existing.len(), query
    );
    let mut merged = existing;
    merged.extend(config::search_site(client, config, "x1337x", query, None).await);
    merged
}

/// Search all sites for dubbed/dual-audio results matching `query`.
pub async fn search_dubbed(query: &str, imdb_id: Option<&str>) -> Vec<TorrentResult> {
    let client = &*HTTP;
    let config = get_config().await;

    // Dedicated sources run first, in parallel, via the config-driven
    // dispatcher. "x1337x" is deliberately excluded — see fallback below.
    let dedicated_ids = ["tgx", "kat", "torrentdownload", "extratorrent", "therarbg", "tpb", "kat_ws"];
    let mut merged = config::search_sites(client, &config, &dedicated_ids, query, imdb_id).await;

    // Keep only results that actually carry a dubbed/dual-audio tag —
    // not every site pre-filters, so we enforce it here as the final gate.
    merged.retain(|r| r.is_dubbed());

    // 1337x fallback — broad query, then re-filter to dubbed only.
    let mut merged = with_1337x_fallback(client, &config, query, merged).await;
    merged.retain(|r| r.is_dubbed());

    dedupe_and_sort(merged)
}

/// Plain keyword search across all sites, no dub filtering.
pub async fn search_all(query: &str) -> Vec<TorrentResult> {
    let client = &*HTTP;
    let config = get_config().await;

    let dedicated_ids = ["tgx", "kat", "torrentdownload", "extratorrent", "therarbg", "tpb", "kat_ws"];
    let merged = config::search_sites(client, &config, &dedicated_ids, query, None).await;

    let merged = with_1337x_fallback(client, &config, query, merged).await;

    dedupe_and_sort(merged)
}

// ── Merge helpers ──────────────────────────────────────────────────────────────

/// Remove near-duplicate releases and sort by seeds descending.
fn dedupe_and_sort(mut results: Vec<TorrentResult>) -> Vec<TorrentResult> {
    use std::collections::HashSet;

    let mut seen_magnets: HashSet<String> = HashSet::new();
    let mut seen_signature: HashSet<(String, String)> = HashSet::new();

    results.retain(|r| {
        let hash = extract_btih(&r.magnet).unwrap_or_else(|| r.magnet.clone());
        if !seen_magnets.insert(hash) {
            return false;
        }
        let sig = (r.title.to_lowercase(), r.size.clone());
        seen_signature.insert(sig)
    });

    results.sort_by(|a, b| b.seeds.cmp(&a.seeds));
    results
}

fn extract_btih(magnet: &str) -> Option<String> {
    let marker = "btih:";
    let start = magnet.find(marker)? + marker.len();
    let rest = &magnet[start..];
    let end = rest.find('&').unwrap_or(rest.len());
    Some(rest[..end].to_lowercase())
}

pub async fn search_dubbed_json(query: &str, imdb_id: Option<&str>) -> String {
    let results = search_dubbed(query, imdb_id).await;
    serde_json::to_string(&results).unwrap_or_else(|_| "[]".to_string())
}

pub async fn search_all_json(query: &str) -> String {
    let results = search_all(query).await;
    serde_json::to_string(&results).unwrap_or_else(|_| "[]".to_string())
}

// ── Drama (K-drama / C-drama / Turkish drama) ────────────────────────────────

pub async fn search_drama(query: &str) -> Vec<TorrentResult> {
    let client = &*HTTP;
    let config = get_config().await;

    let dedicated_ids = ["tgx", "kat", "torrentdownload", "extratorrent", "therarbg", "tpb", "kat_ws"];
    let mut merged = config::search_sites(client, &config, &dedicated_ids, query, None).await;

    if is_special_site_enabled(&config, "torrentqq") {
        let mirrors = special_mirrors(&config, "torrentqq");
        merged.extend(sites::kdrama::search_torrentqq(client, &mirrors, query).await);
    }
    if is_special_site_enabled(&config, "torrentsome") {
        let mirrors = special_mirrors(&config, "torrentsome");
        merged.extend(sites::kdrama::search_torrentsome(client, &mirrors, query).await);
    }
    if is_special_site_enabled(&config, "torrenttip") {
        let mirrors = special_mirrors(&config, "torrenttip");
        merged.extend(sites::kdrama::search_torrenttip(client, &mirrors, query).await);
    }

    let merged = with_1337x_fallback(client, &config, query, merged).await;

    dedupe_and_sort(merged)
}

pub async fn search_drama_english(query: &str) -> Vec<TorrentResult> {
    let mut results = search_drama(query).await;
    results.retain(|r| {
        r.audio_tags.iter().any(|t| t == "English Dub" || t == "English Sub")
    });
    results
}

pub async fn search_drama_json(query: &str) -> String {
    let results = search_drama(query).await;
    serde_json::to_string(&results).unwrap_or_else(|_| "[]".to_string())
}

// ── Anime ──────────────────────────────────────────────────────────────────

pub async fn search_anime_english(query: &str) -> Vec<TorrentResult> {
    let client = &*HTTP;
    let config = get_config().await;

    let mut merged = Vec::new();

    if is_special_site_enabled(&config, "nyaa") {
        let mirrors = special_mirrors(&config, "nyaa");
        merged.extend(sites::nyaa::search_english(client, &mirrors, query).await);
    }

    let td_results = config::search_site(client, &config, "torrentdownload", query, None).await;
    merged.extend(td_results.into_iter().filter(|r| r.title.to_lowercase().contains("anime")));

    if is_special_site_enabled(&config, "tokyotosho") {
        let mirrors = special_mirrors(&config, "tokyotosho");
        let tokyo_results = sites::tokyotosho::search(client, &mirrors, query).await;
        merged.extend(tokyo_results.into_iter().filter(|r| {
            !r.title.to_lowercase().contains("raw]")
        }));
    }

    let merged = with_1337x_fallback(client, &config, query, merged).await;
    let merged: Vec<TorrentResult> = merged.into_iter().filter(|r| {
        let t = r.title.to_lowercase();
        r.source != "1337x"
            || t.contains("anime")
            || r.audio_tags.iter().any(|tag| tag == "English Dub" || tag == "English Sub")
    }).collect();

    dedupe_and_sort(merged)
}

pub async fn search_anime_other_dub(query: &str) -> Vec<TorrentResult> {
    let client = &*HTTP;
    let config = get_config().await;
    if !is_special_site_enabled(&config, "nyaa") {
        return vec![];
    }
    let mirrors = special_mirrors(&config, "nyaa");
    let results = sites::nyaa::search_other_dub(client, &mirrors, query).await;
    dedupe_and_sort(results)
}

pub async fn search_anime_all(query: &str) -> Vec<TorrentResult> {
    let client = &*HTTP;
    let config = get_config().await;
    if !is_special_site_enabled(&config, "nyaa") {
        return vec![];
    }
    let mirrors = special_mirrors(&config, "nyaa");
    let results = sites::nyaa::search(client, &mirrors, query).await;
    dedupe_and_sort(results)
}

pub async fn search_anime_english_json(query: &str) -> String {
    let results = search_anime_english(query).await;
    serde_json::to_string(&results).unwrap_or_else(|_| "[]".to_string())
}

pub async fn search_anime_other_dub_json(query: &str) -> String {
    let results = search_anime_other_dub(query).await;
    serde_json::to_string(&results).unwrap_or_else(|_| "[]".to_string())
}
