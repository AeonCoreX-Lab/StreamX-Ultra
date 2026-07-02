// src/indexer/sites/kat.rs
//
// KickassTorrents indexer — ported from Jackett's kickasstorrents-to.yml
//
// KEY POINTS from Jackett YAML:
//   Base URL   :  https://kickass.torrentbay.st/ (multiple mirrors)
//   Search URL :  search/?q={QUERY}   (page 1)
//                 search/?page=2&q={QUERY}  (page 2)
//   requestDelay: 2
//   Row selector: table.data > tbody > tr:has(a[href^="magnet:?xt="])
//   Title      :  a.cellMainLink
//   Magnet     :  a[href^="magnet:?xt="] @href  ← IN THE LISTING (no detail page)
//   Size       :  td:nth-child(2)
//   Seeds      :  td:nth-child(5)
//   Leechers   :  td:nth-child(6)
//
// CATEGORY of interest:
//   "DubbedMovies" / "MoviesDubbedMovies" → Movies Dubbed (from categorymappings)
//   "Bollywood" / "MoviesBollywood" → Bollywood content
//   We filter post-fetch by audio_tags since category column isn't reliable.

use anyhow::Result;
use scraper::{Html, Selector};
use crate::indexer::types::TorrentResult;

const MIRRORS: &[&str] = &[
    "https://kickass.torrentbay.st",
    "https://kickass.torrentsbay.org",
    "https://kickasstorrents.unblockninja.com",
];

/// Keyword search, 2 pages
pub async fn search(client: &reqwest::Client, query: &str) -> Vec<TorrentResult> {
    let q = urlencoding::encode(query);
    let mut results = Vec::new();

    // Jackett paths:  search/?q={Keywords}  and  search/?page=2&q={Keywords}
    let paths = [
        format!("/search/?q={q}"),
        format!("/search/?page=2&q={q}"),
    ];

    for path in &paths {
        match fetch_list(client, path).await {
            Ok(mut r) => results.append(&mut r),
            Err(e) => { log::warn!("[KAT] page failed: {e}"); break; }
        }
        tokio::time::sleep(std::time::Duration::from_millis(1500)).await;
    }
    results
}

// ── Internal ─────────────────────────────────────────────────────────────────

async fn fetch_list(client: &reqwest::Client, path: &str) -> Result<Vec<TorrentResult>> {
    let html = get_html_with_fallback(client, path).await?;
    let doc  = Html::parse_document(&html);

    // Jackett: rows = table.data > tbody > tr:has(a[href^="magnet:?xt="])
    let row_sel    = Selector::parse(r#"table.data > tbody > tr:has(a[href^="magnet:?xt="])"#).unwrap();
    // Jackett: title = a.cellMainLink
    let title_sel  = Selector::parse("a.cellMainLink").unwrap();
    // Jackett: download = a[href^="magnet:?xt="] @href
    let magnet_sel = Selector::parse(r#"a[href^="magnet:?xt="]"#).unwrap();
    // Jackett: size = td:nth-child(2)
    let size_sel   = Selector::parse("td:nth-child(2)").unwrap();
    // Jackett: seeders = td:nth-child(5)
    let seeds_sel  = Selector::parse("td:nth-child(5)").unwrap();
    // Jackett: leechers = td:nth-child(6)
    let leech_sel  = Selector::parse("td:nth-child(6)").unwrap();

    let mut results = Vec::new();

    for row in doc.select(&row_sel) {
        let title = match row.select(&title_sel).next() {
            Some(e) => e.text().collect::<String>().trim().to_string(),
            None    => continue,
        };
        let magnet = match row.select(&magnet_sel).next()
            .and_then(|e| e.value().attr("href"))
        {
            Some(m) => m.to_string(),
            None    => continue,
        };

        let size  = row.select(&size_sel).next()
            .map(|e| e.text().collect::<String>().trim().to_string())
            .unwrap_or_default();
        let seeds = row.select(&seeds_sel).next()
            .and_then(|e| {
                e.text().collect::<String>().trim()
                    .replace(',', "").parse::<u32>().ok()
            })
            .unwrap_or(0);
        let peers = row.select(&leech_sel).next()
            .and_then(|e| {
                e.text().collect::<String>().trim()
                    .replace(',', "").parse::<u32>().ok()
            })
            .unwrap_or(0);

        if seeds == 0 { continue; }

        let mut r = TorrentResult {
            title,
            magnet,
            size,
            seeds,
            peers,
            source: "KickassTorrents".to_string(),
            ..Default::default()
        };
        r.parse_tags();
        results.push(r);
    }
    Ok(results)
}

// ── HTTP helpers ─────────────────────────────────────────────────────────────

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

async fn get_html_with_fallback(client: &reqwest::Client, path: &str) -> Result<String> {
    for mirror in MIRRORS {
        let url = format!("{mirror}{path}");
        match get_html(client, &url).await {
            Ok(html) => return Ok(html),
            Err(e) => log::warn!("[KAT] mirror {mirror} failed: {e}"),
        }
    }
    anyhow::bail!("all KAT mirrors failed for {path}")
}
