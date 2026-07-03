// src/indexer/config/loader.rs
//
// Fetches indexer-config.json from GitHub (AeonCoreX-Lab/streamx-addons),
// caches it on disk with a TTL, and falls back to a bundled snapshot if
// the network is unavailable or the remote file is malformed.
//
// This is what makes site fixes (dead mirror, changed selector) not
// require an APK release: edit the JSON in the streamx-addons repo,
// push, and every app picks it up within CACHE_TTL of its next search.

use anyhow::{Context, Result};
use once_cell::sync::OnceCell;
use parking_lot::RwLock;
use std::path::PathBuf;
use std::time::{Duration, SystemTime};

use super::schema::IndexerConfig;

/// Raw GitHub URL — streamx-addons repo, main branch. Update this path if
/// the config file ever moves within the repo.
const REMOTE_CONFIG_URL: &str =
    "https://raw.githubusercontent.com/AeonCoreX-Lab/streamx-addons/main/indexer-config.json";

/// Re-fetch at most this often. A search that happens sooner than this
/// after the last successful fetch uses the cached copy — keeps us from
/// hitting GitHub on every single search call.
const CACHE_TTL: Duration = Duration::from_secs(6 * 60 * 60); // 6 hours

/// Bundled fallback — a build-time snapshot of a known-good config,
/// embedded directly in the binary. Used when there's no cache on disk
/// yet AND the network fetch fails (e.g. first launch, offline, or
/// GitHub temporarily unreachable). This guarantees the indexer never
/// goes to zero sites even in the worst case.
const BUNDLED_CONFIG: &str = include_str!("../../../assets/indexer-config.default.json");

static CACHE: OnceCell<RwLock<CachedConfig>> = OnceCell::new();

struct CachedConfig {
    config:       IndexerConfig,
    fetched_at:   SystemTime,
}

/// Returns the current config, fetching from GitHub if the cache is
/// stale or empty. Never fails — falls back to bundled config on any
/// error so callers never have to handle a "no config" case.
pub async fn get_config(cache_dir: &std::path::Path) -> IndexerConfig {
    // Fast path: fresh in-memory cache, no I/O at all.
    if let Some(cached) = CACHE.get() {
        let guard = cached.read();
        if guard.fetched_at.elapsed().unwrap_or(Duration::MAX) < CACHE_TTL {
            return guard.config.clone();
        }
    }

    // Cache is stale or doesn't exist yet — try to refresh.
    match refresh(cache_dir).await {
        Ok(config) => {
            store_in_memory(config.clone());
            config
        }
        Err(e) => {
            log::warn!("[indexer-config] refresh failed: {e}");
            // Fall back to whatever we have: disk cache → memory cache
            // (even if stale) → bundled default, in that order.
            if let Some(disk) = load_from_disk(cache_dir) {
                store_in_memory(disk.clone());
                return disk;
            }
            if let Some(cached) = CACHE.get() {
                return cached.read().config.clone();
            }
            parse_bundled()
        }
    }
}

fn store_in_memory(config: IndexerConfig) {
    let entry = CachedConfig { config, fetched_at: SystemTime::now() };
    match CACHE.get() {
        Some(lock) => { *lock.write() = entry; }
        None => { let _ = CACHE.set(RwLock::new(entry)); }
    }
}

async fn refresh(cache_dir: &std::path::Path) -> Result<IndexerConfig> {
    let client = reqwest::Client::builder()
        .timeout(Duration::from_secs(8))
        .build()?;

    let resp = client.get(REMOTE_CONFIG_URL)
        .header("Accept", "application/json")
        // GitHub raw content is aggressively cached by their CDN; this
        // header just avoids us adding unnecessary request overhead —
        // actual freshness is governed by our own CACHE_TTL, not this.
        .send()
        .await
        .context("GitHub fetch failed")?;

    if !resp.status().is_success() {
        anyhow::bail!("GitHub returned HTTP {}", resp.status());
    }

    let text = resp.text().await.context("reading response body")?;
    let config: IndexerConfig = serde_json::from_str(&text)
        .context("parsing indexer-config.json")?;

    if config.sites.is_empty() {
        anyhow::bail!("remote config has zero sites — refusing to use it");
    }

    save_to_disk(cache_dir, &text);
    log::info!(
        "[indexer-config] refreshed from GitHub — {} sites, updated={}",
        config.sites.len(), config.updated
    );
    Ok(config)
}

fn cache_file_path(cache_dir: &std::path::Path) -> PathBuf {
    cache_dir.join("indexer-config.cache.json")
}

fn save_to_disk(cache_dir: &std::path::Path, text: &str) {
    let path = cache_file_path(cache_dir);
    if let Err(e) = std::fs::write(&path, text) {
        log::warn!("[indexer-config] failed to write disk cache: {e}");
    }
}

fn load_from_disk(cache_dir: &std::path::Path) -> Option<IndexerConfig> {
    let path = cache_file_path(cache_dir);
    let text = std::fs::read_to_string(&path).ok()?;
    match serde_json::from_str(&text) {
        Ok(config) => {
            log::info!("[indexer-config] loaded from disk cache: {}", path.display());
            Some(config)
        }
        Err(e) => {
            log::warn!("[indexer-config] disk cache corrupt, ignoring: {e}");
            None
        }
    }
}

fn parse_bundled() -> IndexerConfig {
    log::warn!("[indexer-config] using bundled fallback config (network + disk cache both unavailable)");
    serde_json::from_str(BUNDLED_CONFIG)
        .expect("bundled indexer-config.default.json must always parse — this is a build-time asset")
}

/// Force an immediate refresh regardless of TTL — exposed for a manual
/// "refresh sources" button in app settings, if one is added later.
#[allow(dead_code)]
pub async fn force_refresh(cache_dir: &std::path::Path) -> Result<IndexerConfig> {
    let config = refresh(cache_dir).await?;
    store_in_memory(config.clone());
    Ok(config)
}

/// Timestamp helper for surfacing "sources last updated X ago" in the UI
/// if desired later.
#[allow(dead_code)]
pub fn cache_age_secs() -> Option<u64> {
    let cached = CACHE.get()?;
    let guard = cached.read();
    guard.fetched_at.elapsed().ok().map(|d| d.as_secs())
}
