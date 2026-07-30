package com.aeoncorex.streamx.streaming

import android.util.Log
import android.webkit.CookieManager
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response

// ═════════════════════════════════════════════════════════════════════════════
//  TorrentWafInterceptor
//  ─────────────────────────────────────────────────────────────────────────
//  Client-side counterpart to the Worker's globalFetchPatch.js + axiosShim.js
//  (the two callers of wafDetect.js's isLikelyWafBlock()). Those patch the
//  Worker's own fetch()/axios calls so EVERY provider request gets checked,
//  without each provider having to opt in individually. This interceptor
//  does the same thing for the app's torrent HTTP calls: attach it once to
//  the shared OkHttpClient (see TorrentProviders.httpClient) and every
//  request through that client — BitSearch, TorrentCSV, SolidTorrents, any
//  future provider added to the same client — is automatically covered.
//
//  Purely response-driven, same as the Worker version: no per-domain
//  allowlist here either. Whichever domain the request happened to hit is
//  the domain that gets the WebView solve — the interceptor never needs to
//  know in advance which sites use Cloudflare.
//
//  Flow:
//    1. Request goes out normally.
//    2. If the response looks like a WAF challenge (TorrentWafDetect —
//       ported from wafDetect.js) instead of a dead origin, we know a
//       clearance cookie would likely fix it.
//    3. Resolve it on-device (WafCookieResolver.resolveLocalOnly —
//       Phase-1-only, invisible WebView, never surprises the user with a
//       captcha popup mid-search, same solve mechanics as the Worker-flow's
//       resolveForLiveRetry() but WITHOUT reporting the cookie to the
//       Worker — there's no Worker in this request path to report to; see
//       resolveLocalOnly()'s doc comment for why that distinction matters).
//    4. If solved, the cookie is now sitting in Android's CookieManager.
//       OkHttp's default client has no CookieJar wired to that store, so we
//       read it back out and attach it as a request header manually, then
//       retry the SAME request once.
//    5. Whether the retry succeeds or the solve failed, we return
//       whatever response we have — callers' existing try/catch and
//       empty-list fallbacks handle a still-bad response exactly like any
//       other provider failure, no special-casing needed there.
//
//  Bounded to ONE retry attempt per request — this is an interceptor
//  sitting in a hot path for every torrent query, not a background warmup,
//  so it must never loop.
// ═════════════════════════════════════════════════════════════════════════════
object TorrentWafInterceptor : Interceptor {

    private const val TAG = "TorrentWafInterceptor"

    override fun intercept(chain: Interceptor.Chain): Response {
        val request  = chain.request()
        val response = chain.proceed(request)

        if (!response.isSuccessful) {
            // Peek the body (peekBody, not body!!.string()) so we don't
            // consume the real response stream if this turns out NOT to be
            // a WAF block — the original response still needs to be
            // returned/re-readable by the caller in that case.
            val bodySample = try {
                response.peekBody(2_048).string()
            } catch (e: Exception) {
                null
            }

            if (TorrentWafDetect.isLikelyWafBlock(response.code, bodySample)) {
                val domain = request.url.host
                Log.d(TAG, "$domain: response looks WAF-blocked (${response.code}) — attempting on-device solve")

                val context = WorkerStreamProviderEngine.getThemedContext()
                if (context == null) {
                    Log.w(TAG, "$domain: no themed context available yet — skipping solve, returning original response")
                    return response
                }

                // Interceptors run synchronously on OkHttp's dispatcher
                // thread (already off the caller's own thread — every
                // torrent fetch function here is wrapped in
                // withContext(Dispatchers.IO)), so blocking this thread on
                // the suspend solve call is safe and doesn't freeze the UI.
                // resolveLocalOnly() — NOT resolveForLiveRetry() — since this
                // torrent traffic never goes through the streamx-stream-resolver
                // Worker at all. Reporting the cookie there would just bloat
                // the Worker's KV registry with domains it never fetches
                // itself, for zero benefit. See resolveLocalOnly()'s doc
                // comment in WafCookieResolver.kt.
                val solved = runBlocking {
                    WafCookieResolver.resolveLocalOnly(context, domain)
                }

                if (!solved) {
                    Log.d(TAG, "$domain: solve did not succeed — returning original (blocked) response")
                    return response
                }

                val cookie = CookieManager.getInstance().getCookie("https://$domain/")
                if (cookie.isNullOrBlank()) {
                    Log.w(TAG, "$domain: solved but no cookie found in CookieManager — returning original response")
                    return response
                }

                response.close()

                val retryRequest = request.newBuilder()
                    .header("Cookie", cookie)
                    .build()

                Log.d(TAG, "$domain: retrying request with solved cookie")
                return chain.proceed(retryRequest)
            }
        }

        return response
    }
}
