// src/indexer/sites/extratorrent.rs
//
// ExtraTorrent.st indexer — ported from Jackett's extratorrent-st.yml
//
// KEY POINTS from Jackett YAML:
//   Base URL   :  https://extratorrent.st/
//   Search URL :  search/?srt=added&order=desc&search={QUERY}&new=1&x=0&y=0
//   Row selector: tr[class^="tl"]:has(a[href^="magnet:?xt="])
//   Category   :  span.c_tor  (has explicit "Bollywood" and "Dubbed Movies" tags!)
//   Title      :  a[href^="/torrent/"]:not([href$="comments"])
//   Magnet     :  a[href^="magnet:?xt="] @href   ← IN THE LISTING (no detail page)
//   Size       :  td:nth-last-of-type(4)
//   Seeders    :  td.sy, td.sn
//   Leechers   :  td.ly, td.ln
//
// ADVANTAGE over 1337x/KAT: this site has EXPLICIT "Bollywood" and
// "Dubbed Movies" categories in its own taxonomy (categorymappings in
// the Jackett YAML), so its own category label is often a stronger dub
// signal than title-text pattern matching alone — we combine both.

use anyhow::Result;
use scraper::{Html, Selector};
use crate::indexer::types::TorrentResult;

const MIRRORS: &[&str] = &[
    "https://extratorrent.st",
];

pub async fn search(client: &reqwest::Client, query: &str) -> Vec<TorrentResult> {
    let q = urlencoding::encode(query);
    let path = format!("/search/?srt=added&order=desc&search={q}&new=1&x=0&y=0");
    fetch_results(client, &path).await.unwrap_or_default()
}

/// Same search, but only keeps rows whose OWN site category is
/// Bollywood/Dubbed (in addition to whatever title-tag parsing finds).
/// This is a stronger signal than title parsing alone since it comes
/// from the site's own classification.
pub async fn search_dubbed(client: &reqwest::Client, query: &str) -> Vec<TorrentResult> {
    let mut results = search(client, query).await;
    results.retain(|r| r.is_dubbed());
    results
}

// ── Internal ─────────────────────────────────────────────────────────────────

async fn fetch_results(client: &reqwest::Client, path: &str) -> Result<Vec<TorrentResult>> {
    let html = get_html_with_fallback(client, path).await?;
    let doc  = Html::parse_document(&html);

    // Jackett: rows = tr[class^="tl"]:has(a[href^="magnet:?xt="])
    let row_sel   = Selector::parse(r#"tr[class^="tl"]:has(a[href^="magnet:?xt="])"#).unwrap();
    // Jackett: category = span.c_tor  (e.g. "in Bollywood", "in Dubbed Movies")
    let cat_sel   = Selector::parse("span.c_tor").unwrap();
    // Jackett: title = a[href^="/torrent/"]:not([href$="comments"])
    let title_sel = Selector::parse(r#"a[href^="/torrent/"]"#).unwrap();
    // Jackett: download = a[href^="magnet:?xt="] @href — directly in row
    let magnet_sel = Selector::parse(r#"a[href^="magnet:?xt="]"#).unwrap();
    // Jackett: size = td:nth-last-of-type(4)
    let size_sel  = Selector::parse("td:nth-last-of-type(4)").unwrap();
    // Jackett: seeders = td.sy, td.sn
    let seeds_sel = Selector::parse("td.sy, td.sn").unwrap();
    // Jackett: leechers = td.ly, td.ln
    let leech_sel = Selector::parse("td.ly, td.ln").unwrap();

    let mut results = Vec::new();

    for row in doc.select(&row_sel) {
        let title_el = match row.select(&title_sel).next() { Some(e) => e, None => continue };
        let title = title_el.text().collect::<String>().trim().to_string();
        if title.is_empty() || title.eq_ignore_ascii_case("comments") { continue; }

        let magnet = match row.select(&magnet_sel).next()
            .and_then(|e| e.value().attr("href"))
        {
            Some(m) => m.to_string(),
            None => continue,
        };

        // Site's own category label — "in Bollywood" / "in Dubbed Movies" etc.
        // Jackett strips the "in " prefix; we do the same.
        let site_category = row.select(&cat_sel).next()
            .map(|e| e.text().collect::<String>().replace("in ", "").trim().to_string())
            .unwrap_or_default();

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

        let mut r = TorrentResult {
            title,
            magnet,
            size,
            seeds,
            peers,
            source: "ExtraTorrent".to_string(),
            ..Default::default()
        };
        r.parse_tags();

        // Fold the site's own category into the audio_tags if it's a
        // dub-relevant category the title-parser might have missed
        // (e.g. a title with no "Hindi"/"Dubbed" word but the site
        // itself filed it under Bollywood).
        let cat_lower = site_category.to_lowercase();
        if cat_lower.contains("bollywood") && !r.audio_tags.iter().any(|t| t == "Hindi") {
            r.audio_tags.push("Hindi".to_string());
        }
        if cat_lower.contains("dubbed") && !r.audio_tags.iter().any(|t| t == "Dubbed") {
            r.audio_tags.push("Dubbed".to_string());
        }

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
            Err(e) => log::warn!("[ExtraTorrent] mirror {mirror} failed: {e}"),
        }
    }
    anyhow::bail!("all ExtraTorrent mirrors failed for {path}")
}
