// src/indexer/config/generic_json.rs
//
// Config-driven JSON-API client — replaces the hardcoded struct field
// access in therarbg.rs/tpb.rs. Field NAMES (which JSON key is "title",
// which is "seeders", etc) come from the remote config's json_fields
// block, so if TheRARBG or TPB ever rename a response field, that's a
// JSON edit in streamx-addons, not a Rust code change + APK release.
//
// Uses serde_json::Value generically rather than a typed struct per
// site, since the whole point is that the field layout isn't known at
// compile time.

use anyhow::Result;
use serde_json::Value;

use crate::indexer::config::schema::SiteConfig;
use crate::indexer::types::TorrentResult;

pub async fn search(
    client:   &reqwest::Client,
    site_id:  &str,
    config:   &SiteConfig,
    query:    &str,
    imdb_id:  Option<&str>,
) -> Vec<TorrentResult> {
    let fields = match &config.json_fields {
        Some(f) => f,
        None => {
            log::warn!("[{site_id}] JSON site config missing json_fields block");
            return vec![];
        }
    };

    let path = build_path(config, query, imdb_id);

    for mirror in &config.mirrors {
        let url = format!("{mirror}{path}");
        match fetch_and_parse(client, config, fields, &url).await {
            Ok(results) if !results.is_empty() => return results,
            Ok(_) => continue, // empty, try next mirror
            Err(e) => log::warn!("[{site_id}] mirror {mirror} failed: {e}"),
        }
    }
    vec![]
}

fn build_path(config: &SiteConfig, query: &str, imdb_id: Option<&str>) -> String {
    match imdb_id {
        Some(id) if config.imdb_path.is_some() => {
            config.imdb_path.as_ref().unwrap().replace("{imdb_id}", id)
        }
        _ => {
            let q = urlencoding::encode(query);
            config.search_path.replace("{query}", &q)
        }
    }
}

async fn fetch_and_parse(
    client: &reqwest::Client,
    config: &SiteConfig,
    fields: &crate::indexer::config::schema::JsonFields,
    url:    &str,
) -> Result<Vec<TorrentResult>> {
    let mut req = client.get(url);
    for (k, v) in &config.request.headers {
        req = req.header(k.as_str(), v.as_str());
    }
    let resp = req.send().await?;
    if !resp.status().is_success() {
        anyhow::bail!("HTTP {} for {url}", resp.status());
    }

    let body: Value = resp.json().await?;
    let array = navigate_to_array(&body, &fields.results_array)?;

    let mut results = Vec::new();
    for item in array {
        if let Some(r) = item_to_result(item, fields, &config.display_name) {
            results.push(r);
        }
    }
    Ok(results)
}

/// Follow a dotted path (e.g. "data.items") to find the results array.
/// An empty path means the response body itself is the array.
fn navigate_to_array<'a>(body: &'a Value, path: &str) -> Result<Vec<Value>> {
    if path.is_empty() {
        return body.as_array().cloned()
            .ok_or_else(|| anyhow::anyhow!("response body is not a JSON array"));
    }
    let mut current = body;
    for segment in path.split('.') {
        current = current.get(segment)
            .ok_or_else(|| anyhow::anyhow!("results_array path '{path}' not found in response"))?;
    }
    current.as_array().cloned()
        .ok_or_else(|| anyhow::anyhow!("results_array path '{path}' did not resolve to an array"))
}

fn item_to_result(
    item:         Value,
    fields:       &crate::indexer::config::schema::JsonFields,
    display_name: &str,
) -> Option<TorrentResult> {
    let title = get_str(&item, &fields.title)?;

    let magnet = if fields.infohash_is_full_magnet {
        get_str(&item, &fields.infohash)?
    } else {
        let hash = get_str(&item, &fields.infohash)?;
        if hash.is_empty() { return None; }
        build_magnet(&hash, &title)
    };

    let size  = get_str_any(&item, &fields.size).unwrap_or_default();
    let seeds = get_number(&item, &fields.seeds).unwrap_or(0);
    let peers = get_number(&item, &fields.peers).unwrap_or(0);

    if seeds == 0 { return None; }

    let mut r = TorrentResult {
        title,
        magnet,
        size,
        seeds,
        peers,
        source: display_name.to_string(),
        ..Default::default()
    };
    r.parse_tags();

    if let Some(cat_field) = &fields.category {
        if let Some(cat) = get_str_any(&item, cat_field) {
            if cat.to_lowercase().contains("xxx") { return None; }
        }
    }

    Some(r)
}

fn get_str(item: &Value, field: &str) -> Option<String> {
    item.get(field)?.as_str().map(|s| s.to_string())
}

/// Like get_str, but also accepts a JSON number/bool by stringifying it —
/// some APIs (TPB) return sizes/seeds as strings, others might not.
fn get_str_any(item: &Value, field: &str) -> Option<String> {
    let v = item.get(field)?;
    match v {
        Value::String(s) => Some(s.clone()),
        Value::Number(n) => Some(n.to_string()),
        _ => None,
    }
}

fn get_number(item: &Value, field: &str) -> Option<u32> {
    let v = item.get(field)?;
    match v {
        Value::Number(n) => n.as_u64().map(|x| x as u32),
        Value::String(s) => s.trim().replace(',', "").parse::<u32>().ok(),
        _ => None,
    }
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
