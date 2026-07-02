// src/indexer/sites/tpb.rs
//
// The Pirate Bay indexer — ported from Jackett's thepiratebay.yml
//
// KEY POINTS from Jackett YAML:
//   API URL    :  https://apibay.org/  (TPB's own official JSON backend —
//                 not a scrape target, this is the site's real API)
//   Search URL :  q.php?q={QUERY}&cat={categories}
//   Response   :  JSON array — [{id, name, info_hash, leechers, seeders,
//                  num_files, size, username, category, imdb, added}, ...]
//
// ADVANTAGE: pure JSON API, same class of reliability as TheRARBG — no
// CSS selectors, nothing to break on a redesign. TPB's own IMDB field
// (`imdb`) is present per-result (not a search parameter like TGx/RARBG's
// IMDB search, but still useful for client-side exact-match filtering).
//
// Jackett applies keyword pre-filters worth replicating:
//   • strips "it's" (apostrophe confuses TPB's search engine — Jackett
//     issue #8829)
//   • replaces CJK (Chinese/Japanese/Korean) characters with "." since
//     TPB's search engine chokes on them (Jackett issue #7291) — this
//     matters for our C-drama/K-drama/anime queries that may carry
//     native-script titles
//   • lowercases the query

use anyhow::Result;
use serde::Deserialize;
use crate::indexer::types::TorrentResult;

const API_MIRRORS: &[&str] = &[
    "https://apibay.org",
    "https://apibay.li", // documented fallback mirror for apibay
];

#[derive(Debug, Deserialize)]
struct TpbItem {
    #[serde(default)]
    name: String,
    #[serde(default)]
    info_hash: String,
    #[serde(default)]
    seeders: String,   // TPB returns these as strings, not numbers
    #[serde(default)]
    leechers: String,
    #[serde(default)]
    size: String,       // bytes, as a string
    #[serde(default)]
    imdb: String,
}

/// Search by keyword. Applies the same query pre-filters Jackett uses
/// (strip apostrophe-s, replace CJK chars, lowercase) since TPB's search
/// engine is picky about both.
pub async fn search(client: &reqwest::Client, query: &str) -> Vec<TorrentResult> {
    let cleaned = clean_query(query);
    let q = urlencoding::encode(&cleaned);
    // cat= left empty → all categories; TPB accepts a blank cat param.
    let path = format!("/q.php?q={q}&cat=");
    fetch_json(client, &path).await.unwrap_or_default()
}

/// Filter results to only those whose own `imdb` field matches — useful
/// as a client-side exact-match pass since TPB doesn't support IMDB as a
/// search parameter directly (unlike TGx/RARBG), so we still search by
/// title text but discard anything whose imdb field doesn't match.
pub async fn search_by_imdb(client: &reqwest::Client, query: &str, imdb_id: &str) -> Vec<TorrentResult> {
    let cleaned = clean_query(query);
    let q = urlencoding::encode(&cleaned);
    let path = format!("/q.php?q={q}&cat=");

    match fetch_json_imdb_filtered(client, &path, imdb_id).await {
        Ok(results) if !results.is_empty() => results,
        // Fall back to the plain title search if nothing matched the
        // exact IMDB id — some TPB uploads don't set the imdb field.
        _ => search(client, query).await,
    }
}

// Internal variant that filters by IMDB before discarding the raw `imdb`
// field (TorrentResult doesn't carry it, so filtering must happen here).
async fn fetch_json_imdb_filtered(
    client:   &reqwest::Client,
    path:     &str,
    imdb_id:  &str,
) -> Result<Vec<TorrentResult>> {
    let normalized = imdb_id.trim_start_matches("tt");
    for mirror in API_MIRRORS {
        let url = format!("{mirror}{path}");
        match try_fetch_raw(client, &url).await {
            Ok(items) => {
                let filtered: Vec<TorrentResult> = items.into_iter()
                    .filter(|item| item.imdb.trim_start_matches("tt") == normalized)
                    .filter_map(item_to_result)
                    .collect();
                if !filtered.is_empty() {
                    return Ok(filtered);
                }
            }
            Err(e) => log::warn!("[TPB] mirror {mirror} failed: {e}"),
        }
    }
    Ok(vec![])
}

// ── Internal ─────────────────────────────────────────────────────────────────

async fn fetch_json(client: &reqwest::Client, path: &str) -> Result<Vec<TorrentResult>> {
    for mirror in API_MIRRORS {
        let url = format!("{mirror}{path}");
        match try_fetch_raw(client, &url).await {
            Ok(items) if !items.is_empty() => {
                return Ok(items.into_iter().filter_map(item_to_result).collect());
            }
            Ok(_) => continue,
            Err(e) => log::warn!("[TPB] mirror {mirror} failed: {e}"),
        }
    }
    Ok(vec![])
}

async fn try_fetch_raw(client: &reqwest::Client, url: &str) -> Result<Vec<TpbItem>> {
    let resp = client.get(url)
        .header("Accept", "application/json")
        .send()
        .await?;

    if !resp.status().is_success() {
        anyhow::bail!("HTTP {} for {url}", resp.status());
    }

    let items: Vec<TpbItem> = resp.json().await?;

    // TPB returns a single placeholder item {"id":"0", "name":"No results
    // returned", ...} when nothing matches — filter that out explicitly.
    Ok(items.into_iter().filter(|i| i.name != "No results returned").collect())
}

fn item_to_result(item: TpbItem) -> Option<TorrentResult> {
    if item.info_hash.is_empty() { return None; }
    let seeds = item.seeders.parse::<u32>().unwrap_or(0);
    if seeds == 0 { return None; }
    let peers = item.leechers.parse::<u32>().unwrap_or(0);
    let size  = format_size(item.size.parse::<u64>().unwrap_or(0));

    let magnet = build_magnet(&item.info_hash, &item.name);

    let mut r = TorrentResult {
        title:  item.name,
        magnet,
        size,
        seeds,
        peers,
        source: "ThePirateBay".to_string(),
        ..Default::default()
    };
    r.parse_tags();
    Some(r)
}

/// Strip apostrophe-s and replace CJK characters, same as Jackett's
/// keywordsfilters for this indexer (issues #8829 and #7291).
fn clean_query(query: &str) -> String {
    let re_its = regex::Regex::new(r"(?i)\bit's\b").unwrap();
    let without_its = re_its.replace_all(query, "");

    let re_cjk = regex::Regex::new(
        r"[\u{4E00}-\u{9FFF}\u{3040}-\u{30FF}\u{AC00}-\u{D7AF}]+"
    ).unwrap();
    let without_cjk = re_cjk.replace_all(&without_its, ".");

    without_cjk.to_lowercase().trim().to_string()
}

fn build_magnet(infohash: &str, title: &str) -> String {
    let dn = urlencoding::encode(title);
    format!(
        "magnet:?xt=urn:btih:{infohash}&dn={dn}\
         &tr=udp://tracker.opentrackr.org:1337/announce\
         &tr=udp://open.demonii.com:1337/announce\
         &tr=udp://tracker.openbittorrent.com:80\
         &tr=udp://exodus.desync.com:6969/announce\
         &tr=udp://tracker.torrent.eu.org:451/announce"
    )
}

fn format_size(bytes: u64) -> String {
    const UNITS: &[&str] = &["B", "KB", "MB", "GB", "TB"];
    let mut size = bytes as f64;
    let mut unit_idx = 0;
    while size >= 1024.0 && unit_idx < UNITS.len() - 1 {
        size /= 1024.0;
        unit_idx += 1;
    }
    format!("{:.2} {}", size, UNITS[unit_idx])
}
