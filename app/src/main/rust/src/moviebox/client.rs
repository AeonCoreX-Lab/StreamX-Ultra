//! MovieBox HTTP client.
//!
//! Talks to the `/wefeed-mobile-bff/*` API family (the "v3" surface in the
//! reference Python library), which is what real device traffic and the
//! reference lib's own test suite exercise successfully. Endpoints used:
//!
//!   SUBJECT_GET_PATH = /wefeed-mobile-bff/subject-api/get         (item details + dubs[])
//!   SEARCH_PATH       = /wefeed-mobile-bff/subject-api/search      (search)
//!   RESOURCE_PATH     = /wefeed-mobile-bff/subject-api/resource    (direct MP4 stream files — VERIFIED path)
//!   EXT_CAPTIONS_PATH = /wefeed-mobile-bff/subject-api/get-ext-captions
//!
//! All of the above require HMAC request signing (see `crypto.rs`) plus a
//! bearer token. The token is bootstrapped the same way as the legacy web
//! API: call any endpoint once, read the `x-user` response header, cache
//! the token, and refresh it whenever a fresher one shows up on later
//! responses.
//!
//! Host-pool fallback: if a host returns a retryable status code (403, 407,
//! 429, 500, 502, 503, 504) or a transport error, the next host in the pool
//! is tried automatically. This matters in production — individual
//! api*.aoneroom.com hosts get rate-limited/blocked independently.

use anyhow::{anyhow, bail, Context, Result};
use log::{debug, warn};
use once_cell::sync::Lazy;
use parking_lot::RwLock;
use reqwest::Client;
use serde_json::Value;
use url::Url;
use uuid::Uuid;

use super::crypto::build_signed_headers;
use super::types::*;

// ── Host pool ────────────────────────────────────────────────────────────────

const HOST_POOL: &[&str] = &[
    "https://api6.aoneroom.com",
    "https://api5.aoneroom.com",
    "https://api4.aoneroom.com",
    "https://api4sg.aoneroom.com",
    "https://api3.aoneroom.com",
    "https://api6sg.aoneroom.com",
    "https://api.inmoviebox.com",
];

const RETRY_STATUS: &[u16] = &[403, 407, 429, 500, 502, 503, 504];

const MAIN_PAGE_PATH: &str = "/wefeed-mobile-bff/tab-operating";
const SEARCH_PATH: &str = "/wefeed-mobile-bff/subject-api/search";
const SUBJECT_GET_PATH: &str = "/wefeed-mobile-bff/subject-api/get";
const RESOURCE_PATH: &str = "/wefeed-mobile-bff/subject-api/resource";
const EXT_CAPTIONS_PATH: &str = "/wefeed-mobile-bff/subject-api/get-ext-captions";

// ── Shared state ─────────────────────────────────────────────────────────────

static HTTP: Lazy<Client> = Lazy::new(|| {
    Client::builder()
        .timeout(std::time::Duration::from_secs(25))
        .redirect(reqwest::redirect::Policy::limited(5))
        .build()
        .expect("moviebox http client build")
});

static TOKEN: Lazy<RwLock<Option<String>>> = Lazy::new(|| RwLock::new(None));

/// Stable per-process device identity used in the `X-Client-Info` header.
/// Generated once at first use and reused for the lifetime of the process —
/// there is no requirement that this survive app restarts, but keeping it
/// process-stable avoids re-signing with a different fingerprint mid-session.
static DEVICE_ID: Lazy<String> = Lazy::new(|| Uuid::new_v4().simple().to_string());
static GAID: Lazy<String> = Lazy::new(|| Uuid::new_v4().to_string());

fn absorb_x_user(headers: &reqwest::header::HeaderMap) {
    if let Some(x_user) = headers.get("x-user") {
        if let Ok(s) = x_user.to_str() {
            if let Ok(v) = serde_json::from_str::<Value>(s) {
                if let Some(tok) = v.get("token").and_then(|t| t.as_str()) {
                    *TOKEN.write() = Some(tok.to_string());
                }
            }
        }
    }
}

fn current_token() -> Option<String> {
    TOKEN.read().clone()
}

/// Performs one signed request against a specific host, returning the raw
/// response. Does NOT handle host fallback — see `signed_request` for that.
async fn signed_request_once(
    base: &str,
    path_and_query: &str,
    method: &str,
) -> Result<reqwest::Response> {
    let full_url = format!("{}{}", base, path_and_query);
    let url = Url::parse(&full_url).context("invalid MovieBox request URL")?;

    let signed = build_signed_headers(
        method,
        &url,
        "application/json",
        "application/json",
        None,
        &DEVICE_ID,
        &GAID,
    );

    let mut req = match method {
        "GET" => HTTP.get(url.clone()),
        "POST" => HTTP.post(url.clone()),
        other => bail!("unsupported method {other}"),
    };

    req = req
        .header("User-Agent", signed.user_agent)
        .header("Accept", "application/json")
        .header("Content-Type", "application/json")
        .header("Connection", "keep-alive")
        .header("X-Client-Token", signed.x_client_token)
        .header("x-tr-signature", signed.x_tr_signature)
        .header("X-Client-Info", signed.x_client_info)
        .header("X-Client-Status", "0");

    if let Some(tok) = current_token() {
        req = req.header("Authorization", format!("Bearer {}", tok));
    }

    let resp = req.send().await.context("MovieBox request failed")?;
    absorb_x_user(resp.headers());
    Ok(resp)
}

/// Tries each host in `HOST_POOL` in order until one returns a
/// non-retryable status. Returns the winning response's parsed `data`
/// field (MovieBox wraps every payload as `{"code":..., "data": {...}}`).
async fn signed_request(path_and_query: &str, method: &str) -> Result<Value> {
    let mut last_err: Option<anyhow::Error> = None;

    for base in HOST_POOL {
        debug!("[moviebox] {method} {base}{path_and_query} — attempting");
        match signed_request_once(base, path_and_query, method).await {
            Ok(resp) => {
                let status = resp.status().as_u16();
                if RETRY_STATUS.contains(&status) {
                    warn!("[moviebox] {base} returned retryable status {status}, trying next host");
                    last_err = Some(anyhow!("host {base} returned retryable status {status}"));
                    continue;
                }
                let body: Value = match resp.json().await {
                    Ok(v) => v,
                    Err(e) => {
                        warn!("[moviebox] {base} response was not valid JSON: {e}");
                        last_err = Some(anyhow::Error::new(e).context("MovieBox response was not valid JSON"));
                        continue;
                    }
                };

                if let Some(code) = body.get("code").and_then(|c| c.as_i64()) {
                    if code != 0 {
                        let msg = body
                            .get("message")
                            .and_then(|m| m.as_str())
                            .unwrap_or("unknown error");
                        warn!("[moviebox] {base} returned API error code {code}: {msg}");
                        last_err = Some(anyhow!("MovieBox API error (code {code}): {msg}"));
                        continue;
                    }
                }

                debug!("[moviebox] {base} succeeded");
                return Ok(body.get("data").cloned().unwrap_or(body));
            }
            Err(e) => {
                warn!("[moviebox] {base} transport error: {e:#}");
                last_err = Some(e);
                continue;
            }
        }
    }

    Err(last_err.unwrap_or_else(|| anyhow!("all MovieBox hosts exhausted")))
}

/// Bootstraps the bearer token if we don't have one yet, by hitting the
/// (auth-free) homepage endpoint once. Idempotent — a no-op once a token
/// has been absorbed from any prior response.
async fn ensure_token() -> Result<()> {
    if current_token().is_some() {
        return Ok(());
    }
    let path = format!("{}?page=1&tabId=0&version=", MAIN_PAGE_PATH);
    // Ignore body/errors here — we only care about the x-user header,
    // which signed_request_once() already absorbs as a side effect even
    // if the endpoint itself 4xxs. Bounded to one host + the client's own
    // 25s timeout, so this alone cannot be the source of a long hang.
    if let Err(e) = signed_request_once(HOST_POOL[0], &path, "GET").await {
        debug!("[moviebox] ensure_token: bootstrap request errored (may be fine if token already set): {e:#}");
    }
    if current_token().is_none() {
        warn!("[moviebox] ensure_token: failed to acquire bearer token from {}", HOST_POOL[0]);
        bail!("failed to acquire MovieBox bearer token");
    }
    Ok(())
}

// ── Shared deadline wrapper ──────────────────────────────────────────────────
//
// Every public entry point below can internally loop over up to
// HOST_POOL.len() hosts (7), each with its own 25s HTTP timeout — so a
// single call can, in the worst case, take minutes to fail. Since none of
// that is visible to the UI as progress, wrap every public entry point in
// one generous overall deadline so a bad run always surfaces as a definite
// error within a bounded, predictable time instead of hanging.
const DEFAULT_DEADLINE: std::time::Duration = std::time::Duration::from_secs(45);

async fn with_deadline<T, F>(op_name: &str, fut: F) -> Result<T>
where
    F: std::future::Future<Output = Result<T>>,
{
    match tokio::time::timeout(DEFAULT_DEADLINE, fut).await {
        Ok(inner) => inner,
        Err(_) => {
            warn!("[moviebox] {op_name} timed out after {DEFAULT_DEADLINE:?}");
            bail!("MovieBox request timed out — the source may be temporarily unavailable");
        }
    }
}

// ── Public API ────────────────────────────────────────────────────────────────

/// SEARCH_PATH is a POST endpoint whose body must be included in the HMAC
/// signature (see `crypto::build_signed_headers`'s `body` param) — this is
/// handled inline here rather than through `signed_request()`, which only
/// signs no-body GETs.
pub async fn search(query: &str, page: u32) -> Result<Vec<SearchItem>> {
    with_deadline("search", search_inner(query, page)).await
}

async fn search_inner(query: &str, page: u32) -> Result<Vec<SearchItem>> {
    ensure_token().await.ok(); // best-effort; search may work token-less

    let body = serde_json::json!({
        "keyword": query,
        "page": page,
        "perPage": 20,
    })
    .to_string();

    let mut last_err: Option<anyhow::Error> = None;

    for base in HOST_POOL {
        let full_url = format!("{}{}", base, SEARCH_PATH);
        let url = match Url::parse(&full_url) {
            Ok(u) => u,
            Err(e) => {
                last_err = Some(e.into());
                continue;
            }
        };

        let signed = build_signed_headers(
            "POST",
            &url,
            "application/json",
            "application/json",
            Some(&body),
            &DEVICE_ID,
            &GAID,
        );

        let mut req = HTTP
            .post(url.clone())
            .header("User-Agent", signed.user_agent)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("Connection", "keep-alive")
            .header("X-Client-Token", signed.x_client_token)
            .header("x-tr-signature", signed.x_tr_signature)
            .header("X-Client-Info", signed.x_client_info)
            .header("X-Client-Status", "0")
            .body(body.clone());

        if let Some(tok) = current_token() {
            req = req.header("Authorization", format!("Bearer {}", tok));
        }

        match req.send().await {
            Ok(resp) => {
                absorb_x_user(resp.headers());
                let status = resp.status().as_u16();
                if RETRY_STATUS.contains(&status) {
                    last_err = Some(anyhow!("host {base} returned {status}"));
                    continue;
                }
                let parsed: Value = match resp.json().await {
                    Ok(v) => v,
                    Err(e) => {
                        last_err = Some(e.into());
                        continue;
                    }
                };
                let data = parsed.get("data").cloned().unwrap_or(parsed);
                let items = data
                    .get("items")
                    .or_else(|| data.get("list"))
                    .and_then(|v| v.as_array())
                    .cloned()
                    .unwrap_or_default();

                let results: Vec<SearchItem> = items
                    .into_iter()
                    .filter_map(|v| serde_json::from_value(v).ok())
                    .collect();
                return Ok(results);
            }
            Err(e) => {
                last_err = Some(e.into());
                continue;
            }
        }
    }

    Err(last_err.unwrap_or_else(|| anyhow!("all MovieBox hosts exhausted (search)")))
}

/// Full item details for `subject_id`, including the `dubs[]` array. Call
/// this once when a movie/show/anime detail screen opens, using the
/// **original** subject_id from search results. Each returned dub carries
/// its own `subject_id` to re-query for streams under that language.
pub async fn get_item_details(subject_id: &str) -> Result<ItemDetails> {
    with_deadline("get_item_details", async {
        ensure_token().await?;
        let path = format!("{}?subjectId={}", SUBJECT_GET_PATH, subject_id);
        let data = signed_request(&path, "GET").await?;
        serde_json::from_value(data).context("failed to parse ItemDetails")
    })
    .await
}

/// Direct playable stream files for one episode/movie under a specific
/// `subject_id` (which may be a dub's subject_id, not the original).
/// Mirrors `DownloadableVideoFilesDetail` in the reference lib.
///
/// IMPORTANT (verified against reference `core.py` + `models/downloadables.py`):
/// `RESOURCE_PATH` has NO server-side `se`/`ep` filter — those fields are
/// commented out in the reference's own `_create_params`, and the real
/// response is a flat, paginated `list[VideoFileMetadata]` covering ALL
/// episodes/resolutions for this `subject_id`, with each item individually
/// tagged with its own `se`/`ep`. So we must:
///   1. NOT send `se=`/`ep=` as request params (server ignores them anyway),
///   2. filter the returned `list` client-side for items matching the
///      requested `se`/`ep`,
///   3. follow `pager.hasMore` and fetch further pages until either we've
///      found matches on the current page with no more needed, or hasMore
///      is false — otherwise a long series (many episodes × resolutions)
///      can silently truncate past the first ~20-50 entries and an episode
///      that genuinely has a stream will wrongly report "no stream found".
/// We only ever stream directly — no on-disk download/caching of the
/// resource files is done here.
pub async fn get_streams(subject_id: &str, se: u32, ep: u32) -> Result<StreamResult> {
    ensure_token().await?;

    const PER_PAGE: u32 = 50;
    const MAX_PAGES: u32 = 10; // hard safety cap; 500 entries is already generous
    // Overall wall-clock budget for the whole (possibly multi-page, possibly
    // multi-host-per-page) lookup. Without this, a request can silently
    // chew through up to MAX_PAGES × HOST_POOL.len() × per-request timeout
    // (10 × 7 × 25s ≈ 29 minutes worst case) with the UI showing nothing
    // but a spinner the entire time. 45s is generous for a real lookup —
    // in practice a matching page is almost always found within 1-2 pages —
    // while still being short enough that the UI can show a clear error
    // instead of hanging indefinitely.
    const OVERALL_DEADLINE: std::time::Duration = std::time::Duration::from_secs(45);

    let mut matched: Vec<StreamFile> = Vec::new();
    let mut total_episode: Option<i64> = None;
    let mut page = 1u32;

    let result = tokio::time::timeout(OVERALL_DEADLINE, async move {
        loop {
            let path = format!(
                "{}?subjectId={}&resolution=best&page={}&perPage={}",
                RESOURCE_PATH, subject_id, page, PER_PAGE
            );
            debug!("[moviebox] get_streams: fetching page {page} for subject={subject_id} se={se} ep={ep}");
            let data = signed_request(&path, "GET").await?;

            if total_episode.is_none() {
                total_episode = data.get("totalEpisode").and_then(|v| v.as_i64());
            }

            // RESOURCE_PATH's raw shape is { list: [VideoFileMetadata...], pager: {...}, ... }
            // rather than the older web API's { streams: [...], hasResource }.
            let list = data
                .get("list")
                .and_then(|v| v.as_array())
                .cloned()
                .unwrap_or_default();
            let list_len = list.len();

            for item in list {
                let item_se = item.get("se").and_then(|v| v.as_u64()).unwrap_or(0) as u32;
                let item_ep = item.get("ep").and_then(|v| v.as_u64()).unwrap_or(0) as u32;

                // Movies (se/ep absent or 0) always match; series entries must
                // match the requested se/ep exactly.
                let is_movie_entry = item_se == 0 && item_ep == 0;
                if !is_movie_entry && (item_se != se || item_ep != ep) {
                    continue;
                }

                let Some(url) = item
                    .get("resourceLink")
                    .or_else(|| item.get("sourceUrl"))
                    .and_then(|v| v.as_str())
                else {
                    continue;
                };
                let resolution = item.get("resolution").and_then(|r| r.as_u64()).unwrap_or(0) as u32;
                matched.push(StreamFile {
                    format: "MP4".to_string(),
                    id: item.get("resourceId").and_then(|v| v.as_str()).unwrap_or("").to_string(),
                    url: url.to_string(),
                    resolutions: resolution,
                    size: item.get("size").map(|v| v.to_string()),
                    duration: item.get("duration").and_then(|v| v.as_i64()),
                    codec_name: item.get("codecName").and_then(|v| v.as_str()).map(str::to_string),
                });
            }

            debug!(
                "[moviebox] get_streams: page {page} had {list_len} entries, {} matched so far",
                matched.len()
            );

            let has_more = data
                .get("pager")
                .and_then(|p| p.get("hasMore"))
                .and_then(|v| v.as_bool())
                .unwrap_or(false);

            // Stop as soon as we have at least one match — we don't need
            // every resolution/source on every page, just enough to play.
            // Continuing to page through the whole catalog after we already
            // have a playable result only adds latency for no benefit.
            if !matched.is_empty() || !has_more || page >= MAX_PAGES {
                break;
            }
            page += 1;
        }
        // Move the accumulated state out through the Ok value rather than
        // relying on the caller reading the outer `matched`/`total_episode`/
        // `page` bindings after this future resolves — this block owns them
        // (async move) for its whole lifetime, so they must come back out
        // this way.
        Ok::<(Vec<StreamFile>, Option<i64>, u32), anyhow::Error>((matched, total_episode, page))
    })
    .await;

    let (matched, total_episode, page) = match result {
        Ok(Ok(state)) => state,
        Ok(Err(e)) => return Err(e),
        Err(_) => {
            warn!(
                "[moviebox] get_streams: timed out after {OVERALL_DEADLINE:?} (subject={subject_id} se={se} ep={ep})"
            );
            bail!("MovieBox lookup timed out — the source may be temporarily unavailable");
        }
    };

    let has_resource = !matched.is_empty();

    Ok(StreamResult {
        subject_id: subject_id.to_string(),
        se,
        ep,
        has_resource,
        sources: matched,
        hls: Vec::new(),
        dash: Vec::new(),
        free_episodes: total_episode,
        limited: false,
        note: if has_resource { None } else { Some("No stream found for this episode.".to_string()) },
    })
}

/// External caption/subtitle files for a specific resource. `resource_id`
/// should come from one of the `StreamFile.id` values returned by
/// `get_streams()`.
pub async fn get_captions(subject_id: &str, resource_id: &str) -> Result<CaptionResult> {
    with_deadline("get_captions", async {
        ensure_token().await?;
        let path = format!(
            "{}?subjectId={}&resourceId={}",
            EXT_CAPTIONS_PATH, subject_id, resource_id
        );
        let data = signed_request(&path, "GET").await?;
        serde_json::from_value(data).context("failed to parse CaptionResult")
    })
    .await
}
