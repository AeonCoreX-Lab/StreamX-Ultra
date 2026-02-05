// Rust JNI Bridge for StreamX
// Dependencies in Cargo.toml: jni = "0.21", lazy_static = "1.4"

use jni::JNIEnv;
use jni::objects::{JClass, JString, JFloatArray};
// FIX: 'jfloatArray' রিমুভ করা হয়েছে কারণ এটি unused ছিল এবং ওয়ার্নিং দিচ্ছিল
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

// C++ Functions Import (Using c_char for cross-platform pointer compatibility)
extern "C" {
    fn initAINative_CPP(path: *const c_char) -> bool;
    fn pushAudioNative_CPP(data: *const f32, size: i32);
    fn getSubtitleNative_CPP() -> *const c_char;
    fn stopAINative_CPP();
}

#[no_mangle]
pub extern "system" fn Java_com_aeoncorex_streamx_ui_movie_StreamXCore_initAI(
    mut env: JNIEnv, // FIX: এখানে 'mut' যোগ করা হয়েছে (Error E0596 Solved)
    _class: JClass,
    model_path: JString,
) -> jboolean {
    // env.get_string ইন্টারনালি env মডিফাই করে, তাই mut env জরুরি
    let path_str: String = env.get_string(&model_path).expect("Couldn't get java string").into();
    
    let c_path = CString::new(path_str).unwrap();

    let mut ctx = CTX.lock().unwrap();
    
    // Cast pointer to *const c_char to match extern signature
    let success = unsafe { initAINative_CPP(c_path.as_ptr() as *const c_char) };
    
    ctx.is_running = success;
    ctx.model_loaded = success;
    
    if success { 1 } else { 0 }
}

#[no_mangle]
pub extern "system" fn Java_com_aeoncorex_streamx_ui_movie_StreamXCore_pushAudio(
    mut env: JNIEnv, // FIX: এখানেও 'mut' যোগ করা হয়েছে
    _class: JClass,
    audio_data: JFloatArray, 
) {
    let ctx = CTX.lock().unwrap();
    if !ctx.is_running { return; }

    let len = env.get_array_length(&audio_data).unwrap();
    let mut buf = vec![0.0f32; len as usize];
    
    // get_float_array_region মেমোরি কপি করে, তাই env মিউটেবল হতে হবে
    env.get_float_array_region(&audio_data, 0, &mut buf).unwrap();

    unsafe {
        pushAudioNative_CPP(buf.as_ptr(), len as i32);
    }
}

#[no_mangle]
pub extern "system" fn Java_com_aeoncorex_streamx_ui_movie_StreamXCore_getSubtitle(
    mut env: JNIEnv, // FIX: এখানেও 'mut' যোগ করা হয়েছে
    _class: JClass,
) -> jstring {
    let c_sub = unsafe { getSubtitleNative_CPP() };
    
    if c_sub.is_null() {
        return env.new_string("").unwrap().into_raw();
    }

    let c_str = unsafe { CStr::from_ptr(c_sub as *const c_char) };
    let output = c_str.to_str().unwrap_or("");

    // new_string নতুন জাভা অবজেক্ট তৈরি করে, তাই env মিউটেবল হতে হবে
    env.new_string(output).unwrap().into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_aeoncorex_streamx_ui_movie_StreamXCore_stopAI(
    _env: JNIEnv, // এখানে env ব্যবহার হচ্ছে না, তাই mut না দিলেও চলে, তবে ওয়ার্নিং এড়াতে _env রাখা ভালো
    _class: JClass,
) {
    let mut ctx = CTX.lock().unwrap();
    ctx.is_running = false;
    unsafe { stopAINative_CPP(); }
}
