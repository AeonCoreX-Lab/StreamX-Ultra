// Rust JNI Bridge for StreamX
// Dependencies: jni = "0.21", lazy_static = "1.4"

use jni::JNIEnv;
use jni::objects::{JClass, JString, JFloatArray};
// FIX: unused 'jfloatArray' import removed
use jni::sys::{jboolean, jstring};
use std::sync::{Arc, Mutex};
use lazy_static::lazy_static;
use std::ffi::{CString, CStr};
use std::os::raw::c_char;

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
pub extern "system" fn Java_com_aeoncorex_streamx_ui_movie_StreamXCore_initAI(
    mut env: JNIEnv, // FIX: Added 'mut' here to fix E0596
    _class: JClass,
    model_path: JString,
) -> jboolean {
    // env.get_string requires mutable borrow
    let path_str: String = env.get_string(&model_path).expect("Couldn't get java string").into();
    let c_path = CString::new(path_str).unwrap();

    let success = unsafe { initAINative_CPP(c_path.as_ptr()) };
    
    let mut ctx = CTX.lock().unwrap();
    ctx.is_running = success;
    ctx.model_loaded = success;
    
    if success { 1 } else { 0 }
}

#[no_mangle]
pub extern "system" fn Java_com_aeoncorex_streamx_ui_movie_StreamXCore_pushAudio(
    mut env: JNIEnv, // FIX: Added 'mut'
    _class: JClass,
    audio_data: JFloatArray, 
) {
    let ctx = CTX.lock().unwrap();
    if !ctx.is_running { return; }

    let len = env.get_array_length(&audio_data).unwrap();
    let mut buf = vec![0.0f32; len as usize];
    
    // get_float_array_region requires mutable env
    env.get_float_array_region(&audio_data, 0, &mut buf).unwrap();

    unsafe {
        pushAudioNative_CPP(buf.as_ptr(), len as i32);
    }
}

#[no_mangle]
pub extern "system" fn Java_com_aeoncorex_streamx_ui_movie_StreamXCore_getSubtitle(
    mut env: JNIEnv, // FIX: Added 'mut'
    _class: JClass,
) -> jstring {
    let c_sub = unsafe { getSubtitleNative_CPP() };
    
    if c_sub.is_null() {
        return env.new_string("").unwrap().into_raw();
    }

    let c_str = unsafe { CStr::from_ptr(c_sub) };
    let output = c_str.to_str().unwrap_or("");
    
    // new_string requires mutable env
    env.new_string(output).unwrap().into_raw()
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
