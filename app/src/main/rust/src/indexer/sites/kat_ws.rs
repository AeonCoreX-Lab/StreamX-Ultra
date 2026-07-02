// src/indexer/sites/kat_ws.rs
//
// kickasstorrents.ws indexer — ported from Jackett's kickasstorrents-ws.yml
//
// This is a DIFFERENT KAT clone from the one in kat.rs (which targets
// kickass.torrentbay.st). Different domain family, different HTML
// structure, different magnet-extraction mechanism — kept as a separate
// module rather than folded into kat.rs to avoid selector cross-talk.
//
// KEY POINTS from Jackett YAML:
//   Base URL   :  https://kickass.ws/  (plus many alternate domains)
//   Search URL :  usearch/{QUERY}/?field=time_add&sorder=desc
//   Row selector: table.data tr[id]:has(a[data-download])
//   Title      :  a[class="cellMainLink"]
//   Magnet     :  NOT a direct href — the site wraps it:
//                 td:nth-child(1) > div > a[data-download] @href is a
//                 redirector URL with the real magnet embedded as a
//                 `url=` querystring parameter (Jackett's `querystring`
//                 filter extracts it). We replicate that by parsing the
//                 href's query string ourselves rather than following it.
//   Size       :  td:nth-child(2)
//   Seeders    :  td:nth-child(4)  (or literal "N/A" → 0)
//   Leechers   :  td:nth-child(5)  (or literal "N/A" → 0)
//
// info_flaresolverr is flagged on this definition (same as 1337x/KAT) —
// intermittent Cloudflare challenges are possible but not guaranteed;
// we still try plain HTTP first, same policy as the rest of the indexer.

use anyhow::Result;
use scraper::{Html, Selector};
use crate::indexer::types::TorrentResult;

const MIRRORS: &[&str] = &[
    "https://kickass.ws",
    "https://kickasstorrents.bz",
];

pub async fn search(client: &reqwest::Client, query: &str) -> Vec<TorrentResult> {
    let q = urlencoding::encode(query);
    let path = format!("/usearch/{q}/?field=time_add&sorder=desc");
    fetch_results(client, &path).await.unwrap_or_default()
}

// ── Internal ─────────────────────────────────────────────────────────────────

async fn fetch_results(client: &reqwest::Client, path: &str) -> Result<Vec<TorrentResult>> {
    let html = get_html_with_fallback(client, path).await?;
    let doc  = Html::parse_document(&html);

    // Jackett: rows = table.data tr[id]:has(a[data-download])
    let row_sel   = Selector::parse(r#"table.data tr[id]:has(a[data-download])"#).unwrap();
    let title_sel = Selector::parse(r#"a[class="cellMainLink"]"#).unwrap();
    let dl_sel    = Selector::parse("td:nth-child(1) > div > a[data-download]").unwrap();
    let size_sel  = Selector::parse("td:nth-child(2)").unwrap();
    let seeds_sel = Selector::parse("td:nth-child(4)").unwrap();
    let leech_sel = Selector::parse("td:nth-child(5)").unwrap();

    let mut results = Vec::new();

    for row in doc.select(&row_sel) {
        let title_el = match row.select(&title_sel).next() { Some(e) => e, None => continue };
        let title = title_el.text().collect::<String>().trim().to_string();
        if title.is_empty() { continue; }

        // Magnet is embedded in a `url=` querystring param on the
        // data-download link, not a direct magnet: href — decode it.
        let magnet = match row.select(&dl_sel).next()
            .and_then(|e| e.value().attr("href"))
            .and_then(extract_url_param)
        {
            Some(m) if m.starts_with("magnet:") => m,
            _ => continue,
        };

        let size = row.select(&size_sel).next()
            .map(|e| e.text().collect::<String>().trim().to_string())
            .unwrap_or_default();

        let seeds = row.select(&seeds_sel).next()
            .map(|e| e.text().collect::<String>().trim().to_string())
            .filter(|s| s != "N/A")
            .and_then(|s| s.replace(',', "").parse::<u32>().ok())
            .unwrap_or(0);
        let peers = row.select(&leech_sel).next()
            .map(|e| e.text().collect::<String>().trim().to_string())
            .filter(|s| s != "N/A")
            .and_then(|s| s.replace(',', "").parse::<u32>().ok())
            .unwrap_or(0);

        if seeds == 0 { continue; }

        let mut r = TorrentResult {
            title,
            magnet,
            size,
            seeds,
            peers,
            source: "KickassTorrents-WS".to_string(),
            ..Default::default()
        };
        r.parse_tags();
        results.push(r);
    }
    Ok(results)
}

/// Extract the `url=` querystring parameter from a redirector href and
/// URL-decode it — matches Jackett's `querystring` filter with `args: url`.
fn extract_url_param(href: &str) -> Option<String> {
    let query_start = href.find('?')? + 1;
    let query = &href[query_start..];
    for pair in query.split('&') {
        let mut parts = pair.splitn(2, '=');
        let key = parts.next()?;
        let val = parts.next()?;
        if key == "url" {
            return urlencoding::decode(val).ok().map(|s| s.into_owned());
        }
    }
    None
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
            Err(e) => log::warn!("[KAT-WS] mirror {mirror} failed: {e}"),
        }
    }
    anyhow::bail!("all KAT-WS mirrors failed for {path}")
}
