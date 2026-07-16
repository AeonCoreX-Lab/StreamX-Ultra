// app/src/main/rust/src/lib.rs
// ═══════════════════════════════════════════════════════════════════════
//  StreamX Rust Core — JNI Bridge
//
//  All JNI function names are IDENTICAL to the old C++ native-lib.cpp
//  torrent functions. TorrentEngine.kt and StreamXCore don't change.
//
//  Functions provided:
//    ① (removed) TMDB key — now served by the metadata-cache Worker instead
//    ② Torrent JNI  → replaces C++ TorrentSystem / torrent-engine.cpp
//    ③ Addon HTTP transport → nativeAddonFetchStreams
//    ④ JS provider engine (QuickJS) → nativeExecuteJsStream (NEW)
// ═══════════════════════════════════════════════════════════════════════

#![allow(non_snake_case, clippy::missing_safety_doc)]

mod torrent;
mod jsengine;
mod indexer;
mod moviebox;

use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jstring, jdouble, jlongArray, jboolean};
use log::info;
use torrent::engine::TorrentEngineHandle;

// ── One-time init ─────────────────────────────────────────────────────────────
// Called from TorrentEngine.init { initNative() } in Kotlin
#[no_mangle]
pub extern "system" fn Java_com_aeoncorex_streamx_ui_movie_TorrentEngine_initNative(
    _env: JNIEnv,
    _obj: JClass,
) {
    #[cfg(target_os = "android")]
    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Debug),
    );

    // Touch the singleton to start the Tokio runtime early
    let _ = TorrentEngineHandle::get();
    info!("StreamX Rust core initialised");
}

// Sets the on-disk cache directory the indexer's remote-config loader
// uses to persist indexer-config.json between launches. Kotlin should
// call this once, right after initNative(), passing
// context.cacheDir.absolutePath — see IndexerNative.kt's init block.
// Safe to skip: if never called, the loader falls back to the system
// temp dir, which still works but won't survive an app restart as
// reliably as the real cache dir.
#[no_mangle]
pub extern "system" fn Java_com_aeoncorex_streamx_streaming_IndexerNative_nativeSetCacheDir(
    mut env:  JNIEnv,
    _cls:     JClass,
    j_path:   JString,
) {
    let path = jstr(&mut env, j_path);
    info!("Indexer cache dir set: {path}");
    indexer::engine::init_cache_dir(std::path::PathBuf::from(path));
}

// ── Proxy support (HTTP / SOCKS4 / SOCKS5) ────────────────────────────────────
//
// Design mirrors Prowlarr's IndexerProxies (see indexer/proxy/config.rs
// doc comment for the full rationale — Prowlarr's FlareSolverr proxy
// type is intentionally NOT ported since it needs an external
// browser-automation server that can't run on Android).
//
// Called from IndexerNative.kt's setProxy()/clearProxy() — the actual
// host/port/username/password come from the user's own Settings entry,
// read out of EncryptedSharedPreferences on the Kotlin side and passed
// here only as plain JNI arguments for this one call (never persisted
// by Rust, never sent anywhere but directly into the in-memory
// reqwest::Client — see indexer/proxy/mod.rs).
//
// kind: "http" | "socks4" | "socks5" (matches ProxyKind's serde rename)
// Returns true on success, false if the proxy config was invalid (bad
// host/port) — the previously active client (proxied or plain) remains
// in effect either way, so a typo in Settings never breaks search
// entirely.
#[no_mangle]
pub extern "system" fn Java_com_aeoncorex_streamx_streaming_IndexerNative_nativeSetProxy(
    mut env:     JNIEnv,
    _cls:        JClass,
    j_kind:      JString,
    j_host:      JString,
    port:        jni::sys::jint,
    j_username:  JString, // pass "" if no auth
    j_password:  JString, // pass "" if no auth
) -> jboolean {
    let kind_str = jstr(&mut env, j_kind);
    let host     = jstr(&mut env, j_host);
    let username = jstr(&mut env, j_username);
    let password = jstr(&mut env, j_password);

    let kind = match kind_str.as_str() {
        "http"   => indexer::proxy::config::ProxyKind::Http,
        "socks4" => indexer::proxy::config::ProxyKind::Socks4,
        "socks5" => indexer::proxy::config::ProxyKind::Socks5,
        other => {
            log::warn!("[proxy] unknown proxy kind '{other}', ignoring");
            return 0;
        }
    };

    let config = indexer::proxy::config::ProxyConfig {
        kind,
        host,
        port: port as u16,
        username: if username.is_empty() { None } else { Some(username) },
        password: if password.is_empty() { None } else { Some(password) },
        enabled: true,
    };

    match indexer::proxy::set_proxy(config) {
        Ok(()) => 1,
        Err(e) => {
            log::warn!("[proxy] failed to activate: {e}");
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_aeoncorex_streamx_streaming_IndexerNative_nativeClearProxy(
    _env: JNIEnv,
    _cls: JClass,
) {
    let _ = indexer::proxy::clear_proxy();
}

#[no_mangle]
pub extern "system" fn Java_com_aeoncorex_streamx_streaming_IndexerNative_nativeProxyStatus(
    mut env: JNIEnv,
    _cls: JClass,
) -> jni::sys::jstring {
    let summary = indexer::proxy::status_summary();
    env.new_string(summary).expect("JNI string").into_raw()
}

// ── ① TMDB key ───────────────────────────────────────────────────────────────
// REMOVED (metadata-cache Worker migration): the app no longer calls TMDB
// directly, so it no longer needs a local key vault. TMDB requests now go
// through the streamx-metadata-cache Cloudflare Worker, which holds the
// real TMDB key as a server-side secret (never compiled into the APK).
// This is also a security improvement — the old approach embedded the key
// in this .so at compile time, which is recoverable via reverse engineering
// (strings/objdump on the .so); a server-side-only key isn't.
//
// StreamXCore.getTmdbKey() has been removed from the Kotlin side (see
// MoviePlayerScreen.kt) — if you're looking for the old function, this is
// where it used to live. Kept as a comment intentionally, not deleted
// outright, so future readers of `git blame`/this file understand why a
// second key delivery mechanism doesn't exist here anymore.

// ── ② Torrent JNI — same names as C++ native-lib.cpp ────────────────────────

#[no_mangle]
pub extern "system" fn Java_com_aeoncorex_streamx_ui_movie_TorrentEngine_startNative(
    // JNIEnv marked as mut here so we can pass it as &mut env below
    mut env:  JNIEnv,
    _obj:     JClass,
    j_magnet: JString,
    j_path:   JString,
) {
    let magnet   = jstr(&mut env, j_magnet);
    let save_dir = jstr(&mut env, j_path);
    TorrentEngineHandle::get().start(&magnet, &save_dir);
}

#[no_mangle]
pub extern "system" fn Java_com_aeoncorex_streamx_ui_movie_TorrentEngine_stopNative(
    _env: JNIEnv,
    _obj: JClass,
) {
    TorrentEngineHandle::get().stop();
}

// Returns jlongArray[5] = [progress, speed, seeds, peers, state]
// Same format as old C++ getStatusNative(), PLUS progress_bytes appended
// at index 5 (was 5 elements, now 6) — TorrentEngine.kt's getStatusNative
// binding and array indices must be updated to match.
//
// FIX (network-slow black screen / stuck "Detecting…"): the actual root
// cause of both bugs was never really the decode-check timeouts in
// mpv_handler.cpp — those are downstream symptoms. The real problem is
// that MPV was being told to open and start decoding a stream URL before
// enough of the file had actually been downloaded to decode ANYTHING
// meaningful yet. mpv_handler.cpp's negotiation-timeout logic (raised to
// ~12s in an earlier fix) treats "no video-params yet" as evidence of a
// broken/stuck HW decoder and forces an SW fallback — but on a slow
// connection, "no video-params yet" can just as easily mean "genuinely
// no usable data has arrived yet," and no decode-check timeout, however
// generous, can fix a data problem by waiting longer at the DECODE
// layer. progress_bytes lets the Kotlin side gate playback start on
// actual buffered data instead, so mpv is never asked to decode a stream
// that hasn't buffered enough yet — see MoviePlayerScreen.kt's
// MIN_BUFFER_BYTES_BEFORE_PLAY for how this is used.
#[no_mangle]
pub extern "system" fn Java_com_aeoncorex_streamx_ui_movie_TorrentEngine_getStatusNative(
    env:  JNIEnv,
    _obj: JClass,
) -> jlongArray {
    let s    = TorrentEngineHandle::get().status();
    let arr  = env.new_long_array(6).expect("jlongArray");
    let data = [
        s.progress  as i64,
        s.speed_bps,
        s.seeds     as i64,
        s.peers     as i64,
        s.state     as i64,
        s.progress_bytes as i64,
    ];
    env.set_long_array_region(&arr, 0, &data).expect("set");
    arr.into_raw()
}

// Returns video file path string — TorrentEngine.getFilePath() unchanged
#[no_mangle]
pub extern "system" fn Java_com_aeoncorex_streamx_ui_movie_TorrentEngine_getFilePathNative(
    env:  JNIEnv,
    _obj: JClass,
) -> jstring {
    let path = TorrentEngineHandle::get().status().video_path;
    env.new_string(path).expect("JNI string").into_raw()
}

// Diagnostics export (Tier 3 #16) — plain torrent-session state dump for a
// user-initiated "Copy Diagnostics" button. Works in release builds too
// (unlike the HTTP /debug route, which is debug_assertions-gated) since
// this has no network/HTTP surface at all — just a direct method call.
#[no_mangle]
pub extern "system" fn Java_com_aeoncorex_streamx_ui_movie_TorrentEngine_getDebugDumpNative(
    env:  JNIEnv,
    _obj: JClass,
) -> jstring {
    let dump = TorrentEngineHandle::get().debug_dump();
    env.new_string(dump).expect("JNI string").into_raw()
}

// Focused error-message export for the ERROR-state UI fix — see
// MoviePlayerScreen.kt's State.ERROR handler.
#[no_mangle]
pub extern "system" fn Java_com_aeoncorex_streamx_ui_movie_TorrentEngine_getLastErrorNative(
    env:  JNIEnv,
    _obj: JClass,
) -> jstring {
    let msg = TorrentEngineHandle::get().last_error();
    env.new_string(msg).expect("JNI string").into_raw()
}

// Called from Kotlin MPV time-pos observer — NEW method, add to TorrentEngine.kt
#[no_mangle]
pub extern "system" fn Java_com_aeoncorex_streamx_ui_movie_TorrentEngine_setPlayheadNative(
    _env: JNIEnv,
    _obj: JClass,
    secs: jdouble,
) {
    TorrentEngineHandle::get().set_playhead(secs);
}

// Returns "http://127.0.0.1:8088/stream" — same URL as TorrentStreamServer
#[no_mangle]
pub extern "system" fn Java_com_aeoncorex_streamx_ui_movie_TorrentEngine_getLocalUrlNative(
    env:  JNIEnv,
    _obj: JClass,
) -> jstring {
    let url = TorrentEngineHandle::get().local_url();
    env.new_string(url).expect("JNI string").into_raw()
}

// Clear download cache directory. Returns true if the directory was
// actually removed (or already absent) — false if removal failed, so
// Kotlin can log/react instead of silently assuming the space was freed.
#[no_mangle]
pub extern "system" fn Java_com_aeoncorex_streamx_ui_movie_TorrentEngine_clearCacheNative(
    mut env: JNIEnv,
    _obj:  JClass,
    j_dir: JString,
) -> jboolean {
    let dir = jstr(&mut env, j_dir);
    let ok = torrent::engine::TorrentEngine::clear_cache(&dir);
    ok as jboolean
}

// ── ③ Addon HTTP transport ───────────────────────────────────────────────────
// Called from JsStreamProviderEngine when querying HTTP (Stremio) addons.
// Returns JSON string: [{"url":"...","name":"...","description":"..."}, ...]
#[no_mangle]
pub extern "system" fn Java_com_aeoncorex_streamx_streaming_StreamXNative_nativeAddonFetchStreams(
    mut env:         JNIEnv,
    _cls:            JClass,
    j_transport_url: JString,
    j_type:          JString,
    j_id:            JString,
) -> jstring {
    let transport_url = jstr(&mut env, j_transport_url);
    let content_type  = jstr(&mut env, j_type);
    let id            = jstr(&mut env, j_id);

    let json = TorrentEngineHandle::get().rt.block_on(async {
        fetch_addon_streams(&transport_url, &content_type, &id).await
    });

    env.new_string(json).expect("JNI string").into_raw()
}

async fn fetch_addon_streams(transport_url: &str, content_type: &str, id: &str) -> String {
    let base = transport_url
        .trim_end_matches("manifest.json")
        .trim_end_matches('/');
    let id_enc = percent_encode(id);
    let url    = format!("{}/stream/{}/{}.json", base, content_type, id_enc);

    let client = reqwest::Client::builder()
        .timeout(std::time::Duration::from_secs(15))
        .build()
        .unwrap_or_default();

    let text = match client.get(&url).send().await {
        Ok(r)  => r.text().await.unwrap_or_default(),
        Err(e) => { log::warn!("addon fetch error: {}", e); return "[]".to_string(); }
    };

    // Parse { streams: [...] } → return array as JSON string
    match serde_json::from_str::<serde_json::Value>(&text) {
        Ok(v) => {
            let streams = v.get("streams")
                .and_then(|s| s.as_array())
                .map(|arr| serde_json::to_string(arr).unwrap_or_default())
                .unwrap_or_else(|| "[]".to_string());
            streams
        }
        Err(_) => "[]".to_string(),
    }
}

fn percent_encode(s: &str) -> String {
    url::form_urlencoded::byte_serialize(s.as_bytes()).collect()
}

// ── ⑤ Indexer — Jackett-style multi-site torrent search ──────────────────────
//
// Called from IndexerNative.kt (new, mirrors StreamXNative.kt's pattern).
// Searches 1337x, TorrentGalaxy, KickassTorrents, TorrentDownload in parallel
// for dubbed/dual-audio releases and returns them as a JSON array.
//
// IMPORTANT: this function ONLY searches and returns magnet URIs — it does
// NOT start playback. Kotlin takes the chosen result's `magnet` field and
// passes it to the EXISTING TorrentEngine.startNative(magnet, saveDir) call,
// exactly like it already does for YTS results. No torrent/session.rs or
// http_server.rs changes were needed for this to work.
//
// Reuses TorrentEngineHandle's tokio runtime (same as nativeAddonFetchStreams)
// rather than spinning up a second one.
#[no_mangle]
pub extern "system" fn Java_com_aeoncorex_streamx_streaming_IndexerNative_nativeSearchDubbed(
    mut env:     JNIEnv,
    _cls:        JClass,
    j_query:     JString,
    j_imdb_id:   JString, // pass empty string "" if unavailable
) -> jstring {
    let query   = jstr(&mut env, j_query);
    let imdb_id = jstr(&mut env, j_imdb_id);
    let imdb_opt: Option<&str> = if imdb_id.is_empty() { None } else { Some(&imdb_id) };

    let json = TorrentEngineHandle::get().rt.block_on(async {
        indexer::engine::search_dubbed_json(&query, imdb_opt).await
    });

    env.new_string(json).expect("JNI string").into_raw()
}

// Plain keyword search, no dub filtering — used for the English/original
// language path in TorrentRepository.kt (replaces the old broken
// fetch1337x(englishQuery) call).
#[no_mangle]
pub extern "system" fn Java_com_aeoncorex_streamx_streaming_IndexerNative_nativeSearchAll(
    mut env:  JNIEnv,
    _cls:     JClass,
    j_query:  JString,
) -> jstring {
    let query = jstr(&mut env, j_query);

    let json = TorrentEngineHandle::get().rt.block_on(async {
        indexer::engine::search_all_json(&query).await
    });

    env.new_string(json).expect("JNI string").into_raw()
}

// ── Drama (K-drama / C-drama / Turkish drama) ─────────────────────────────────
// Returns BOTH original-voice and dubbed releases together; Kotlin filters
// by result.audioTags client-side for "Original" vs "English Dub" chips —
// same pattern as searchDubbed()/searchAll() above.
#[no_mangle]
pub extern "system" fn Java_com_aeoncorex_streamx_streaming_IndexerNative_nativeSearchDrama(
    mut env:  JNIEnv,
    _cls:     JClass,
    j_query:  JString,
) -> jstring {
    let query = jstr(&mut env, j_query);

    let json = TorrentEngineHandle::get().rt.block_on(async {
        indexer::engine::search_drama_json(&query).await
    });

    env.new_string(json).expect("JNI string").into_raw()
}

// ── Anime — English dub/sub ────────────────────────────────────────────────
#[no_mangle]
pub extern "system" fn Java_com_aeoncorex_streamx_streaming_IndexerNative_nativeSearchAnimeEnglish(
    mut env:  JNIEnv,
    _cls:     JClass,
    j_query:  JString,
) -> jstring {
    let query = jstr(&mut env, j_query);

    let json = TorrentEngineHandle::get().rt.block_on(async {
        indexer::engine::search_anime_english_json(&query).await
    });

    env.new_string(json).expect("JNI string").into_raw()
}

// ── Anime — non-English dub/sub (Nyaa's "Non-English-translated" cat) ────────
#[no_mangle]
pub extern "system" fn Java_com_aeoncorex_streamx_streaming_IndexerNative_nativeSearchAnimeOtherDub(
    mut env:  JNIEnv,
    _cls:     JClass,
    j_query:  JString,
) -> jstring {
    let query = jstr(&mut env, j_query);

    let json = TorrentEngineHandle::get().rt.block_on(async {
        indexer::engine::search_anime_other_dub_json(&query).await
    });

    env.new_string(json).expect("JNI string").into_raw()
}

// ── ⑥ MovieBox — direct-stream provider (search / dubs / resolve) ────────────
//
// Separate from the indexer (⑤) above: indexer returns magnet URIs for
// TorrentEngine to download; MovieBox returns ready-to-play HTTP(S) MP4/HLS
// URLs directly — no torrent session involved. Called from MovieBoxNative.kt.
//
// Dub handling: nativeGetItemDetails() returns a `dubs[]` array where each
// entry has ITS OWN subject_id (confirmed against real MovieBox responses —
// dubs are not a query-param switch on one subject_id, they are separate
// subjects). Kotlin re-calls nativeGetStreams() with whichever subject_id
// the user picked from that list.
use moviebox::client as moviebox_client;

#[no_mangle]
pub extern "system" fn Java_com_aeoncorex_streamx_streaming_MovieBoxNative_nativeSearch(
    mut env: JNIEnv,
    _cls: JClass,
    j_query: JString,
    page: jni::sys::jint,
) -> jstring {
    let query = jstr(&mut env, j_query);

    let json = TorrentEngineHandle::get().rt.block_on(async {
        match moviebox_client::search(&query, page.max(1) as u32).await {
            Ok(items) => serde_json::to_string(&items).unwrap_or_else(|_| "[]".to_string()),
            Err(e) => format!(
                "{{\"error\":{}}}",
                serde_json::to_string(&e.to_string()).unwrap_or_else(|_| "\"unknown error\"".into())
            ),
        }
    });

    env.new_string(json).expect("JNI string").into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_aeoncorex_streamx_streaming_MovieBoxNative_nativeGetItemDetails(
    mut env: JNIEnv,
    _cls: JClass,
    j_subject_id: JString,
) -> jstring {
    let subject_id = jstr(&mut env, j_subject_id);

    let json = TorrentEngineHandle::get().rt.block_on(async {
        match moviebox_client::get_item_details(&subject_id).await {
            Ok(details) => serde_json::to_string(&details).unwrap_or_else(|_| "{}".to_string()),
            Err(e) => format!(
                "{{\"error\":{}}}",
                serde_json::to_string(&e.to_string()).unwrap_or_else(|_| "\"unknown error\"".into())
            ),
        }
    });

    env.new_string(json).expect("JNI string").into_raw()
}

/// java: MovieBoxNative.nativeGetSeasonInfo(subjectId: String): String (JSON SeasonInfo)
///
/// Returns MovieBox's OWN season/episode-count list for `subjectId` —
/// authoritative for what episodes actually exist under that subject,
/// which matters especially after a dub switch (a dub's subject_id can
/// have a different available episode count than the original). Use this
/// instead of assuming TMDB's episode count applies to every dub.
#[no_mangle]
pub extern "system" fn Java_com_aeoncorex_streamx_streaming_MovieBoxNative_nativeGetSeasonInfo(
    mut env: JNIEnv,
    _cls: JClass,
    j_subject_id: JString,
) -> jstring {
    let subject_id = jstr(&mut env, j_subject_id);

    let json = TorrentEngineHandle::get().rt.block_on(async {
        match moviebox_client::get_season_info(&subject_id).await {
            Ok(info) => serde_json::to_string(&info).unwrap_or_else(|_| "{}".to_string()),
            Err(e) => format!(
                "{{\"error\":{}}}",
                serde_json::to_string(&e.to_string()).unwrap_or_else(|_| "\"unknown error\"".into())
            ),
        }
    });

    env.new_string(json).expect("JNI string").into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_aeoncorex_streamx_streaming_MovieBoxNative_nativeGetStreams(
    mut env: JNIEnv,
    _cls: JClass,
    j_subject_id: JString,
    se: jni::sys::jint,
    ep: jni::sys::jint,
) -> jstring {
    let subject_id = jstr(&mut env, j_subject_id);
    let se_u = se.max(1) as u32;
    let ep_u = ep.max(1) as u32;

    let json = TorrentEngineHandle::get().rt.block_on(async {
        match moviebox_client::get_streams(&subject_id, se_u, ep_u).await {
            Ok(r) => serde_json::to_string(&r).unwrap_or_else(|_| "{}".to_string()),
            Err(e) => format!(
                "{{\"error\":{}}}",
                serde_json::to_string(&e.to_string()).unwrap_or_else(|_| "\"unknown error\"".into())
            ),
        }
    });

    env.new_string(json).expect("JNI string").into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_aeoncorex_streamx_streaming_MovieBoxNative_nativeGetCaptions(
    mut env: JNIEnv,
    _cls: JClass,
    j_subject_id: JString,
    j_resource_id: JString,
) -> jstring {
    let subject_id = jstr(&mut env, j_subject_id);
    let resource_id = jstr(&mut env, j_resource_id);

    let json = TorrentEngineHandle::get().rt.block_on(async {
        match moviebox_client::get_captions(&subject_id, &resource_id).await {
            Ok(c) => serde_json::to_string(&c).unwrap_or_else(|_| "{}".to_string()),
            Err(e) => format!(
                "{{\"error\":{}}}",
                serde_json::to_string(&e.to_string()).unwrap_or_else(|_| "\"unknown error\"".into())
            ),
        }
    });

    env.new_string(json).expect("JNI string").into_raw()
}


// Fixed back to `&mut JNIEnv` since `get_string` requires a mutable reference.
fn jstr(env: &mut JNIEnv, s: JString) -> String {
    env.get_string(&s).map(|js| js.into()).unwrap_or_default()
}

// ── ④ JS Provider Engine (QuickJS) ──────────────────────────────────────────
// Called from JsStreamProviderEngine (via StreamXNative.executeJsStream) to
// execute a Vega-style CJS stream.js bundle and return resolved streams.
//
// Replaces the entire Rhino-based JsEngine.kt / JsProviderContext.kt pipeline.
// See src/jsengine/mod.rs for the full QuickJS implementation.
//
// Runs on its own thread (not TorrentEngineHandle's tokio runtime) because:
//   • run_provider_stream() is fully synchronous (reqwest::blocking handles
//     its own internal runtime — no need to be inside a tokio context)
//   • rquickjs::Runtime/Context are !Send — must be created and dropped on
//     the same OS thread, which a fresh spawned thread guarantees cleanly
#[no_mangle]
pub extern "system" fn Java_com_aeoncorex_streamx_streaming_StreamXNative_nativeExecuteJsStream(
    mut env:    JNIEnv,
    _cls:       JClass,
    j_code:     JString,
    j_link:     JString,
    is_series:  jboolean,
) -> jstring {
    let code = jstr(&mut env, j_code);
    let link = jstr(&mut env, j_link);

    let json = std::thread::Builder::new()
        .stack_size(4 * 1024 * 1024)
        .spawn(move || jsengine::run_provider_stream(&code, &link, is_series != 0))
        .map(|h| h.join().unwrap_or_else(|_| "[]".to_string()))
        .unwrap_or_else(|e| {
            log::error!("[jsengine] thread spawn failed: {e}");
            "[]".to_string()
        });

    env.new_string(json).expect("JNI string").into_raw()
}
