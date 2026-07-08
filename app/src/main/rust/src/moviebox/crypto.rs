//! Request signing for MovieBox's `/wefeed-mobile-bff/*` hosts.
//!
//! Ported 1:1 from the `moviebox-api` (Python, v3) reference implementation's
//! `crypto.py`. Every mobile-bff endpoint (subject search, item details,
//! resource/stream fetch, captions) requires three headers on top of the
//! bearer token:
//!
//!   X-Client-Token : "<ts_ms>,<md5(reverse(ts_ms))>"
//!   x-tr-signature : "<ts_ms>|2|<base64(hmac_md5(canonical_string, secret))>"
//!   X-Client-Info  : JSON device fingerprint (see [`client_info_json`])
//!
//! Without these, api*.aoneroom.com / api.inmoviebox.com return 403.
//! This is NOT used for the older h5-api.aoneroom.com web endpoints (those
//! only need the bearer token from the `x-user` response header — see
//! `client.rs`'s `ensure_token()`).

use base64::{engine::general_purpose::STANDARD as B64, Engine as _};
use hmac::{Hmac, Mac};
use md5::{Digest, Md5};
use std::collections::BTreeMap;
use std::time::{SystemTime, UNIX_EPOCH};
use url::Url;

type HmacMd5 = Hmac<Md5>;

/// Base64-encoded HMAC secret. Decoded once at first use.
/// Matches `SECRET_KEY_DEFAULT` in the reference implementation.
const SECRET_KEY_DEFAULT_B64: &str = "76iRl07s0xSN9jqmEWAt79EBJZulIQIsV64FZr2O";

fn now_ms() -> u128 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .expect("system clock before epoch")
        .as_millis()
}

fn md5_hex(data: &[u8]) -> String {
    let mut hasher = Md5::new();
    hasher.update(data);
    let digest = hasher.finalize();
    digest.iter().map(|b| format!("{:02x}", b)).collect()
}

/// `"<ts>,<md5(reverse(<ts>))>"`
fn generate_x_client_token(timestamp_ms: u128) -> String {
    let ts = timestamp_ms.to_string();
    let reversed: String = ts.chars().rev().collect();
    let hash = md5_hex(reversed.as_bytes());
    format!("{},{}", ts, hash)
}

/// Rebuilds the query string with keys sorted lexicographically, values
/// left un-percent-encoded (matches the reference `_sorted_query_string`).
fn sorted_query_string(url: &Url) -> String {
    let mut map: BTreeMap<String, Vec<String>> = BTreeMap::new();
    for (k, v) in url.query_pairs() {
        map.entry(k.into_owned()).or_default().push(v.into_owned());
    }
    if map.is_empty() {
        return String::new();
    }
    let mut parts = Vec::new();
    for (k, values) in map {
        for v in values {
            parts.push(format!("{}={}", k, v));
        }
    }
    parts.join("&")
}

fn build_canonical_string(
    method: &str,
    accept: &str,
    content_type: &str,
    url: &Url,
    body: Option<&str>,
    timestamp_ms: u128,
) -> String {
    let path = url.path();
    let query = sorted_query_string(url);
    let canonical_url = if query.is_empty() {
        path.to_string()
    } else {
        format!("{}?{}", path, query)
    };

    let (body_hash, body_length) = match body {
        Some(b) => {
            let bytes = b.as_bytes();
            let truncated = &bytes[..bytes.len().min(102_400)];
            (md5_hex(truncated), bytes.len().to_string())
        }
        None => (String::new(), String::new()),
    };

    format!(
        "{}\n{}\n{}\n{}\n{}\n{}\n{}",
        method.to_uppercase(),
        accept,
        content_type,
        body_length,
        timestamp_ms,
        body_hash,
        canonical_url
    )
}

/// `"<ts>|2|<base64(hmac_md5(canonical, secret))>"`
fn generate_x_tr_signature(
    method: &str,
    accept: &str,
    content_type: &str,
    url: &Url,
    body: Option<&str>,
    timestamp_ms: u128,
) -> String {
    let canonical = build_canonical_string(method, accept, content_type, url, body, timestamp_ms);

    let secret_bytes = B64
        .decode(SECRET_KEY_DEFAULT_B64)
        .expect("static secret key must be valid base64");

    let mut mac =
        HmacMd5::new_from_slice(&secret_bytes).expect("HMAC accepts any key length");
    mac.update(canonical.as_bytes());
    let sig = mac.finalize().into_bytes();
    let sig_b64 = B64.encode(sig);

    format!("{}|2|{}", timestamp_ms, sig_b64)
}

/// Fixed device fingerprint used for `X-Client-Info`. A static-but-plausible
/// Android device profile is enough — the reference implementation
/// randomizes per-process, but a single stable identity is simpler to
/// reason about for a single-user mobile app and avoids regenerating a
/// device id every launch.
pub fn client_info_json(device_id: &str, gaid: &str) -> String {
    format!(
        r#"{{"package_name":"com.community.oneroom","version_name":"3.0.03.0529.03","version_code":50020045,"os":"android","os_version":"13","install_ch":"ps","device_id":"{device_id}","install_store":"ps","gaid":"{gaid}","brand":"Redmi","model":"23078RKD5C","system_language":"en","net":"NETWORK_WIFI","region":"US","timezone":"Asia/Dhaka","sp_code":"40401","X-Play-Mode":"2"}}"#
    )
}

pub const USER_AGENT: &str =
    "com.community.oneroom/50020045 (Linux; U; Android 13; en_US; 23078RKD5C; Build/TQ2A.230405.003; Cronet/135.0.7012.3)";

/// Full set of signed headers for one request to a `/wefeed-mobile-bff/*`
/// endpoint. `url` must be the complete URL including query string — the
/// signature covers path + sorted query.
pub struct SignedHeaders {
    pub x_client_token: String,
    pub x_tr_signature: String,
    pub x_client_info: String,
    pub user_agent: &'static str,
}

pub fn build_signed_headers(
    method: &str,
    url: &Url,
    accept: &str,
    content_type: &str,
    body: Option<&str>,
    device_id: &str,
    gaid: &str,
) -> SignedHeaders {
    let ts = now_ms();
    SignedHeaders {
        x_client_token: generate_x_client_token(ts),
        x_tr_signature: generate_x_tr_signature(method, accept, content_type, url, body, ts),
        x_client_info: client_info_json(device_id, gaid),
        user_agent: USER_AGENT,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn client_token_format_has_two_parts() {
        let tok = generate_x_client_token(1_700_000_000_000);
        let parts: Vec<&str> = tok.split(',').collect();
        assert_eq!(parts.len(), 2);
        assert_eq!(parts[0], "1700000000000");
        assert_eq!(parts[1].len(), 32); // md5 hex digest length
    }

    #[test]
    fn sorted_query_string_orders_keys() {
        let url = Url::parse("https://x.test/p?zeta=1&alpha=2&alpha=3").unwrap();
        assert_eq!(sorted_query_string(&url), "alpha=2&alpha=3&zeta=1");
    }

    #[test]
    fn signature_has_three_pipe_delimited_parts() {
        let url = Url::parse("https://api6.aoneroom.com/wefeed-mobile-bff/subject-api/get?subjectId=123").unwrap();
        let sig = generate_x_tr_signature("GET", "application/json", "application/json", &url, None, 1_700_000_000_000);
        let parts: Vec<&str> = sig.splitn(3, '|').collect();
        assert_eq!(parts.len(), 3);
        assert_eq!(parts[0], "1700000000000");
        assert_eq!(parts[1], "2");
        assert!(!parts[2].is_empty());
    }
}
