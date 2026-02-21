// Rust JNI Bridge for StreamX
// Dependencies: jni = "0.21", lazy_static = "1.4"

use jni::JNIEnv;
use jni::objects::{JFloatArray, JObject, JString};
use jni::sys::{jboolean, jstring};
use lazy_static::lazy_static;
use std::ffi::{CStr, CString};
use std::os::raw::c_char;
use std::sync::{Arc, Mutex};

// Global State wrapper (Thread Safe)
struct AiContext {
    is_running: bool,
    model_loaded: bool,
    subtitle: String,
}

lazy_static! {
    static ref CTX: Arc<Mutex<AiContext>> = Arc::new(Mutex::new(AiContext {
        is_running: false,
        model_loaded: false,
        subtitle: String::new(),
    }));
}

// C++ Functions Import
extern "C" {
    fn initAINative_CPP(path: *const c_char) -> bool;
    fn pushAudioNative_CPP(data: *const f32, size: i32);
    fn getSubtitleNative_CPP() -> *const c_char;
    fn stopAINative_CPP();
}

#[no_mangle]
pub extern "system" fn Java_com_aeoncorex_streamx_ui_movie_StreamXCore_initAI<'local>(
    mut env: JNIEnv<'local>, 
    _class: JObject<'local>,
    model_path: JString<'local>
) -> jboolean {
    let path: String = env.get_string(&model_path).expect("Invalid string").into();
    let c_path = CString::new(path).unwrap();
    
    let success = unsafe { initAINative_CPP(c_path.as_ptr()) };
    
    let mut ctx = CTX.lock().unwrap();
    ctx.is_running = success;
    ctx.model_loaded = success;
    
    if success { 1 } else { 0 }
}

#[no_mangle]
pub extern "system" fn Java_com_aeoncorex_streamx_ui_movie_StreamXCore_pushAudio<'local>(
    env: JNIEnv<'local>, 
    _class: JObject<'local>,
    audio_data: JFloatArray<'local>, 
) {
    let ctx = CTX.lock().unwrap();
    if !ctx.is_running { return; }

    let len = env.get_array_length(&audio_data).unwrap();
    let mut buf = vec![0.0f32; len as usize];
    
    env.get_float_array_region(&audio_data, 0, &mut buf).unwrap();

    unsafe {
        pushAudioNative_CPP(buf.as_ptr(), len as i32);
    }
}

#[no_mangle]
pub extern "system" fn Java_com_aeoncorex_streamx_ui_movie_StreamXCore_getSubtitle<'local>(
    mut env: JNIEnv<'local>, 
    _class: JObject<'local>,
) -> jstring {
    let c_sub = unsafe { getSubtitleNative_CPP() };
    
    if c_sub.is_null() {
        return env.new_string("").unwrap().into_raw();
    }

    let c_str = unsafe { CStr::from_ptr(c_sub) };
    let sub_str = c_str.to_str().unwrap_or("");
    
    env.new_string(sub_str).unwrap().into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_aeoncorex_streamx_ui_movie_StreamXCore_stopAI<'local>(
    _env: JNIEnv<'local>,
    _class: JObject<'local>,
) {
    let mut ctx = CTX.lock().unwrap();
    ctx.is_running = false;
    ctx.model_loaded = false;
    
    unsafe { stopAINative_CPP(); }
}

// --- SECURE VAULT FOR TMDB API ---
#[no_mangle]
pub extern "system" fn Java_com_aeoncorex_streamx_ui_movie_StreamXCore_getTmdbKey<'local>(
    mut env: JNIEnv<'local>,
    _class: JObject<'local>,
) -> jstring {
    // Fetches the secret injected from GitHub Actions during cargo build
    let secret_key = option_env!("TMDB_API_KEY").unwrap_or("api_key_not_found");
    env.new_string(secret_key).expect("Failed to create JNI string").into_raw()
}
