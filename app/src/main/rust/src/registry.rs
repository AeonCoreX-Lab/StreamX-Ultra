// app/src/main/rust/src/registry.rs
//
// StreamX Ultra's own copy of the "hosted fetch with fallback" pattern
// documented in streamx-torrent-indexer's docs/CONSUMING.md. This is
// the direct successor to the old in-app indexer/config/loader.rs +
// indexer/engine.rs CACHE_DIR pair — same three-tier fallback
// (hosted → last-known-good disk cache → embedded), just pointed at
// the streamx-torrent-indexer repo's GitHub Releases instead of
// hand-maintaining indexer-config.json inside this repo.
//
// WHY THIS LIVES HERE AND NOT IN THE CRATE: streamx-indexer (the
// crate) deliberately doesn't know it's running inside an Android app
// — it has no concept of Context.cacheDir, no opinion on WHERE a
// hosted registry.json comes from, and no network-retry/TTL policy of
// its own. This module is exactly the platform-specific glue the
// crate's docs say the embedding app is responsible for providing.

use once_cell::sync::OnceCell;
use parking_lot::RwLock;
use std::path::{Path, PathBuf};
use std::time::{Duration, SystemTime};
use streamx_indexer::registry::IndexerRegistry;

/// GitHub's "latest release" alias — always redirects to whichever
/// release streamx-torrent-indexer's daily release-registry.yml cut
/// most recently. No version string to track on this side; see that
/// repo's docs/CONSUMING.md.
const REMOTE_REGISTRY_URL: &str =
    "https://github.com/AeonCoreX-Lab/streamx-torrent-indexer/releases/latest/download/registry.json";

/// Re-fetch at most this often. A search sooner than this after the
/// last successful fetch reuses the in-memory copy — keeps us from
/// hitting GitHub on every single search call. Matches the old
/// indexer-config.json loader's TTL.
const CACHE_TTL: Duration = Duration::from_secs(6 * 60 * 60); // 6 hours

static CACHE_DIR: OnceCell<RwLock<Option<PathBuf>>> = OnceCell::new();
static CACHE: OnceCell<RwLock<CachedRegistry>> = OnceCell::new();

struct CachedRegistry {
    registry:   IndexerRegistry,
    fetched_at: SystemTime,
}

/// Called once from lib.rs's nativeSetCacheDir at native init time —
/// same call site the old indexer::engine::init_cache_dir() used, just
/// renamed. Gives the disk-cache layer somewhere durable to write.
/// Safe to skip: falls back to the system temp dir, which still works
/// but won't survive an app restart as reliably as the real cache dir.
pub fn init_cache_dir(dir: PathBuf) {
    match CACHE_DIR.get() {
        Some(lock) => *lock.write() = Some(dir),
        None => { let _ = CACHE_DIR.set(RwLock::new(Some(dir))); }
    }
}

fn cache_dir() -> PathBuf {
    CACHE_DIR
        .get()
        .and_then(|lock| lock.read().clone())
        .unwrap_or_else(std::env::temp_dir)
}

/// Returns the current registry, fetching from GitHub if the in-memory
/// cache is stale or empty. Never fails — falls back all the way to
/// the crate's compiled-in embedded registry on any error, so callers
/// never have to handle a "no registry" case.
pub async fn get_registry() -> IndexerRegistry {
    if let Some(cached) = CACHE.get() {
        let guard = cached.read();
        if guard.fetched_at.elapsed().unwrap_or(Duration::MAX) < CACHE_TTL {
            return guard.registry.clone();
        }
    }

    match refresh().await {
        Ok(registry) => {
            store_in_memory(registry.clone());
            registry
        }
        Err(e) => {
            log::warn!("[registry] refresh failed: {e}");
            // Fall back to whatever we have: disk cache → memory cache
            // (even if stale) → embedded snapshot, in that order.
            if let Some(disk) = load_from_disk(&cache_dir()) {
                store_in_memory(disk.clone());
                return disk;
            }
            if let Some(cached) = CACHE.get() {
                return cached.read().registry.clone();
            }
            log::warn!("[registry] using embedded fallback (network + disk cache both unavailable)");
            streamx_indexer::registry::load_embedded()
        }
    }
}

fn store_in_memory(registry: IndexerRegistry) {
    let entry = CachedRegistry { registry, fetched_at: SystemTime::now() };
    match CACHE.get() {
        Some(lock) => { *lock.write() = entry; }
        None => { let _ = CACHE.set(RwLock::new(entry)); }
    }
}

async fn refresh() -> anyhow::Result<IndexerRegistry> {
    let client = reqwest::Client::builder()
        .timeout(Duration::from_secs(8))
        .build()?;

    let resp = client
        .get(REMOTE_REGISTRY_URL)
        .header("Accept", "application/json")
        .send()
        .await
        .map_err(|e| anyhow::anyhow!("GitHub Releases fetch failed: {e}"))?;

    if !resp.status().is_success() {
        anyhow::bail!("GitHub Releases returned HTTP {}", resp.status());
    }

    let text = resp.text().await.map_err(|e| anyhow::anyhow!("reading response body: {e}"))?;
    let registry = streamx_indexer::registry::load_from_json(&text)
        .map_err(|e| anyhow::anyhow!("parsing registry.json: {e}"))?;

    if registry.sites.is_empty() {
        anyhow::bail!("remote registry has zero sites — refusing to use it");
    }

    save_to_disk(&cache_dir(), &text);
    log::info!(
        "[registry] refreshed from GitHub Releases — {} site(s), version={}",
        registry.sites.len(),
        registry.updated
    );
    Ok(registry)
}

fn cache_file_path(cache_dir: &Path) -> PathBuf {
    cache_dir.join("streamx-indexer-registry.cache.json")
}

fn save_to_disk(cache_dir: &Path, text: &str) {
    let path = cache_file_path(cache_dir);
    if let Err(e) = std::fs::write(&path, text) {
        log::warn!("[registry] failed to write disk cache: {e}");
    }
}

fn load_from_disk(cache_dir: &Path) -> Option<IndexerRegistry> {
    let path = cache_file_path(cache_dir);
    let text = std::fs::read_to_string(&path).ok()?;
    match streamx_indexer::registry::load_from_json(&text) {
        Ok(registry) => {
            log::info!("[registry] loaded from disk cache: {}", path.display());
            Some(registry)
        }
        Err(e) => {
            log::warn!("[registry] disk cache corrupt, ignoring: {e}");
            None
        }
    }
}

/// Force an immediate refresh regardless of TTL — exposed for a manual
/// "refresh sources" button in app settings, if one is added later.
/// Mirrors the old loader's force_refresh().
#[allow(dead_code)]
pub async fn force_refresh() -> anyhow::Result<IndexerRegistry> {
    let registry = refresh().await?;
    store_in_memory(registry.clone());
    Ok(registry)
}

/// Timestamp helper for surfacing "sources last updated X ago" in the
/// UI if desired later. Mirrors the old loader's cache_age_secs().
#[allow(dead_code)]
pub fn cache_age_secs() -> Option<u64> {
    let cached = CACHE.get()?;
    let guard = cached.read();
    guard.fetched_at.elapsed().ok().map(|d| d.as_secs())
}
