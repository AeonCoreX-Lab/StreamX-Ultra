// src/indexer/sites/kdrama.rs
//
// Korean drama indexers — ported from Jackett's torrentqq.yml and
// torrentsome.yml. Both are KOREAN public trackers with no login.
//
// IMPORTANT SITE LIMITATION (verified against both YAML definitions):
// Neither site exposes real seeder/leecher counts — Jackett's own
// definitions hardcode `seeders: {text: 1}` because the sites don't
// publish swarm health data at all (Korean torrent sites of this type
// tend to be direct-download-oriented, DHT swarm stats aren't tracked
// the way 1337x/TGx/RARBG track them). We surface this honestly by
// setting seeds = 1 (matching Jackett's own placeholder) rather than
// pretending we have real data — the UI should treat a `source` of
// "TorrentQQ"/"Torrentsome" as "health unknown", not "low seeders".
//
// Both require a detail-page fetch to extract the magnet/infohash —
// magnet is not present in the search listing itself.

use anyhow::Result;
use scraper::{Html, Selector};
use crate::indexer::types::TorrentResult;

const TORRENTQQ_MIRRORS: &[&str] = &[
    "https://torrentqq418.com",
];
const TORRENTSOME_MIRRORS: &[&str] = &[
    "https://torrentsome252.com",
];

/// Placeholder seed value used when a site provides no real swarm data.
/// Matches Jackett's own convention for these two indexers (`text: 1`).
/// TorrentEngine should treat this specially — see doc comment above.
const UNKNOWN_HEALTH_SEEDS: u32 = 1;

// ── TorrentQQ ──────────────────────────────────────────────────────────────

pub async fn search_torrentqq(client: &reqwest::Client, query: &str) -> Vec<TorrentResult> {
    let q = urlencoding::encode(query);
    let path = format!("/search?q={q}");
    fetch_torrentqq_list(client, &path).await.unwrap_or_default()
}

async fn fetch_torrentqq_list(client: &reqwest::Client, path: &str) -> Result<Vec<TorrentResult>> {
    let html = get_html(client, TORRENTQQ_MIRRORS, path).await?;
    let doc  = Html::parse_document(&html);

    // Jackett: rows = ul#searchresult > li:has(a[href$=".html"][title])
    let row_sel   = Selector::parse(r#"ul#searchresult > li:has(a[href$=".html"][title])"#).unwrap();
    let link_sel  = Selector::parse(r#"a[href$=".html"][title]"#).unwrap();
    let size_sel  = Selector::parse("div.wr-size").unwrap();

    let mut metas = Vec::new();
    for row in doc.select(&row_sel) {
        let link_el = match row.select(&link_sel).next() { Some(e) => e, None => continue };
        let title   = link_el.value().attr("title")
            .map(|s| s.to_string())
            .unwrap_or_else(|| link_el.text().collect::<String>());
        let title = title.trim().to_string();
        let detail = match link_el.value().attr("href") { Some(h) => h, None => continue };
        let size   = row.select(&size_sel).next()
            .map(|e| format!("{}B", e.text().collect::<String>().trim()))
            .unwrap_or_default();
        if title.is_empty() { continue; }
        metas.push((title, detail.to_string(), size));
    }

    // Detail-page magnet fetches run concurrently
    let futures = metas.into_iter().map(|(title, detail, size)| {
        let client = client.clone();
        async move {
            let detail_url = format!("{}{}", TORRENTQQ_MIRRORS[0], detail);
            match fetch_torrentqq_magnet(&client, &detail_url).await {
                Ok(magnet) => {
                    let mut r = TorrentResult {
                        title,
                        magnet,
                        size,
                        seeds: UNKNOWN_HEALTH_SEEDS,
                        peers: 0,
                        source: "TorrentQQ".to_string(),
                        ..Default::default()
                    };
                    r.parse_tags();
                    Some(r)
                }
                Err(e) => { log::warn!("[TorrentQQ] magnet fetch failed: {e}"); None }
            }
        }
    });
    let results: Vec<TorrentResult> = futures::future::join_all(futures).await
        .into_iter().flatten().collect();
    Ok(results)
}

async fn fetch_torrentqq_magnet(client: &reqwest::Client, detail_url: &str) -> Result<String> {
    let html = get_html_raw(client, detail_url).await?;
    let doc  = Html::parse_document(&html);

    // Jackett: infohash from table.table-bordered > tbody > tr > td > ul > li,
    // regex-extracted 40-char hex hash. We build a magnet from it directly.
    let hash_sel = Selector::parse("table.table-bordered > tbody > tr > td > ul > li").unwrap();
    let hash_re  = regex_hash();

    for el in doc.select(&hash_sel) {
        let text = el.text().collect::<String>();
        if let Some(hash) = hash_re.find(&text) {
            return Ok(build_magnet(hash.as_str()));
        }
    }
    anyhow::bail!("no infohash found on {detail_url}")
}

// ── Torrentsome ───────────────────────────────────────────────────────────

pub async fn search_torrentsome(client: &reqwest::Client, query: &str) -> Vec<TorrentResult> {
    fetch_torrentsome_list(client, query).await.unwrap_or_default()
}

async fn fetch_torrentsome_list(client: &reqwest::Client, query: &str) -> Result<Vec<TorrentResult>> {
    let q = urlencoding::encode(query);
    let path = format!("/search/index?keywords={q}&search_type=0&order=time&page=1");
    let html = get_html(client, TORRENTSOME_MIRRORS, &path).await?;
    let doc  = Html::parse_document(&html);

    // Jackett: rows = div.topic-item:not(:has(div:nth-child(3):contains("-")))
    let row_sel   = Selector::parse("div.topic-item").unwrap();
    let link_sel  = Selector::parse(r#"a[href^="/v/"]"#).unwrap();
    let size_sel  = Selector::parse("div:nth-last-child(2)").unwrap();

    let mut metas = Vec::new();
    for row in doc.select(&row_sel) {
        let link_el = match row.select(&link_sel).next() { Some(e) => e, None => continue };
        let title   = link_el.value().attr("title")
            .map(|s| s.to_string())
            .unwrap_or_else(|| link_el.text().collect::<String>());
        let title = title.trim().to_string();
        let detail = match link_el.value().attr("href") { Some(h) => h, None => continue };
        let size   = row.select(&size_sel).next()
            .map(|e| e.text().collect::<String>().trim().to_string())
            .unwrap_or_default();
        if title.is_empty() { continue; }
        metas.push((title, detail.to_string(), size));
    }

    let futures = metas.into_iter().map(|(title, detail, size)| {
        let client = client.clone();
        async move {
            let detail_url = format!("{}{}", TORRENTSOME_MIRRORS[0], detail);
            match fetch_torrentsome_magnet(&client, &detail_url).await {
                Ok(magnet) => {
                    let mut r = TorrentResult {
                        title,
                        magnet,
                        size,
                        seeds: UNKNOWN_HEALTH_SEEDS,
                        peers: 0,
                        source: "Torrentsome".to_string(),
                        ..Default::default()
                    };
                    r.parse_tags();
                    Some(r)
                }
                Err(e) => { log::warn!("[Torrentsome] magnet fetch failed: {e}"); None }
            }
        }
    });
    let results: Vec<TorrentResult> = futures::future::join_all(futures).await
        .into_iter().flatten().collect();
    Ok(results)
}

async fn fetch_torrentsome_magnet(client: &reqwest::Client, detail_url: &str) -> Result<String> {
    let html = get_html_raw(client, detail_url).await?;
    let doc  = Html::parse_document(&html);

    // Jackett: magnet directly present on detail page, hash extracted via regex
    let sel = Selector::parse(r#"a[href^="magnet:?xt="]"#).unwrap();
    if let Some(el) = doc.select(&sel).next() {
        if let Some(href) = el.value().attr("href") {
            return Ok(href.to_string());
        }
    }
    anyhow::bail!("no magnet found on {detail_url}")
}

// ── Shared helpers ────────────────────────────────────────────────────────

fn regex_hash() -> regex::Regex {
    regex::Regex::new(r"[A-Fa-f0-9]{40}").expect("valid regex")
}

fn build_magnet(infohash: &str) -> String {
    format!(
        "magnet:?xt=urn:btih:{infohash}\
         &tr=udp://tracker.opentrackr.org:1337/announce\
         &tr=udp://open.demonii.com:1337/announce\
         &tr=udp://tracker.openbittorrent.com:80\
         &tr=udp://exodus.desync.com:6969/announce"
    )
}

// Korean sites block common Linux/bot User-Agents — Jackett notes this
// explicitly for torrentsome. Use a Windows Chrome UA for both.
const KOREAN_SITE_UA: &str =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/87.0.4280.88 Safari/537.35";

async fn get_html(client: &reqwest::Client, mirrors: &[&str], path: &str) -> Result<String> {
    for mirror in mirrors {
        let url = format!("{mirror}{path}");
        match get_html_raw(client, &url).await {
            Ok(html) => return Ok(html),
            Err(e) => log::warn!("[KDrama] mirror {mirror} failed: {e}"),
        }
    }
    anyhow::bail!("all mirrors failed for {path}")
}

async fn get_html_raw(client: &reqwest::Client, url: &str) -> Result<String> {
    let resp = client.get(url)
        .header("User-Agent", KOREAN_SITE_UA)
        .header("Accept", "text/html,application/xhtml+xml")
        .send()
        .await?;
    if !resp.status().is_success() {
        anyhow::bail!("HTTP {} for {url}", resp.status());
    }
    Ok(resp.text().await?)
}
