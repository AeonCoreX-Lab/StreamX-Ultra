package com.aeoncorex.streamx.streaming

// ═════════════════════════════════════════════════════════════════════════════
//  TorrentWafDetect — domain-agnostic "is this a WAF/bot-challenge response,
//  not real content" classifier.
//  ─────────────────────────────────────────────────────────────────────────
//  Direct Kotlin port of the Worker's wafDetect.js isLikelyWafBlock(). Same
//  philosophy, ported for a different transport:
//
//    Worker version:  Cloudflare Worker's fetch() hits a provider's origin
//                      server-side, classifies the response, and — if
//                      WAF-blocked — stores/replays a clearance cookie via
//                      wafCookieStore.js's KV-backed registry so its OWN
//                      future fetch() calls to that domain succeed.
//
//    This version:    The Android app's OkHttp client hits a torrent site
//                      DIRECTLY (no Worker in the middle for this path), so
//                      there's no KV registry — WafCookieResolver already
//                      does the on-device equivalent (solve via WebView,
//                      hold the cookie in Android's CookieManager). This
//                      file only supplies the missing piece: a response
//                      classifier that decides WHEN to trigger that solve.
//
//  Deliberately NOT a hardcoded site list — same reasoning as wafDetect.js's
//  header comment: a fixed domain allowlist means every new torrent
//  provider needs a manual audit before its WAF-protected mirrors can ever
//  be solved. Classification is based purely on status code + response
//  body pattern, so it applies uniformly to whichever domain the request
//  happened to hit (1337x, TorrentGalaxy, BitSearch, or any future one).
// ═════════════════════════════════════════════════════════════════════════════
object TorrentWafDetect {

    // Same confirmed-dead-origin 1XXX codes as the Worker's wafDetect.js —
    // see that file's header comment for the Cloudflare doc references.
    // These mean the origin itself is unreachable/misconfigured; no
    // clearance cookie fixes that, so we must NOT treat these as solvable.
    private val CONFIRMED_DEAD_ORIGIN_CODES = setOf("1001", "1014", "1016", "1042")

    private val cf1xxxRegex = Regex("error code:?\\s*(1\\d{3})", RegexOption.IGNORE_CASE)

    private fun extractCloudflare1xxxCode(bodyText: String?): String? {
        if (bodyText.isNullOrEmpty()) return null
        return cf1xxxRegex.find(bodyText)?.groupValues?.get(1)
    }

    /**
     * @param status    HTTP status code of the response.
     * @param bodyText  Response body (first ~500 chars is all that's
     *                  inspected — same bound as the Worker version).
     * @return true if this response looks like a WAF/bot challenge that an
     *         on-device WebView solve (WafCookieResolver) could plausibly
     *         clear; false if it's either not a challenge, or a confirmed
     *         dead-origin case that solving cannot fix.
     */
    fun isLikelyWafBlock(status: Int, bodyText: String?): Boolean {
        if (status == 403 || status == 503) {
            if (bodyText.isNullOrEmpty()) return status == 403
            val sample = bodyText.take(500).lowercase()
            return sample.contains("cf-browser-verification") ||
                   sample.contains("cf_chl_") ||
                   sample.contains("checking your browser") ||
                   sample.contains("attention required") ||
                   sample.contains("cloudflare") ||
                   status == 403
        }

        if (status == 530 || status == 522) {
            val code = extractCloudflare1xxxCode(bodyText)
            if (code != null && code in CONFIRMED_DEAD_ORIGIN_CODES) {
                // Confirmed genuine DNS/config failure — no cookie fixes this.
                return false
            }
            // No recognized dead-origin code (either no code found, or an
            // unrecognized one) — default to attempting a solve, same
            // conservative-toward-retrying choice as the Worker version.
            return true
        }

        return false
    }
}
