// src/indexer/sites/torrentdownload.rs
//
// TorrentDownload indexer — ported from Jackett's torrentdownload.yml
//
// KEY POINTS from Jackett YAML:
//   Base URL   :  https://www.torrentdownload.info/
//   Search URL :  searchd?q={QUERY}   (sort=d → by date, default)
//   Row selector: table.table2 > tbody > tr:has(span.smallish)
//   Category   :  div.tt-name > span.smallish  (text, strip non-alpha)
//   Title      :  div.tt-name > a[href^="/"]
//   Detail href:  div.tt-name > a[href^="/"] @href   ← detail page needed for magnet
//   Size       :  td:nth-child(3)
//   Seeds      :  td.tdseed
//   Leechers   :  td.tdleech
//
// DETAIL PAGE: magnet = a[href^="magnet:?xt="] @href
//
// HAS dedicated category "MoviesDubbedMovies" in categorymappings —
// we filter by category text post-parse as well.

use anyhow::Result;
use scraper::{Html, Selector};
use crate::indexer::types::TorrentResult;

const BASE: &str = "https://www.torrentdownload.info";

pub async fn search(client: &reqwest::Client, query: &str) -> Vec<TorrentResult> {
    let q = urlencoding::encode(query);
    // Jackett path: searchd?q={Keywords}  (sort=d is default)
    let url = format!("{BASE}/searchd?q={q}");
    match fetch_list(client, &url).await {
        Ok(r)  => r,
        Err(e) => { log::warn!("[TorrentDownload] search failed: {e}"); vec![] }
    }
}

// ── Internal ─────────────────────────────────────────────────────────────────

async fn fetch_list(client: &reqwest::Client, url: &str) -> Result<Vec<TorrentResult>> {
    let html = get_html(client, url).await?;
    let doc  = Html::parse_document(&html);

    // Jackett: table.table2 > tbody > tr:has(span.smallish)
    let row_sel  = Selector::parse("table.table2 > tbody > tr:has(span.smallish)").unwrap();
    // Jackett: category = div.tt-name > span.smallish (strip non-alpha)
    let cat_sel  = Selector::parse("div.tt-name > span.smallish").unwrap();
    // Jackett: title = div.tt-name > a[href^="/"]
    let title_sel = Selector::parse(r#"div.tt-name > a[href^="/"]"#).unwrap();
    // Jackett: size = td:nth-child(3)
    let size_sel  = Selector::parse("td:nth-child(3)").unwrap();
    // Jackett: seeds = td.tdseed
    let seeds_sel = Selector::parse("td.tdseed").unwrap();
    // Jackett: leechers = td.tdleech
    let leech_sel = Selector::parse("td.tdleech").unwrap();

    let mut results = Vec::new();

    for row in doc.select(&row_sel) {
        // Category — Jackett strips all non-alpha: [^A-Za-z]+  → ""
        let category = row.select(&cat_sel).next()
            .map(|e| {
                let raw = e.text().collect::<String>();
                // same regex Jackett applies: remove everything except letters
                raw.chars().filter(|c| c.is_ascii_alphabetic()).collect::<String>()
            })
            .unwrap_or_default();

        // Only keep movie/video categories — skip software, XXX, etc.
        // Jackett category "MoviesDubbedMovies" after stripping = "MoviesDubbedMovies"
        // We also keep generic "Movies" + "VideoMovies"
        let keep_cats = ["Movies", "VideoMovies", "MoviesDubbedMovies", "MoviesHighresMovies"];
        if !category.is_empty() && !keep_cats.iter().any(|&c| category.contains(c)) {
            continue;
        }

        let title_el = match row.select(&title_sel).next() { Some(e) => e, None => continue };
        let title = title_el.text().collect::<String>().trim().to_string();
        let detail_href = match title_el.value().attr("href") { Some(h) => h, None => continue };
        let detail_url = format!("{BASE}{detail_href}");

        let size  = row.select(&size_sel).next()
            .map(|e| e.text().collect::<String>().trim().to_string())
            .unwrap_or_default();
        let seeds = row.select(&seeds_sel).next()
            .and_then(|e| e.text().collect::<String>().trim().parse::<u32>().ok())
            .unwrap_or(0);
        let peers = row.select(&leech_sel).next()
            .and_then(|e| e.text().collect::<String>().trim().parse::<u32>().ok())
            .unwrap_or(0);

        if seeds == 0 { continue; }

        // Fetch magnet from detail page
        let magnet = match fetch_magnet(client, &detail_url).await {
            Ok(m)  => m,
            Err(e) => { log::warn!("[TorrentDownload] magnet fetch failed for {title}: {e}"); continue; }
        };

        let mut r = TorrentResult {
            title,
            magnet,
            size,
            seeds,
            peers,
            source: "TorrentDownload".to_string(),
            ..Default::default()
        };
        r.parse_tags();
        results.push(r);
    }
    Ok(results)
}

// ── Detail page magnet extraction ────────────────────────────────────────────
// Jackett download.selectors: a[href^="magnet:?xt="] @href

async fn fetch_magnet(client: &reqwest::Client, detail_url: &str) -> Result<String> {
    let html = get_html(client, detail_url).await?;
    let doc  = Html::parse_document(&html);

    let sel = Selector::parse(r#"a[href^="magnet:?xt="]"#).unwrap();
    if let Some(el) = doc.select(&sel).next() {
        if let Some(href) = el.value().attr("href") {
            return Ok(href.to_string());
        }
    }
    // Broader fallback
    let broad = Selector::parse(r#"a[href^="magnet:"]"#).unwrap();
    if let Some(el) = doc.select(&broad).next() {
        if let Some(href) = el.value().attr("href") {
            return Ok(href.to_string());
        }
    }
    anyhow::bail!("no magnet on {detail_url}")
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
