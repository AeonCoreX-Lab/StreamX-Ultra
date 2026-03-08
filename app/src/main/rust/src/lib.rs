// Rust JNI Bridge for StreamX
// Dependencies: jni = "0.21"

use jni::JNIEnv;
use jni::objects::JClass;
use jni::sys::jstring;

// --- SECURE VAULT FOR TMDB API ---
#[no_mangle]
pub extern "system" fn Java_com_aeoncorex_streamx_ui_movie_StreamXCore_getTmdbKey<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jstring {
    // Fetches the secret injected from GitHub Actions during cargo build
    let secret_key = option_env!("TMDB_API_KEY").unwrap_or("api_key_not_found");
    
    // Return the key safely to Kotlin
    let output = env.new_string(secret_key).expect("Failed to create JNI string");
    output.into_raw()
}
