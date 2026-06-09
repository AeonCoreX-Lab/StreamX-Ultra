// app/src/main/rust/src/lib.rs
// ═══════════════════════════════════════════════════════════════════════
//  StreamX Rust Core — JNI Bridge
//
//  All JNI function names are IDENTICAL to the old C++ native-lib.cpp
//  torrent functions. TorrentEngine.kt and StreamXCore don't change.
//
//  Functions provided:
//    ① TMDB key (already existed)
//    ② Torrent JNI  → replaces C++ TorrentSystem / torrent-engine.cpp
//    ③ Addon HTTP transport → nativeAddonFetchStreams (NEW)
// ═══════════════════════════════════════════════════════════════════════

#![allow(non_snake_case, clippy::missing_safety_doc)]

mod torrent;

use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jstring, jdouble, jlongArray};
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

// ── ① TMDB key ───────────────────────────────────────────────────────────────
// Unchanged from previous lib.rs — StreamXCore.getTmdbKey() still works
#[no_mangle]
pub extern "system" fn Java_com_aeoncorex_streamx_ui_movie_StreamXCore_getTmdbKey<'a>(
    env: JNIEnv<'a>,
    _class: JClass<'a>,
) -> jstring {
    let key = option_env!("TMDB_API_KEY").unwrap_or("api_key_not_found");
    env.new_string(key).expect("JNI string").into_raw()
}

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
// Same format as old C++ getStatusNative() — TorrentEngine.kt unchanged
#[no_mangle]
pub extern "system" fn Java_com_aeoncorex_streamx_ui_movie_TorrentEngine_getStatusNative(
    env:  JNIEnv,
    _obj: JClass,
) -> jlongArray {
    let s    = TorrentEngineHandle::get().status();
    let arr  = env.new_long_array(5).expect("jlongArray");
    let data = [
        s.progress  as i64,
        s.speed_bps,
        s.seeds     as i64,
        s.peers     as i64,
        s.state     as i64,
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

// Clear download cache directory
#[no_mangle]
pub extern "system" fn Java_com_aeoncorex_streamx_ui_movie_TorrentEngine_clearCacheNative(
    mut env: JNIEnv,
    _obj:  JClass,
    j_dir: JString,
) {
    let dir = jstr(&mut env, j_dir);
    torrent::engine::TorrentEngine::clear_cache(&dir);
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

// ── JNI helper ────────────────────────────────────────────────────────────────
// Fixed back to `&mut JNIEnv` since `get_string` requires a mutable reference.
fn jstr(env: &mut JNIEnv, s: JString) -> String {
    env.get_string(&s).map(|js| js.into()).unwrap_or_default()
}
