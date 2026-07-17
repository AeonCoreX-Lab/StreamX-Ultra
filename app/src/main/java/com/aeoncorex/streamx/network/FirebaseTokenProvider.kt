package com.aeoncorex.streamx.network

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.tasks.await

// ═════════════════════════════════════════════════════════════════════════════
//  FirebaseTokenProvider
//  ─────────────────────────────────────────────────────────────────────────
//  Single shared source of Firebase ID tokens for every call to the
//  streamx-metadata-cache Worker. Replaces the old static
//  BuildConfig.WORKER_AUTH_SECRET header used by both MovieRepository
//  (Retrofit/OkHttp) and CinemetaRepository (HttpClient.getJson).
//
//  Why one shared object instead of duplicating token logic in each
//  repository:
//    • Both repositories hit the same Worker, so both need the exact
//      same "get a fresh token, refresh only when needed" behavior.
//    • Firebase caches the current ID token internally already —
//      getIdToken(false) does NOT hit the network unless the cached
//      token is within ~5 min of expiring. Calling it from multiple
//      places is cheap and safe; there's no reason to hand-roll our
//      own in-memory cache on top of it.
//
//  forceRefresh=true is only used for the retry-once-after-401 path —
//  see MovieRepository's authInterceptor and CinemetaRepository's calls.
// ═════════════════════════════════════════════════════════════════════════════
object FirebaseTokenProvider {

    private const val TAG = "FirebaseTokenProvider"

    /**
     * Returns a valid Firebase ID token for the current signed-in user,
     * or null if nobody is signed in or the token fetch fails (e.g. no
     * network — in that case the request will go out unauthenticated
     * and the Worker will correctly reject it with 401, rather than us
     * crashing or blocking the whole request pipeline here).
     */
    suspend fun getIdToken(forceRefresh: Boolean = false): String? {
        val user = FirebaseAuth.getInstance().currentUser ?: run {
            Log.w(TAG, "getIdToken: no signed-in user")
            return null
        }
        return try {
            user.getIdToken(forceRefresh).await()?.token
        } catch (e: Exception) {
            Log.w(TAG, "getIdToken failed: ${e.message}")
            null
        }
    }
}
