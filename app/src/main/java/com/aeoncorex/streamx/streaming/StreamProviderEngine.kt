package com.aeoncorex.streamx.streaming

import kotlinx.coroutines.channels.Channel

// ═══════════════════════════════════════════════════════════════════════════
//  StreamProviderEngine.kt — v3 (JS Addon System)
//
//  All Kotlin providers have been removed.
//  This file now delegates to JsStreamProviderEngine which executes
//  bundled JS provider modules downloaded via AddonManager.
//
//  All existing callers (ExoSourceSelectionScreen, PrefetchEngine)
//  are unchanged — they still call StreamProviderEngine.fetch() /
//  fetchStreaming() as before.
// ═══════════════════════════════════════════════════════════════════════════
object StreamProviderEngine {

    suspend fun fetch(req: ProviderRequest): List<StreamResult> =
        JsStreamProviderEngine.fetch(req)

    fun fetchStreaming(req: ProviderRequest): Channel<List<StreamResult>> =
        JsStreamProviderEngine.fetchStreaming(req)
}
