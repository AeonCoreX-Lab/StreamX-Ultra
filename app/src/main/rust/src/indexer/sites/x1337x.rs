// src/indexer/sites/x1337x.rs
//
// 1337x indexer — ported from Jackett's 1337x.yml
//
// KEY POINTS from Jackett YAML:
//   Search URL :  https://1337x.to/sort-search/{QUERY}/seeders/desc/1/
//   Category   :  cat/4  = Movies/Dubs/Dual Audio  (id:4)
//                 cat/73 = Movies/Bollywood         (id:73)
//   Row selector: tr:has(a[href^="/torrent/"])
//   Title      :  td[class^="coll-1"] a[href^="/torrent/"]
//   Seeds      :  td[class^="coll-2"]
//   Leechers   :  td[class^="coll-3"]
//   Size       :  td[class^="coll-4"]
//   Detail URL :  td[class^="coll-1"] a[href^="/torrent/"] @href
//
// DETAIL PAGE (Jackett download block):
//   magnet     :  ul li a[href^="magnet:"]   @href   (primary)
//   .torrent   :  ul li a[href^="http://itorrents.org/"] @href (fallback)
//
// Jackett uses requestDelay: 3 — we use 1-2s between pages to be safe.

use anyhow::Result;
use scraper::{Html, Selector};
use crate::indexer::types::TorrentResult;

const BASE: &str = "https://1337x.to";

// Fallback mirrors from Jackett legacylinks
const MIRRORS: &[&str] = &[
    "https://1337x.to",
    "https://1337x.st",
    "https://x1337x.ws",
];

/// Search by keyword (sort by seeders desc, up to 2 pages)
pub async fn search(client: &reqwest::Client, query: &str) -> Vec<TorrentResult> {
    let q = urlencoding::encode(query);
    // Jackett path template:
    // sort-search/{Keywords}/seeders/desc/1/
    let mut results = Vec::new();
    for page in 1..=2u32 {
        let url = format!("{BASE}/sort-search/{q}/seeders/desc/{page}/");
        match fetch_list(client, &url).await {
            Ok(mut r) => results.append(&mut r),
            Err(e) => { log::warn!("[1337x] search page {page} failed: {e}"); break; }
        }
        tokio::time::sleep(std::time::Duration::from_millis(1200)).await;
    }
    results
}

/// Browse dubbed/dual-audio category directly (cat id=4), sorted by seeders
pub async fn search_dubbed(client: &reqwest::Client, query: &str) -> Vec<TorrentResult> {
    // 1337x category 4 = "Movies/Dubs/Dual Audio" (from Jackett categorymappings id:4)
    // 1337x category 73 = "Movies/Bollywood"
    let q = urlencoding::encode(query);
    let mut results = Vec::new();

    // Jackett: if keywords + !disablesort → sort-search/{keywords}/{sort}/{type}/{page}/
    let urls = [
        format!("{BASE}/sort-search/{q}/seeders/desc/1/"),
        format!("{BASE}/sort-search/{q}/seeders/desc/2/"),
    ];

    for url in &urls {
        match fetch_list(client, url).await {
            Ok(mut r) => {
                // Filter to only dubbed results — Jackett doesn't filter here,
                // but since caller wants dubbed we apply audio_tag filter
                r.retain(|t| t.is_dubbed());
                results.append(&mut r);
            }
            Err(e) => { log::warn!("[1337x] dubbed search failed: {e}"); break; }
        }
        tokio::time::sleep(std::time::Duration::from_millis(1200)).await;
    }
    results
}

// ── Internal: fetch + parse a listing page ───────────────────────────────────

async fn fetch_list(client: &reqwest::Client, url: &str) -> Result<Vec<TorrentResult>> {
    let html = get_html(client, url).await?;
    let doc  = Html::parse_document(&html);

    // Jackett rows selector: tr:has(a[href^="/torrent/"])
    let row_sel    = Selector::parse(r#"tr:has(a[href^="/torrent/"])"#).unwrap();
    // Jackett title selector: td[class^="coll-1"] a[href^="/torrent/"]
    let title_sel  = Selector::parse(r#"td[class^="coll-1"] a[href^="/torrent/"]"#).unwrap();
    let seeds_sel  = Selector::parse(r#"td[class^="coll-2"]"#).unwrap();
    let leech_sel  = Selector::parse(r#"td[class^="coll-3"]"#).unwrap();
    let size_sel   = Selector::parse(r#"td[class^="coll-4"]"#).unwrap();

    let mut results = Vec::new();

    for row in doc.select(&row_sel) {
        let title_el = match row.select(&title_sel).next() { Some(e) => e, None => continue };

        let title  = title_el.text().collect::<String>().trim().to_string();
        let detail = match title_el.value().attr("href") { Some(h) => h, None => continue };
        let detail_url = format!("{BASE}{detail}");

        let seeds = row.select(&seeds_sel).next()
            .and_then(|e| e.text().collect::<String>().trim().parse::<u32>().ok())
            .unwrap_or(0);
        let peers = row.select(&leech_sel).next()
            .and_then(|e| e.text().collect::<String>().trim().parse::<u32>().ok())
            .unwrap_or(0);
        let size  = row.select(&size_sel).next()
            .map(|e| e.text().collect::<String>().trim().to_string())
            .unwrap_or_default();

        // Skip low-seed results — same practical threshold Jackett users set
        if seeds == 0 { continue; }

        let mut r = TorrentResult {
            title:   title.clone(),
            magnet:  detail_url.clone(), // placeholder; resolved below
            size,
            seeds,
            peers,
            source:  "1337x".to_string(),
            ..Default::default()
        };
        r.parse_tags();

        // Fetch magnet from detail page (Jackett "download" block with "before" step)
        // We resolve lazily — only fetch detail if seeds > 3
        if seeds >= 3 {
            match fetch_magnet(client, &detail_url).await {
                Ok(m) => r.magnet = m,
                Err(e) => {
                    log::warn!("[1337x] magnet fetch failed for {title}: {e}");
                    continue; // skip if we can't get a magnet
                }
            }
        }

        results.push(r);
    }
    Ok(results)
}

// ── Detail page: extract magnet URI ──────────────────────────────────────────
// Jackett download.selectors:
//   selector: ul li a[href^="magnet:"]        @href   (primary)
//   selector: ul li a[href^="http://itorrents"] @href  (fallback → skip, we want magnet)

async fn fetch_magnet(client: &reqwest::Client, detail_url: &str) -> Result<String> {
    let html = get_html(client, detail_url).await?;
    let doc  = Html::parse_document(&html);

    // Primary: Jackett selector ul li a[href^="magnet:?xt="]
    let magnet_sel = Selector::parse(r#"ul li a[href^="magnet:?xt="]"#).unwrap();
    if let Some(el) = doc.select(&magnet_sel).next() {
        if let Some(href) = el.value().attr("href") {
            return Ok(href.to_string());
        }
    }

    // Fallback: any a[href^="magnet:"] on the page (broader, catches alternate layouts)
    let broad_sel = Selector::parse(r#"a[href^="magnet:"]"#).unwrap();
    if let Some(el) = doc.select(&broad_sel).next() {
        if let Some(href) = el.value().attr("href") {
            return Ok(href.to_string());
        }
    }

    anyhow::bail!("no magnet found on detail page {detail_url}")
}

// ── HTTP helper ───────────────────────────────────────────────────────────────

async fn get_html(client: &reqwest::Client, url: &str) -> Result<String> {
    let resp = client.get(url)
        .header("Accept", "text/html,application/xhtml+xml")
        .header("Accept-Language", "en-US,en;q=0.9")
        .send()
        .await?;

    if !resp.status().is_success() {
        anyhow::bail!("HTTP {} for {url}", resp.status());
    }
    Ok(resp.text().await?)
}

// Try mirrors in order until one succeeds
pub async fn get_html_with_fallback(client: &reqwest::Client, path: &str) -> Result<String> {
    for mirror in MIRRORS {
        let url = format!("{mirror}{path}");
        match get_html(client, &url).await {
            Ok(html) => return Ok(html),
            Err(e) => log::warn!("[1337x] mirror {mirror} failed: {e}"),
        }
    }
    anyhow::bail!("all 1337x mirrors failed for path {path}")
}
