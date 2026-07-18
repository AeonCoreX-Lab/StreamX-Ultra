package com.aeoncorex.streamx.streaming

import kotlinx.coroutines.channels.Channel

// ═══════════════════════════════════════════════════════════════════════════
//  StreamProviderEngine.kt — v4 (Cloudflare Worker resolver)
//
//  Delegates to WorkerStreamProviderEngine, which calls the
//  streamx-stream-resolver Cloudflare Worker instead of executing addon JS
//  on-device. All existing callers (ExoSourceSelectionScreen, PrefetchEngine)
//  are unchanged — they still call StreamProviderEngine.fetch() /
//  fetchStreaming() as before.
//
//  v3's JsStreamProviderEngine (QuickJS/AddonManager/AddonStorage-based) is
//  kept in the codebase for now as a fallback reference while the Worker
//  path is verified in the field, but is no longer wired in here. Once
//  confirmed stable, JsStreamProviderEngine.kt, AddonManager.kt,
//  AddonStorage.kt, and the native StreamXNative.executeJsStream JNI path
//  can be removed.
// ═══════════════════════════════════════════════════════════════════════════
object StreamProviderEngine {

    suspend fun fetch(req: ProviderRequest): List<StreamResult> =
        WorkerStreamProviderEngine.fetch(req)

    fun fetchStreaming(req: ProviderRequest): Channel<List<StreamResult>> =
        WorkerStreamProviderEngine.fetchStreaming(req)
}
