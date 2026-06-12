// app/src/main/rust/src/jsengine/http.rs
//
// Native HTTP backend for providerContext.axios / fetch() inside QuickJS.
// Called synchronously from a QuickJS native function (__native_http).
// Uses reqwest::blocking — spawns its own internal tokio runtime, safe to
// call from any thread including ones already inside another runtime.

use std::collections::HashMap;
use std::time::Duration;
use serde::{Deserialize, Serialize};

const DESKTOP_UA: &str = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) \
    AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 Edg/131.0.0.0";

#[derive(Deserialize)]
pub struct HttpRequest {
    pub url:    String,
    #[serde(default = "default_method")]
    pub method: String,
    #[serde(default)]
    pub headers: HashMap<String, String>,
    #[serde(default)]
    pub body: Option<String>,
}
fn default_method() -> String { "GET".to_string() }

#[derive(Serialize, Default)]
pub struct HttpResponse {
    pub status:  i32,
    pub body:    String,
    pub headers: HashMap<String, String>,
    #[serde(rename = "finalUrl")]
    pub final_url: String,
}

/// Entry point bound to QuickJS as `__native_http(jsonRequest) -> jsonResponse`.
///
/// Request JSON:  { "url": "...", "method": "GET|POST|HEAD", "headers": {...}, "body": "..." }
/// Response JSON: { "status": 200, "body": "...", "headers": {...lowercased...}, "finalUrl": "..." }
///
/// `headers` always includes "x-final-url" (== finalUrl) and, for redirects,
/// "location" — mirroring the Kotlin HttpClient.fetchRaw() contract so the
/// JS-side fetch/axios wrappers stay identical to the (now-retired) Rhino path.
pub fn native_http(req_json: String) -> String {
    let req: HttpRequest = match serde_json::from_str(&req_json) {
        Ok(r) => r,
        Err(e) => {
            log::warn!("[jsengine/http] bad request json: {e}");
            return serde_json::to_string(&HttpResponse::default()).unwrap_or_default();
        }
    };

    let method = req.method.to_uppercase();
    let follow_redirects = method != "HEAD";

    let client = match reqwest::blocking::Client::builder()
        .timeout(Duration::from_secs(25))
        .redirect(if follow_redirects {
            reqwest::redirect::Policy::limited(10)
        } else {
            reqwest::redirect::Policy::none()
        })
        .build()
    {
        Ok(c) => c,
        Err(e) => {
            log::warn!("[jsengine/http] client build error: {e}");
            return serde_json::to_string(&HttpResponse::default()).unwrap_or_default();
        }
    };

    let mut rb = match method.as_str() {
        "POST" => client.post(&req.url),
        "HEAD" => client.head(&req.url),
        _      => client.get(&req.url),
    };

    // Base headers (mirrors HttpClient.BASE_HEADERS), then caller overrides
    rb = rb
        .header("User-Agent", DESKTOP_UA)
        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .header("Accept-Language", "en-US,en;q=0.9")
        .header("Cache-Control", "no-store");

    for (k, v) in &req.headers {
        rb = rb.header(k.as_str(), v.as_str());
    }

    if method == "POST" {
        let content_type = req.headers.get("Content-Type")
            .or_else(|| req.headers.get("content-type"))
            .cloned()
            .unwrap_or_else(|| "application/x-www-form-urlencoded".to_string());
        rb = rb.header("Content-Type", content_type)
               .body(req.body.clone().unwrap_or_default());
    }

    let resp = match rb.send() {
        Ok(r) => r,
        Err(e) => {
            log::warn!("[jsengine/http] {} {} failed: {}", method, req.url, e);
            return serde_json::to_string(&HttpResponse::default()).unwrap_or_default();
        }
    };

    let status    = resp.status().as_u16() as i32;
    let final_url = resp.url().to_string();

    let mut headers: HashMap<String, String> = HashMap::new();
    for (k, v) in resp.headers().iter() {
        if let Ok(val) = v.to_str() {
            headers.insert(k.as_str().to_lowercase(), val.to_string());
        }
    }
    headers.insert("x-final-url".to_string(), final_url.clone());

    let body = if method == "HEAD" {
        String::new()
    } else {
        resp.text().unwrap_or_default()
    };

    log::debug!("[jsengine/http] {} {} -> {} ({} bytes)", method, req.url, status, body.len());

    serde_json::to_string(&HttpResponse { status, body, headers, final_url })
        .unwrap_or_default()
}
