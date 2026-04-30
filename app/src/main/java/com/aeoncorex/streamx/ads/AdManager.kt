package com.aeoncorex.streamx.ads

import android.app.Activity
import android.app.Application
import android.util.Log
import com.aeoncorex.streamx.BuildConfig
import com.startapp.sdk.adsbase.StartAppAd
import com.startapp.sdk.adsbase.StartAppSDK
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// ═══════════════════════════════════════════════════════════════════
//  AdManager — Start.io (start.io)
//  ─────────────────────────────────
//  Like Movie Box ad system:
//    1. Pre-play interstitial before every movie
//    2. Timed mid-video ad (timer countdown in player)
//       → at 5 min mark, ad appears with timer
//       → sometimes skippable after 5s, sometimes must watch 15s
//
//  App ID from GitHub Secret STARTAPP_APP_ID → BuildConfig
//  No approval, no verification, instant live.
//
//  Ad-free premium: isPremiumCached() → skip all ads
// ═══════════════════════════════════════════════════════════════════
object AdManager {

    private const val TAG = "AdManager"

    // Timed ad interval: show ad every N seconds of playback
    // Movie Box shows ad around 4-5 minutes, then again later
    const val AD_INTERVAL_SECONDS  = 300L  // 5 minutes
    const val AD_SKIP_DELAY_SECONDS = 5    // must watch 5s before skip appears
    const val AD_MAX_SECONDS        = 30   // max ad duration if not skipped

    private var isSdkReady      = false
    private var interstitialAd: StartAppAd? = null

    fun initialize(application: Application) {
        if (isSdkReady) return
        try {
            StartAppSDK.init(application, BuildConfig.STARTAPP_APP_ID, false)
            StartAppSDK.setTestAdsEnabled(false)
            isSdkReady = true
            Log.d(TAG, "Start.io ready ✓ id=${BuildConfig.STARTAPP_APP_ID}")
        } catch (e: Exception) {
            Log.e(TAG, "Start.io init failed: ${e.message}")
        }
    }

    // ── App launch return ad ──────────────────────────────────────
    fun showReturnAd(activity: Activity) {
        if (!isSdkReady) return
        CoroutineScope(Dispatchers.Main).launch {
            if (isPremiumSafe()) return@launch
            try { StartAppAd(activity).showAd() } catch (_: Exception) {}
        }
    }

    // ── Pre-load interstitial ─────────────────────────────────────
    fun loadInterstitial(activity: Activity) {
        if (!isSdkReady) return
        try {
            interstitialAd = StartAppAd(activity)
            interstitialAd?.loadAd()
        } catch (_: Exception) {}
    }

    // ── Pre-play interstitial (before movie starts) ───────────────
    fun showInterstitial(activity: Activity, onDone: () -> Unit) {
        if (!isSdkReady) { onDone(); return }
        CoroutineScope(Dispatchers.Main).launch {
            if (isPremiumSafe()) { onDone(); return@launch }
            val ad = interstitialAd
            if (ad == null) { loadInterstitial(activity); onDone(); return@launch }
            try {
                ad.showAd(object : com.startapp.sdk.adsbase.adlisteners.AdDisplayListener {
                    override fun adHidden(a: com.startapp.sdk.adsbase.Ad?) {
                        loadInterstitial(activity); onDone()
                    }
                    override fun adDisplayed(a: com.startapp.sdk.adsbase.Ad?) {}
                    override fun adClicked(a: com.startapp.sdk.adsbase.Ad?) {}
                    override fun adNotDisplayed(a: com.startapp.sdk.adsbase.Ad?) {
                        loadInterstitial(activity); onDone()
                    }
                })
            } catch (_: Exception) {
                loadInterstitial(activity)
                onDone()
            }
        }
    }

    // ── Timed in-video ad (Movie Box style) ───────────────────────
    // Called by ExoMoviePlayerScreen when playback timer hits interval
    // Returns true if ad will be shown (caller should pause playback)
    fun showTimedAd(activity: Activity, onAdComplete: () -> Unit): Boolean {
        if (!isSdkReady) { onAdComplete(); return false }
        var willShow = false
        CoroutineScope(Dispatchers.Main).launch {
            if (isPremiumSafe()) { onAdComplete(); return@launch }
            willShow = true
            try {
                val ad = StartAppAd(activity)
                ad.loadAd(object : com.startapp.sdk.adsbase.adlisteners.AdEventListener {
                    override fun onReceiveAd(loadedAd: com.startapp.sdk.adsbase.Ad?) {
                        try {
                            ad.showAd(object : com.startapp.sdk.adsbase.adlisteners.AdDisplayListener {
                                override fun adHidden(a: com.startapp.sdk.adsbase.Ad?) { onAdComplete() }
                                override fun adDisplayed(a: com.startapp.sdk.adsbase.Ad?) {}
                                override fun adClicked(a: com.startapp.sdk.adsbase.Ad?) {}
                                override fun adNotDisplayed(a: com.startapp.sdk.adsbase.Ad?) { onAdComplete() }
                            })
                        } catch (_: Exception) { onAdComplete() }
                    }
                    override fun onFailedToReceiveAd(failedAd: com.startapp.sdk.adsbase.Ad?) { onAdComplete() }
                })
            } catch (_: Exception) {
                onAdComplete()
            }
        }
        return willShow
    }

    fun isPremiumCached(): Boolean = try {
        com.aeoncorex.streamx.ui.premium.PremiumManager.isPremiumCached()
    } catch (_: Exception) { false }

    private suspend fun isPremiumSafe(): Boolean = try {
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            com.aeoncorex.streamx.ui.premium.PremiumManager.isPremium()
        }
    } catch (_: Exception) { isPremiumCached() }
}