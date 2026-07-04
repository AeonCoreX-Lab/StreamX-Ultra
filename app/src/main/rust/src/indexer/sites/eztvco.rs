// src/indexer/sites/eztvco.rs
//
// eztvtorrent.co indexer — NOT the official EZTV (eztv.re/eztvx.to). This
// is an independent clone/aggregator site with its own frontend
// (ezcdn.online CDN) that re-packages torrents largely sourced from YTS
// (see tracker/download-URL pattern in fetch_magnets_from_detail below).
// Not in Jackett's definition set — added directly from manual site
// analysis since it has a genuinely large (~50k movie, ~10k series)
// multi-region catalog including Hindi, Tamil, Chinese, Turkish, and
// Korean-drama-adjacent titles, with clean, cf_clearance-free magnet
// listings.
//
// SITE STRUCTURE (verified by direct fetch, no yml to port from):
//   Base URL     : https://eztvtorrent.co
//   Movie detail : /movie/{slug}          e.g. /movie/daadi-ki-shaadi-2026
//   Series detail: /series/{slug}         e.g. /series/emily-in-paris
//   Magnet       : directly in the detail-page download table —
//                  a[href^="magnet:?xt="], no detail-page-of-a-detail-page
//                  hop needed once we're on the movie/series page itself.
//
// NO SEARCH ENDPOINT: unlike every other site in this indexer, this site
// exposes no /search?q= or similar query endpoint in its HTML — only
// browse-by-category listing pages (/movies, /tv-series, /top-imdb,
// paginated). We therefore search it by CONSTRUCTING the likely detail
// URL directly from the query title (slugify + optional year suffix)
// rather than querying and parsing a results list. This trades recall
// (a wrong guess simply 404s, silently, and we fall back to other
// sites) for zero-crawl-cost direct access to a title we already know
// the name of, which is exactly our situation — every call site in
// engine.rs already has a specific movie/show title in hand.
//
// The slug guess is inherently probabilistic (we don't control the
// site's exact normalization rules), so this is intentionally a
// best-effort supplementary source, not a primary one — treated the
// same way as the 1337x broad-fallback tier rather than a dedicated tier
// on its own.

use anyhow::Result;
use scraper::{Html, Selector};
use crate::indexer::types::TorrentResult;

const MIRRORS: &[&str] = &[
    "https://eztvtorrent.co",
];

/// Try to find a movie by title (+ optional year) via direct slug guess.
/// Returns whatever magnets are on that detail page if the guess hits;
/// empty Vec (not an error) if it 404s or the slug doesn't exist —
/// callers should treat this exactly like "no results from this site".
pub async fn search_movie(client: &reqwest::Client, title: &str, year: Option<u32>) -> Vec<TorrentResult> {
    let slug = slugify(title);
    let mut candidates = Vec::new();
    if let Some(y) = year {
        candidates.push(format!("{slug}-{y}"));
    }
    candidates.push(slug.clone());
    // Try a small window around the given year too — release-year
    // metadata between our caller and this site can be off by one
    // (e.g. festival-year vs wide-release-year mismatches).
    if let Some(y) = year {
        candidates.push(format!("{slug}-{}", y.saturating_sub(1)));
        candidates.push(format!("{slug}-{}", y + 1));
    }

    for candidate_slug in candidates {
        let path = format!("/movie/{candidate_slug}");
        if let Ok(results) = fetch_detail(client, &path).await {
            if !results.is_empty() {
                return results;
            }
        }
    }
    vec![]
}

/// Same idea for TV series — series slugs on this site have no year
/// suffix (e.g. /series/emily-in-paris), so there's only one candidate.
pub async fn search_series(client: &reqwest::Client, title: &str) -> Vec<TorrentResult> {
    let slug = slugify(title);
    let path = format!("/series/{slug}");
    fetch_detail(client, &path).await.unwrap_or_default()
}

// ── Internal ─────────────────────────────────────────────────────────────────

async fn fetch_detail(client: &reqwest::Client, path: &str) -> Result<Vec<TorrentResult>> {
    let html = match get_html(client, path).await {
        Ok(h) => h,
        Err(_) => return Ok(vec![]), // 404 / not found — not a real error, just a miss
    };
    let doc = Html::parse_document(&html);

    // Each row of the download table: size | quality | torrent-link | magnet-link.
    // We only need the magnet cell and its adjacent size/quality text.
    let row_sel    = Selector::parse("table tr:has(a[href^=\"magnet:?xt=\"])").unwrap();
    let magnet_sel = Selector::parse(r#"a[href^="magnet:?xt="]"#).unwrap();
    let cell_sel   = Selector::parse("td").unwrap();

    // Title comes from the page's own <h1>-equivalent; fall back to the
    // og:title meta value pattern seen in the fetched pages if missing.
    let title_sel = Selector::parse("h1").unwrap();
    let page_title = doc.select(&title_sel).next()
        .map(|e| e.text().collect::<String>().trim().to_string())
        .unwrap_or_default();
    if page_title.is_empty() {
        // No <h1> found at all strongly suggests this was a 404/redirect
        // page rather than a real detail page — bail out rather than
        // risk emitting a garbage result with an empty title.
        return Ok(vec![]);
    }

    let mut results = Vec::new();
    for row in doc.select(&row_sel) {
        let magnet = match row.select(&magnet_sel).next().and_then(|e| e.value().attr("href")) {
            Some(m) => m.to_string(),
            None => continue,
        };

        let cells: Vec<String> = row.select(&cell_sel)
            .map(|c| c.text().collect::<String>().trim().to_string())
            .collect();
        // Observed column order: [Size, Quality, Torrent-link-text, Magnet-link-text]
        let size    = cells.first().cloned().unwrap_or_default();
        let quality_hint = cells.get(1).cloned().unwrap_or_default();

        let mut r = TorrentResult {
            title: format!("{page_title} {quality_hint}").trim().to_string(),
            magnet,
            size,
            seeds: 0, // this site's listing doesn't expose seed counts at all
            peers: 0,
            source: "eztvtorrent.co".to_string(),
            ..Default::default()
        };
        r.parse_tags();
        // No real seed data — mark honestly rather than defaulting to 0
        // looking like "confirmed zero seeds" (which would make every
        // result from this site get filtered out by seed-based sorting
        // elsewhere). Reuse the same "unknown health" convention as the
        // Korean drama sites.
        r.seeds = 1;
        results.push(r);
    }
    Ok(results)
}

/// Converts a display title into this site's URL-slug convention:
/// lowercase, non-alphanumeric runs collapsed to a single hyphen, no
/// leading/trailing hyphens. Matches the pattern observed across every
/// sampled URL (daadi-ki-shaadi-2026, emily-in-paris, x-1996).
fn slugify(title: &str) -> String {
    let mut slug = String::with_capacity(title.len());
    let mut last_was_hyphen = false;
    for c in title.to_lowercase().chars() {
        if c.is_ascii_alphanumeric() {
            slug.push(c);
            last_was_hyphen = false;
        } else if !last_was_hyphen && !slug.is_empty() {
            slug.push('-');
            last_was_hyphen = true;
        }
    }
    if slug.ends_with('-') {
        slug.pop();
    }
    slug
}

// ── HTTP helper ───────────────────────────────────────────────────────────────

async fn get_html(client: &reqwest::Client, path: &str) -> Result<String> {
    for mirror in MIRRORS {
        let url = format!("{mirror}{path}");
        let resp = client.get(&url)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "en-US,en;q=0.9")
            .send()
            .await?;
        if resp.status().is_success() {
            return Ok(resp.text().await?);
        }
        // 404 is the expected "wrong slug guess" outcome, not worth
        // warning about — only log genuinely unexpected failures.
        if resp.status().as_u16() != 404 {
            log::warn!("[eztvtorrent.co] unexpected HTTP {} for {url}", resp.status());
        }
    }
    anyhow::bail!("no mirror returned this path")
}
