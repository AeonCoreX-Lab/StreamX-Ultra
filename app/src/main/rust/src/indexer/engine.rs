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
///
/// IMPORTANT — imdb_id is deliberately NOT passed to the dedicated-site
/// search below (see the `None` in the search_sites call). Root cause of
/// a real bug: when imdb_id WAS passed through, sites with an
/// `imdb_path` (TGx, TheRARBG) silently ignored the caller's query
/// string entirely and searched by IMDB ID alone — which returns
/// whatever cut of the movie that site has, with NO language filtering,
/// since IMDB search has no way to express "the Hindi dub of this movie"
/// on any of these sites. The caller's query already has the dub
/// language baked in (e.g. "Predator Badlands Hindi Dubbed 1080p" — see
/// DubQueryBuilder.kt), so searching by title text is what actually
/// targets the dub. This was the direct cause of "0 Hindi results" while
/// the same title had 80+ English results: every dedicated site was
/// searching by IMDB ID and getting back English-only listings, then the
/// is_dubbed() filter correctly discarded all of them since none carried
/// a Hindi tag.
pub async fn search_dubbed(query: &str, _imdb_id: Option<&str>) -> Vec<TorrentResult> {
    let client = &*HTTP;
    let config = get_config().await;

    // Dedicated sources run first, in parallel, via the config-driven
    // dispatcher. "x1337x" is deliberately excluded — see fallback below.
    // imdb_id is intentionally NOT forwarded here — see doc comment above.
    let dedicated_ids = ["tgx", "kat", "torrentdownload", "extratorrent", "therarbg", "tpb", "kat_ws"];
    let raw = config::search_sites(client, &config, &dedicated_ids, query, None).await;

    // Keep only results that actually carry a dubbed/dual-audio tag —
    // not every site pre-filters, so we enforce it here as the primary
    // gate. This is a strict filter and can legitimately return zero
    // for a real title that simply has no dub release yet (very new
    // movies especially) — that's a correct "no dub available" outcome,
    // not a bug.
    let tagged: Vec<TorrentResult> = raw.iter().cloned().filter(|r| r.is_dubbed()).collect();

    // 1337x fallback — broad query, evaluated against the dub-tagged set.
    // NOTE: unlike the other with_1337x_fallback() call sites, we do NOT
    // re-apply `retain(|r| r.is_dubbed())` after this — the fallback
    // helper only adds 1337x results when `tagged` is already under
    // FALLBACK_MIN_RESULTS, and re-filtering here was a second bug: it
    // discarded the 1337x fallback additions unless THEY ALSO happened
    // to carry a dub tag, effectively making the 1337x fallback a no-op
    // for dubbed search the vast majority of the time. If tagged was
    // empty going in, it's still empty after the fallback returns
    // untagged 1337x hits — that's fine, we fall through to the untagged
    // path below either way.
    let after_1337x = with_1337x_fallback(client, &config, query, tagged).await;
    let tagged_final: Vec<TorrentResult> =
        after_1337x.iter().cloned().filter(|r| r.is_dubbed()).collect();

    if !tagged_final.is_empty() {
        return dedupe_and_sort(tagged_final);
    }

    // FIX: previously this returned an empty list here even when sites
    // DID return matching results for the query+IMDB-ID — they just
    // didn't carry a recognizable dub-language tag in the title (common
    // when a site's dub category is conveyed through its own taxonomy
    // rather than the release name, or when IMDB-ID search on TGx/RARBG
    // returns whatever cut that site has without a language marker).
    // Screenshots showed 80+ English results but zero Hindi for the same
    // title, which was misleadingly presented as "no Hindi torrents"
    // rather than "no dub-tagged Hindi torrents, showing best-guess
    // matches instead". We now fall back to the untagged, IMDB/title-
    // matched result set rather than an empty one — the UI should make
    // clear these are unconfirmed-language matches (see
    // IndexerResult / StreamLink source labeling on the Kotlin side).
    log::info!(
        "[search_dubbed] no dub-tagged results for \"{}\" — falling back to untagged matches",
        query
    );
    let mut untagged = with_1337x_fallback(client, &config, query, raw).await;
    for r in &mut untagged {
        r.is_confirmed_dub = false;
    }
    dedupe_and_sort(untagged)
}

/// Plain keyword search across all sites, no dub filtering.
///
/// NOTE: also does not use IMDB-path search (passes None below), for
/// consistency with search_dubbed()'s fix — see that function's doc
/// comment for the full root-cause explanation. For a plain title
/// search there's less harm in IMDB-path search since there's no
/// language filter being defeated, but keeping the same "always search
/// by query text" behavior here avoids a subtle inconsistency where
/// search_all() and search_dubbed() would silently behave differently
/// for the exact same title depending on whether an IMDB ID happened to
/// be available.
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
/// Universal exit gate for every search_*() function — dedupes,
/// removes adult/XXX content unconditionally, and sorts by seeds.
///
/// The adult-content filter lives HERE rather than per-site, because
/// XXX releases leak in through general-purpose sites (KAT, 1337x,
/// TorrentDownload) whenever a search term happens to also be a
/// performer name or overlaps a scene title — e.g. searching "Supergirl"
/// pulled in "KatiePink...Supergirl XXX iMAGESET" and
/// "ConorCoxxxClips...Supergirl.Conquered.Scene" results from KAT
/// alongside the legitimate TV show. Filtering once here, after every
/// site's results are already merged, guarantees no site (existing or
/// future) can bypass it — a per-site filter would need to be
/// remembered and re-applied every time a new site module is added.
fn dedupe_and_sort(mut results: Vec<TorrentResult>) -> Vec<TorrentResult> {
    use std::collections::HashSet;

    results.retain(|r| !r.is_adult_content());

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
