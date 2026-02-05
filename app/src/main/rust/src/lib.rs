// Rust JNI Bridge for StreamX
// Add dependencies in Cargo.toml: jni = "0.21", lazy_static = "1.4", ringbuf = "0.3"

use jni::JNIEnv;
use jni::objects::{JClass, JString, JValue};
use jni::sys::{jboolean, jfloatArray, jstring, jint};
use std::sync::{Arc, Mutex};
use lazy_static::lazy_static;

// Global State wrapper (Thread Safe)
struct AiContext {
    is_running: bool,
    model_loaded: bool,
    subtitle: String,
    // Add RingBuffer here if needed for audio accumulation
}

lazy_static! {
    static ref CTX: Arc<Mutex<AiContext>> = Arc::new(Mutex::new(AiContext {
        is_running: false,
        model_loaded: false,
        subtitle: String::new(),
    }));
}

// C++ Functions Import (Linking to your existing Whisper C++ code)
extern "C" {
    fn initAINative_CPP(path: *const i8) -> bool;
    fn pushAudioNative_CPP(data: *const f32, size: i32);
    fn getSubtitleNative_CPP() -> *const i8;
    fn stopAINative_CPP();
}

#[no_mangle]
pub extern "system" fn Java_com_aeoncorex_streamx_ui_movie_StreamXCore_initAI(
    env: JNIEnv,
    _class: JClass,
    model_path: JString,
) -> jboolean {
    let path_str: String = env.get_string(model_path).expect("Couldn't get java string").into();
    let c_path = std::ffi::CString::new(path_str).unwrap();

    let mut ctx = CTX.lock().unwrap();
    
    // Call C++ unsafe method via Rust Wrapper
    let success = unsafe { initAINative_CPP(c_path.as_ptr()) };
    
    ctx.is_running = success;
    ctx.model_loaded = success;
    
    if success { 1 } else { 0 }
}

#[no_mangle]
pub extern "system" fn Java_com_aeoncorex_streamx_ui_movie_StreamXCore_pushAudio(
    env: JNIEnv,
    _class: JClass,
    audio_data: jfloatArray,
) {
    let ctx = CTX.lock().unwrap();
    if !ctx.is_running { return; }

    // Critical Performance Path: Zero-copy access if possible
    let len = env.get_array_length(audio_data).unwrap();
    let mut buf = vec![0.0f32; len as usize];
    
    env.get_float_array_region(audio_data, 0, &mut buf).unwrap();

    unsafe {
        pushAudioNative_CPP(buf.as_ptr(), len);
    }
}

#[no_mangle]
pub extern "system" fn Java_com_aeoncorex_streamx_ui_movie_StreamXCore_getSubtitle(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    // We can cache subtitle in Rust to avoid frequent C++ Calls if needed
    let c_sub = unsafe { getSubtitleNative_CPP() };
    let c_str = unsafe { std::ffi::CStr::from_ptr(c_sub) };
    let output = c_str.to_str().unwrap_or("");
    
    env.new_string(output).unwrap().into_inner()
}

#[no_mangle]
pub extern "system" fn Java_com_aeoncorex_streamx_ui_movie_StreamXCore_stopAI(
    _env: JNIEnv,
    _class: JClass,
) {
    let mut ctx = CTX.lock().unwrap();
    ctx.is_running = false;
    unsafe { stopAINative_CPP(); }
}
