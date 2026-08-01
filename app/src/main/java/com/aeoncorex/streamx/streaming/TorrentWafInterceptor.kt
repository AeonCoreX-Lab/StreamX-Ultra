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
//  ── CHANGED 2026-07-31: proactive attach + persistent cache ──────────────
//  Previously this interceptor was PURELY reactive: every single request,
//  even to a domain solved 30 seconds ago, went out with no cookie, hit a
//  403, and only THEN triggered a solve — meaning every torrent search paid
//  the WAF round-trip cost on every request, and a solved cookie only ever
//  lived in Android's CookieManager with no TTL tracking, so there was no
//  way to tell "still good" from "probably expired" without just trying.
//  This is very likely why the interceptor "felt like it wasn't working" —
//  it WAS solving, but only ever after already failing once, request after
//  request, with no memory of having solved this domain minutes ago.
//
//  Now: TorrentWafCookieStore (MMKV, TTL-aware, mirrors wafCookieStore.js's
//  design) is checked FIRST, before the request even goes out. A fresh
//  cached cookie is attached proactively — most requests to an
//  already-solved domain now never see a 403 at all. The reactive path
//  (solve-on-403) still exists as a fallback for a domain that's never
//  been seen before, or whose cached cookie turned out to be stale despite
//  looking fresh (a WAF vendor can always invalidate early).
//
//  Flow:
//    1. Check TorrentWafCookieStore for a fresh cookie for this domain. If
//       found, attach it to the request BEFORE sending.
//    2. Send the request (with or without a cookie attached).
//    3. If the response still looks like a WAF challenge (TorrentWafDetect):
//         a. If we'd attached a cached cookie, it was apparently stale —
//            invalidate it in the store so the next request doesn't retry
//            the same dead cookie.
//         b. Resolve fresh on-device (WafCookieResolver.resolveLocalOnly —
//            Phase-1-only, invisible WebView; see that function's doc
//            comment for why this path never reports to the Worker).
//         c. On success, WafCookieResolver itself now persists the solved
//            cookie into TorrentWafCookieStore (see its resolveSilentInternal)
//            — this interceptor just reads it back out and retries once.
//    4. Whether the retry succeeds or the solve failed, we return whatever
//       response we have — callers' existing try/catch and empty-list
//       fallbacks handle a still-bad response exactly like any other
//       provider failure, no special-casing needed there.
//
//  Bounded to ONE retry attempt per request — this is an interceptor
//  sitting in a hot path for every torrent query, not a background warmup,
//  so it must never loop.
// ═════════════════════════════════════════════════════════════════════════════
object TorrentWafInterceptor : Interceptor {

    private const val TAG = "TorrentWafInterceptor"

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val domain = originalRequest.url.host

        // ── Step 1: proactive attach from the persistent cache ──────────
        val cached = TorrentWafCookieStore.getFresh(domain)
        val requestToSend = if (cached != null) {
            Log.d(TAG, "$domain: attaching cached WAF cookie (${cached.secondsRemaining()}s remaining)")
            originalRequest.newBuilder().header("Cookie", cached.cookie).build()
        } else {
            originalRequest
        }

        val response = chain.proceed(requestToSend)

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
                Log.d(TAG, "$domain: response looks WAF-blocked (${response.code}) — attempting on-device solve")

                if (cached != null) {
                    // The cookie we proactively attached didn't actually
                    // work — the WAF vendor invalidated it earlier than our
                    // TTL assumed. Drop it now so the NEXT request to this
                    // domain doesn't repeat the same failed attach.
                    Log.d(TAG, "$domain: cached cookie was stale despite TTL — invalidating")
                    TorrentWafCookieStore.invalidate(domain)
                }

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
                // comment in WafCookieResolver.kt. It DOES now persist to
                // TorrentWafCookieStore internally (see resolveSilentInternal)
                // — that's what step 1 above reads from on the NEXT request.
                val solved = runBlocking {
                    WafCookieResolver.resolveLocalOnly(context, domain)
                }

                if (!solved) {
                    Log.d(TAG, "$domain: solve did not succeed — returning original (blocked) response")
                    return response
                }

                // Prefer the freshly-persisted store entry (has a known TTL
                // attached); CookieManager is only a fallback for the
                // unexpected case where the store write somehow didn't land.
                val cookie = TorrentWafCookieStore.getFresh(domain)?.cookie
                    ?: CookieManager.getInstance().getCookie("https://$domain/")
                if (cookie.isNullOrBlank()) {
                    Log.w(TAG, "$domain: solved but no cookie found — returning original response")
                    return response
                }

                response.close()

                val retryRequest = originalRequest.newBuilder()
                    .header("Cookie", cookie)
                    .build()

                Log.d(TAG, "$domain: retrying request with solved cookie")
                return chain.proceed(retryRequest)
            }
        }

        return response
    }
}
