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

use once_cell::sync::Lazy;
use std::time::Duration;

use super::sites::{self, x1337x, tgx, kat, torrentdownload, extratorrent, therarbg};
use super::types::TorrentResult;

// Shared reqwest client — reused across all site calls (connection pooling)
static HTTP: Lazy<reqwest::Client> = Lazy::new(|| {
    reqwest::Client::builder()
        .timeout(Duration::from_secs(15))
        .user_agent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
        .build()
        .expect("failed to build indexer HTTP client")
});

// ── Universal fallback (1337x) ────────────────────────────────────────────────
//
// MovieBox's own indexer treats 1337x as the catch-all: whatever the
// category — movie, series, anime, any dub language — if the dedicated
// sources for that category come up short, 1337x gets queried broadly
// (no category restriction) as a last resort, since it's simply the
// largest general-purpose library of the sites we cover.
//
// We replicate that as an explicit fallback step in every search_*()
// function below rather than treating 1337x as "just another parallel
// source": dedicated sources run first, and 1337x's broad/unfiltered
// search only fires if they didn't return enough. This avoids wasting
// 1337x's heavier cost (2 pages + a detail-page fetch per result) on
// searches that already have plenty of good hits, while still
// guaranteeing every query gets a shot at 1337x's much larger catalog.
const FALLBACK_MIN_RESULTS: usize = 3;

/// Query 1337x broadly (plain keyword, no dub/category filter) and merge
/// it in only if `existing` doesn't already have enough results. Always
/// re-runs parse_tags() (already done by x1337x::search) so callers can
/// still filter by audio_tags/category afterward — this function just
/// decides whether 1337x's catalog gets consulted at all.
async fn with_1337x_fallback(
    client:   &reqwest::Client,
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
    merged.extend(x1337x::search(client, query).await);
    merged
}

/// Search all sites for dubbed/dual-audio results matching `query`.
/// If `imdb_id` is Some, TorrentGalaxy and TheRARBG are queried by exact
/// IMDB match first (most reliable for a specific movie), other sites use
/// the title string.
///
/// Runs all 6 site searches concurrently via tokio::join!, then merges,
/// dedupes, and sorts by seeds descending. TheRARBG (pure JSON API) is
/// the most reliable source and is tried whether or not an IMDB ID is
/// available.
pub async fn search_dubbed(query: &str, imdb_id: Option<&str>) -> Vec<TorrentResult> {
    let client = &*HTTP;

    // TGx and TheRARBG both support exact IMDB-ID search — prefer it
    // when we have it, since it avoids title-matching ambiguity entirely.
    let tgx_future = async {
        if let Some(id) = imdb_id {
            tgx::search_by_imdb(client, id).await
        } else {
            tgx::search(client, query).await
        }
    };
    let rarbg_future = async {
        if let Some(id) = imdb_id {
            therarbg::search_by_imdb(client, id).await
        } else {
            therarbg::search_dubbed(client, query).await
        }
    };
    let tpb_future = async {
        if let Some(id) = imdb_id {
            sites::tpb::search_by_imdb(client, query, id).await
        } else {
            sites::tpb::search(client, query).await
        }
    };

    // Dedicated sources run first, in parallel. 1337x is NOT in this
    // batch — see with_1337x_fallback() below.
    let (rtgx, rkat, rtd, rext, rrarbg, rtpb, rkatws) = tokio::join!(
        tgx_future,
        kat::search(client, query),
        torrentdownload::search(client, query),
        extratorrent::search_dubbed(client, query),
        rarbg_future,
        tpb_future,
        sites::kat_ws::search(client, query),
    );

    let mut merged = Vec::new();
    merged.extend(rtgx);
    merged.extend(rkat);
    merged.extend(rtd);
    merged.extend(rext);
    merged.extend(rrarbg);
    merged.extend(rtpb);
    merged.extend(rkatws);

    // Keep only results that actually carry a dubbed/dual-audio tag —
    // not every site pre-filters (KAT, TorrentDownload, and an IMDB-based
    // TheRARBG/TGx hit may return the original-language cut too), so we
    // enforce it here as the final gate.
    merged.retain(|r| r.is_dubbed());

    // 1337x fallback — broad query, then re-filter to dubbed only, since
    // the fallback helper itself doesn't know we want dub-tagged results.
    let mut merged = with_1337x_fallback(client, query, merged).await;
    merged.retain(|r| r.is_dubbed());

    dedupe_and_sort(merged)
}

/// Plain keyword search across all sites, no dub filtering.
/// Useful as a fallback if search_dubbed() returns nothing (rare titles),
/// and as the primary path for the English/original-language flow.
pub async fn search_all(query: &str) -> Vec<TorrentResult> {
    let client = &*HTTP;

    let (rtgx, rkat, rtd, rext, rrarbg, rtpb, rkatws) = tokio::join!(
        tgx::search(client, query),
        kat::search(client, query),
        torrentdownload::search(client, query),
        extratorrent::search(client, query),
        therarbg::search(client, query),
        sites::tpb::search(client, query),
        sites::kat_ws::search(client, query),
    );

    let mut merged = Vec::new();
    merged.extend(rtgx);
    merged.extend(rkat);
    merged.extend(rtd);
    merged.extend(rext);
    merged.extend(rrarbg);
    merged.extend(rtpb);
    merged.extend(rkatws);

    let merged = with_1337x_fallback(client, query, merged).await;

    dedupe_and_sort(merged)
}

// ── Merge helpers ──────────────────────────────────────────────────────────────

/// Remove near-duplicate releases (same title+size from different sites often
/// point at the identical torrent) and sort by seeds descending so the most
/// healthy swarm shows first — this is what TorrentSession cares about most
/// for fast buffering.
fn dedupe_and_sort(mut results: Vec<TorrentResult>) -> Vec<TorrentResult> {
    use std::collections::HashSet;

    let mut seen_magnets: HashSet<String> = HashSet::new();
    let mut seen_signature: HashSet<(String, String)> = HashSet::new();

    results.retain(|r| {
        // Extract btih hash from magnet for true dedup (different sites may
        // link the exact same torrent with different tracker lists appended)
        let hash = extract_btih(&r.magnet).unwrap_or_else(|| r.magnet.clone());
        if !seen_magnets.insert(hash) {
            return false;
        }
        // Secondary guard: identical title+size from different trackers
        let sig = (r.title.to_lowercase(), r.size.clone());
        seen_signature.insert(sig)
    });

    results.sort_by(|a, b| b.seeds.cmp(&a.seeds));
    results
}

/// Pull the BTIH (BitTorrent Info Hash) out of a magnet URI for dedup purposes.
/// magnet:?xt=urn:btih:XXXXXXXX&dn=...
fn extract_btih(magnet: &str) -> Option<String> {
    let marker = "btih:";
    let start = magnet.find(marker)? + marker.len();
    let rest = &magnet[start..];
    let end = rest.find('&').unwrap_or(rest.len());
    Some(rest[..end].to_lowercase())
}

/// Convenience wrapper used by lib.rs — serializes results to a JSON string
/// so the JNI boundary only ever deals with `jstring`, matching the existing
/// nativeAddonFetchStreams() / nativeExecuteJsStream() convention in lib.rs.
pub async fn search_dubbed_json(query: &str, imdb_id: Option<&str>) -> String {
    let results = search_dubbed(query, imdb_id).await;
    serde_json::to_string(&results).unwrap_or_else(|_| "[]".to_string())
}

/// Same convenience wrapper for the plain (non-dub-filtered) search path.
pub async fn search_all_json(query: &str) -> String {
    let results = search_all(query).await;
    serde_json::to_string(&results).unwrap_or_else(|_| "[]".to_string())
}

// ── Drama (K-drama / C-drama / Turkish drama) ────────────────────────────────
//
// These don't fit the "dubbed movie" model cleanly — most releases are
// either original-language-with-subs or specifically English-dubbed, and
// the best sources differ from the movie indexer set:
//   • TorrentQQ / Torrentsome  — dedicated Korean trackers (no seed data,
//     see kdrama.rs doc comment)
//   • General sites (1337x/TGx/KAT/TorrentDownload/ExtraTorrent/TheRARBG)
//     — often carry Chinese/Turkish drama under generic TV categories,
//     found via title tag filtering (parse_tags() now covers these terms)

/// Search for a drama title, returning both original-voice (with subs)
/// and English/other-dub releases together — the caller can filter by
/// `audio_tags` client-side (e.g. show "Korean" vs "English Dub" chips).
pub async fn search_drama(query: &str) -> Vec<TorrentResult> {
    let client = &*HTTP;

    let (rqq, rsome, rtgx, rkat, rtd, rext, rrarbg, rtpb, rkatws) = tokio::join!(
        sites::kdrama::search_torrentqq(client, query),
        sites::kdrama::search_torrentsome(client, query),
        tgx::search(client, query),
        kat::search(client, query),
        torrentdownload::search(client, query),
        extratorrent::search(client, query),
        therarbg::search(client, query),
        sites::tpb::search(client, query),
        sites::kat_ws::search(client, query),
    );

    let mut merged = Vec::new();
    merged.extend(rqq);
    merged.extend(rsome);
    merged.extend(rtgx);
    merged.extend(rkat);
    merged.extend(rtd);
    merged.extend(rext);
    merged.extend(rrarbg);
    merged.extend(rtpb);
    merged.extend(rkatws);

    let merged = with_1337x_fallback(client, query, merged).await;

    dedupe_and_sort(merged)
}

/// Drama search filtered to only English-dubbed or English-subbed releases
/// (i.e. anything an English-only viewer could actually watch).
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
//
// Nyaa.si is the primary source — its own category split (English-
// translated / Non-English-translated / Raw) is a much stronger signal
// than title parsing, so we query it directly by category rather than
// relying purely on parse_tags(). General sites are included as a
// secondary source since some anime movies/OVAs list there too.

/// Anime search returning English-translated (dub or sub) releases from
/// Nyaa, plus general-site and Tokyo Toshokan results for broader
/// movie/OVA/older-batch coverage.
pub async fn search_anime_english(query: &str) -> Vec<TorrentResult> {
    let client = &*HTTP;

    let (rnyaa, rtd, rtokyo) = tokio::join!(
        sites::nyaa::search_english(client, query),
        torrentdownload::search(client, query),
        sites::tokyotosho::search(client, query),
    );

    let mut merged = Vec::new();
    merged.extend(rnyaa);
    merged.extend(rtd.into_iter().filter(|r| r.title.to_lowercase().contains("anime")));
    // Tokyo Toshokan is anime/JP-media-only by nature — no filtering needed,
    // but it does carry raw/non-English releases too, so still gate on tags.
    merged.extend(rtokyo.into_iter().filter(|r| {
        let t = r.title.to_lowercase();
        !t.contains("raw]") // exclude untranslated raws from the English-only result set
    }));

    // 1337x fallback — Nyaa/TokyoToshokan cover anime far better than 1337x
    // normally would, so this rarely fires, but titles that are anime
    // *movies* (as opposed to TV series) sometimes only list on 1337x.
    let merged = with_1337x_fallback(client, query, merged).await;
    let merged: Vec<TorrentResult> = merged.into_iter().filter(|r| {
        let t = r.title.to_lowercase();
        // Only keep 1337x hits that look like anime/English-translated —
        // general sites aren't anime-specific, so gate on title/tags here
        // same as before, applied after the fallback merge.
        r.source != "1337x"
            || t.contains("anime")
            || r.audio_tags.iter().any(|tag| tag == "English Dub" || tag == "English Sub")
    }).collect();

    dedupe_and_sort(merged)
}

/// Anime search for non-English dubs/subs (Nyaa's "Non-English-translated"
/// category) — individual language (Spanish, German, etc.) is available
/// via each result's audio_tags after parse_tags() runs.
pub async fn search_anime_other_dub(query: &str) -> Vec<TorrentResult> {
    let client = &*HTTP;
    let results = sites::nyaa::search_other_dub(client, query).await;
    dedupe_and_sort(results)
}

/// Full anime search — English + other-language translations + raw,
/// all merged (Nyaa's unfiltered "All categories" query).
pub async fn search_anime_all(query: &str) -> Vec<TorrentResult> {
    let client = &*HTTP;
    let results = sites::nyaa::search(client, query).await;
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
