package com.aeoncorex.streamx.ui.movie

object StreamXCore {
    init {
        // Rust code ti amader 'streamx-native' library er bhitor thake
        System.loadLibrary("streamx-native")
    }

    // Rust theke TMDB Key anar jonno eiti proyojon
    @JvmStatic
    external fun getTmdbKey(): String

    // Apnar Rust file (lib.rs) e thaka onno function gulo eikhane thaka uchit
    @JvmStatic
    external fun initAI(modelPath: String): Boolean

    @JvmStatic
    external fun getSubtitle(): String

    @JvmStatic
    external fun stopAI()
}
