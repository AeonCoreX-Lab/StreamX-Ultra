// src/indexer/sites/tgx.rs
//
// TorrentGalaxyClone indexer — ported from Jackett's torrentgalaxyclone.yml
//
// KEY POINTS from Jackett YAML:
//   Base URL   :  https://torrentgalaxy.one/ (with fallback torrentgalaxy.info)
//   Search URL :  get-posts/keywords:{QUERY}
//   IMDB search:  get-posts/keywords:{IMDB_ID}   ← exact match, very reliable
//   Row selector: div.tgxtablerow
//   Title      :  a[href^="/post-detail/"] @title
//   Detail href:  a[href^="/post-detail/"] @href
//   Magnet     :  a[href^="magnet:?xt="]  @href   ← IN THE LISTING (no detail page needed!)
//   Seeds      :  div.tgxtablecell:nth-last-child(2) span font
//   Leechers   :  div.tgxtablecell:nth-last-child(2) span font:nth-of-type(2)
//   Size       :  div.tgxtablecell:nth-last-child(5)
//   IMDB link  :  a[href^="/get-posts/keywords:tt"] @href  (for known IMDB IDs)
//
// ADVANTAGE: magnet is directly in search results — no detail page fetch needed.
// IMDB ID search gives exact movie matches (critical for dubbed version of specific film).

use anyhow::Result;
use scraper::{Html, Selector};
use crate::indexer::types::TorrentResult;

const MIRRORS: &[&str] = &[
    "https://torrentgalaxy.one",
    "https://torrentgalaxy.info",
];

/// Search by keyword
pub async fn search(client: &reqwest::Client, query: &str) -> Vec<TorrentResult> {
    let q = urlencoding::encode(query);
    let path = format!("/get-posts/keywords:{q}");
    fetch_results(client, &path).await.unwrap_or_default()
}

/// Search by IMDB ID (e.g. "tt1375666") — most precise match for a specific movie
/// Jackett path: get-posts/keywords:{imdbid}
pub async fn search_by_imdb(client: &reqwest::Client, imdb_id: &str) -> Vec<TorrentResult> {
    let path = format!("/get-posts/keywords:{imdb_id}");
    fetch_results(client, &path).await.unwrap_or_default()
}

// ── Internal ─────────────────────────────────────────────────────────────────

async fn fetch_results(client: &reqwest::Client, path: &str) -> Result<Vec<TorrentResult>> {
    let html = get_html_with_fallback(client, path).await?;
    let doc  = Html::parse_document(&html);

    // Jackett: rows = div.tgxtablerow
    let row_sel    = Selector::parse("div.tgxtablerow").unwrap();
    // Jackett: title = a[href^="/post-detail/"] @title attribute
    let title_sel  = Selector::parse(r#"a[href^="/post-detail/"]"#).unwrap();
    // Jackett: magnet = a[href^="magnet:?xt="] — directly in listing row!
    let magnet_sel = Selector::parse(r#"a[href^="magnet:?xt="]"#).unwrap();
    // Jackett: size = div.tgxtablecell:nth-last-child(5)
    let size_sel   = Selector::parse("div.tgxtablecell:nth-last-child(5)").unwrap();
    // Jackett: seeders = div.tgxtablecell:nth-last-child(2) span font
    let seeds_sel  = Selector::parse("div.tgxtablecell:nth-last-child(2) span font").unwrap();

    let mut results = Vec::new();

    for row in doc.select(&row_sel) {
        // Title from @title attribute (Jackett uses this to get full untruncated title)
        let title_el = match row.select(&title_sel).next() { Some(e) => e, None => continue };
        let title = title_el.value().attr("title")
            .map(|s| s.trim().to_string())
            .or_else(|| Some(title_el.text().collect::<String>().trim().to_string()))
            .unwrap_or_default();
        if title.is_empty() { continue; }

        // Magnet — directly available in listing (TGx advantage over 1337x)
        let magnet = match row.select(&magnet_sel).next()
            .and_then(|e| e.value().attr("href"))
        {
            Some(m) => m.to_string(),
            None => continue, // skip if no magnet
        };

        let size = row.select(&size_sel).next()
            .map(|e| e.text().collect::<String>().trim().to_string())
            .unwrap_or_default();

        // Seeds: first <font> inside seeds cell
        let seeds_text = row.select(&seeds_sel).next()
            .map(|e| e.text().collect::<String>())
            .unwrap_or_default();
        let seeds = seeds_text.trim().parse::<u32>().unwrap_or(0);

        // Leechers: second <font> — select all fonts and take [1]
        let peers = {
            let fonts: Vec<_> = row.select(&seeds_sel).collect();
            fonts.get(1)
                .map(|e| e.text().collect::<String>().trim().parse::<u32>().unwrap_or(0))
                .unwrap_or(0)
        };

        if seeds == 0 { continue; }

        let mut r = TorrentResult {
            title,
            magnet,
            size,
            seeds,
            peers,
            source: "TorrentGalaxy".to_string(),
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
            Err(e) => log::warn!("[TGx] mirror {mirror} failed: {e}"),
        }
    }
    anyhow::bail!("all TGx mirrors failed for {path}")
}
