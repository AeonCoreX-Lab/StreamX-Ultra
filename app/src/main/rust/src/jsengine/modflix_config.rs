// app/src/main/rust/src/jsengine/modflix_config.rs
//
// Rust port of ModflixConfig.kt — fetches live provider base URLs from the
// streamx-addons repo, with the same hardcoded FALLBACK map. Used to back
// providerContext.getBaseUrl(key) inside the QuickJS engine.
//
// NOTE: this duplicates ModflixConfig.kt's FALLBACK map. If you update one,
// update the other (or, as a follow-up, delete ModflixConfig.kt entirely and
// have Kotlin call into this Rust module via JNI too — out of scope here).

use std::collections::HashMap;
use std::sync::RwLock;
use std::time::{Duration, Instant};
use once_cell::sync::Lazy;

const CONFIG_URL: &str = "https://aeoncorex-lab.github.io/streamx-addons/modflix.json";
const CACHE_TTL: Duration = Duration::from_secs(3600);

static FALLBACK: Lazy<HashMap<&'static str, &'static str>> = Lazy::new(|| {
    HashMap::from([
        ("autoEmbed",    "https://autoembed.cc"),
        ("aed",          "https://watch-drama.autoembed.cc"),
        ("aea",          "https://watch-anime.autoembed.cc"),
        ("rive",         "https://www.rivestream.app"),
        ("consumet",     "https://consumet.zendax.tech"),
        ("hdhub4u",      "https://hdhub4u.foo"),
        ("kissKh",       "https://kisskh.do"),
        ("hdhub",        "https://new4.hdhub4u.fo"),
        ("kat",          "https://katmoviehd.pictures"),
        ("Vega",         "https://vegamovies.vodka"),
        ("filmyfly",     "https://new2.filmyfiy.org"),
        ("showbox",      "https://www.showbox.media"),
        ("movieBox",     "https://api6.aoneroom.com"),
        ("Topmovies",    "https://moviesleech.link"),
        ("multi",        "https://multimovies.autos"),
        ("filepress",    "https://new14.filepress.store"),
        ("dc",           "https://dramacool.org.ro"),
        ("4khdhub",      "https://4khdhub.dad"),
        ("movies4u",     "https://movies4u.vg"),
        ("skymovieshd",  "https://skymovieshd.fast"),
        ("lux",          "https://rogmovies.blog"),
        ("vadapav",      "https://vadapav.mov"),
        ("nfMirror",     "https://net22.cc"),
        ("primewire",    "https://primewire.si"),
        ("embedsu",      "https://moviemaze.cc"),
        ("guardahd",     "https://mostraguarda.stream"),
        ("protonMovies", "https://www.protonmovies.net"),
        ("Moviesmod",    "https://moviesmod.day"),
        ("1cinevood",    "https://www.1cinevood.net"),
        ("cinemaLuxe",   "https://cinemaluxe.net"),
        ("Joya9tv",      "https://joya9tv.com"),
        ("zeefliz",      "https://zeefliz.vip"),
        ("dooflix",      "https://dooflix.stream"),
        ("ogomovies",    "https://www.ogomovies.io"),
        ("kmMovies",     "https://kmmovies.org"),
        ("moviezwap",    "https://moviezwap.org"),
        ("katfix",       "https://katmoviesfix.net"),
        ("moviesapi",    "https://moviesapi.club"),
        ("UhdMovies",    "https://uhdmovies.pink"),
        ("Ringz",        "https://privatereporz.pages.dev"),
        ("w4u",          "https://world4ufree.tw"),
    ])
});

struct Cache {
    json: Option<serde_json::Value>,
    fetched_at: Option<Instant>,
}

static CACHE: Lazy<RwLock<Cache>> = Lazy::new(|| RwLock::new(Cache { json: None, fetched_at: None }));

/// Blocking lookup — safe to call from inside a QuickJS native function
/// (reqwest::blocking spawns its own internal runtime thread).
pub fn get_base_url(key: &str) -> String {
    refresh_if_stale();

    let cache = CACHE.read().unwrap();
    if let Some(json) = &cache.json {
        if let Some(obj) = json.get(key) {
            if let Some(url) = obj.get("url").and_then(|u| u.as_str()) {
                if !url.is_empty() { return url.to_string(); }
            }
            if let Some(flat) = obj.as_str() {
                if !flat.is_empty() { return flat.to_string(); }
            }
        }
    }
    drop(cache);

    FALLBACK.get(key).map(|s| s.to_string()).unwrap_or_else(|| {
        log::warn!("[ModflixConfig] no URL for provider key: {key}");
        String::new()
    })
}

fn refresh_if_stale() {
    let stale = {
        let cache = CACHE.read().unwrap();
        cache.json.is_none() || cache.fetched_at.map_or(true, |t| t.elapsed() > CACHE_TTL)
    };
    if !stale { return; }

    let client = match reqwest::blocking::Client::builder()
        .timeout(Duration::from_secs(10))
        .build() {
        Ok(c) => c,
        Err(_) => return,
    };

    let resp = client.get(CONFIG_URL)
        .header("User-Agent", "StreamX-Ultra/2.0")
        .header("Cache-Control", "no-cache")
        .send();

    match resp {
        Ok(r) if r.status().is_success() => {
            match r.json::<serde_json::Value>() {
                Ok(json) => {
                    let mut cache = CACHE.write().unwrap();
                    cache.json = Some(json);
                    cache.fetched_at = Some(Instant::now());
                    log::debug!("[ModflixConfig] refreshed from {CONFIG_URL}");
                }
                Err(e) => log::warn!("[ModflixConfig] parse error: {e}"),
            }
        }
        Ok(r) => log::warn!("[ModflixConfig] GitHub returned {}, using fallback", r.status()),
        Err(e) => log::warn!("[ModflixConfig] fetch failed (using fallback): {e}"),
    }
}
