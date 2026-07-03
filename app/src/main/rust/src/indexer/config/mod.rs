// src/indexer/config/mod.rs

pub mod schema;
pub mod loader;
pub mod generic_html;
pub mod generic_json;

use crate::indexer::types::TorrentResult;
use schema::SiteKind;

/// Run a search against ONE site by its config-file key (e.g. "x1337x",
/// "tgx", "therarbg"). Dispatches to the HTML or JSON engine based on
/// `SiteConfig.kind`. Returns an empty Vec if the site is disabled in
/// the remote config or config lookup fails — callers don't need to
/// special-case either.
pub async fn search_site(
    client:   &reqwest::Client,
    config:   &schema::IndexerConfig,
    site_id:  &str,
    query:    &str,
    imdb_id:  Option<&str>,
) -> Vec<TorrentResult> {
    let site = match config.sites.get(site_id) {
        Some(s) => s,
        None => {
            log::warn!("[indexer] no config entry for site '{site_id}'");
            return vec![];
        }
    };
    if !site.enabled {
        log::info!("[indexer] site '{site_id}' disabled via remote config, skipping");
        return vec![];
    }

    match site.kind {
        SiteKind::Html => generic_html::search(client, site_id, site, query, imdb_id).await,
        SiteKind::Json => generic_json::search(client, site_id, site, query, imdb_id).await,
    }
}

/// Convenience: run search_site() across several site keys concurrently
/// and flatten the results. This is what engine.rs's search_*() functions
/// use instead of tokio::join!-ing individual per-site module calls.
pub async fn search_sites(
    client:   &reqwest::Client,
    config:   &schema::IndexerConfig,
    site_ids: &[&str],
    query:    &str,
    imdb_id:  Option<&str>,
) -> Vec<TorrentResult> {
    let futures = site_ids.iter().map(|id| search_site(client, config, id, query, imdb_id));
    let results = futures::future::join_all(futures).await;
    results.into_iter().flatten().collect()
}
