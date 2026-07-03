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
//    ③ Addon HTTP transport → nativeAddonFetchStreams
//    ④ JS provider engine (QuickJS) → nativeExecuteJsStream (NEW)
// ═══════════════════════════════════════════════════════════════════════

#![allow(non_snake_case, clippy::missing_safety_doc)]

mod torrent;
mod jsengine;
mod indexer;

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
