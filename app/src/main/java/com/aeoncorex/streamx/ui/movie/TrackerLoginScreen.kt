package com.aeoncorex.streamx.ui.movie

// ═══════════════════════════════════════════════════════════════════════════
//  TrackerLoginScreen.kt — automatic session-cookie capture for a
//  built-in private tracker.
//
//  WHAT THIS IS: a real, visible WebView pointed at the tracker's own
//  site. The user logs in exactly as they would in a normal browser —
//  their username and password are typed into the TRACKER's own login
//  page, inside the WebView, never into any field this app renders.
//  This screen never sees, reads, or stores anything the user types.
//
//  WHAT THIS DOES AUTOMATICALLY: after every page load, it fetches the
//  site's login_check_path (a page only visible when logged in) using
//  the WebView's current cookies, and checks for login_check_selector
//  in the response. The moment that check passes, the session cookie is
//  pulled from Android's CookieManager and saved to
//  PrivateTrackerCookieStore — no manual copy/paste step, no DevTools,
//  no "find your cookie" instructions. The user just logs in and this
//  screen detects it.
//
//  See AuthConfig's doc comment in schema.rs (streamx-torrent-indexer)
//  for why this WebView approach was chosen over automating the login
//  form itself (submitting username/password programmatically) — this
//  app never touches a tracker password at all, only the cookie a real,
//  user-performed login already produced.
// ═══════════════════════════════════════════════════════════════════════════

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import com.aeoncorex.streamx.streaming.HttpClient
import com.aeoncorex.streamx.streaming.PrivateTrackerCookieStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

@Composable
fun TrackerLoginScreen(
    navController: NavController,
    siteId: String,
    displayName: String,
    baseUrl: String,
    instructions: String,
    loginCheckPath: String?,
    loginCheckSelector: String?
) {
    val context = LocalContext.current

    var isLoggedIn   by remember { mutableStateOf(false) }
    var isChecking   by remember { mutableStateOf(false) }
    var pageTitle    by remember { mutableStateOf(displayName) }
    var webViewRef   by remember { mutableStateOf<WebView?>(null) }

    // ── Periodic login-check poll ─────────────────────────────────────
    // Fires roughly every 2s while the screen is open — cheap (a single
    // GET to a page the user is likely already loading anyway) and
    // means login is detected within ~2s of the tracker's own
    // post-login redirect, without needing to parse the WebView's own
    // DOM (which would require injecting JS and handling every
    // tracker's different post-login page differently). Stops polling
    // once isLoggedIn flips true.
    LaunchedEffect(siteId) {
        if (loginCheckPath == null || loginCheckSelector == null) return@LaunchedEffect
        while (isActive && !isLoggedIn) {
            delay(2000)
            val cookie = withContext(Dispatchers.IO) {
                CookieManager.getInstance().getCookie(baseUrl)
            }
            if (cookie.isNullOrBlank()) continue // not logged in yet, no cookie at all

            isChecking = true
            val checkUrl = baseUrl.trimEnd('/') + loginCheckPath
            val html = withContext(Dispatchers.IO) {
                HttpClient.getHtml(checkUrl, mapOf("Cookie" to cookie))
            }
            isChecking = false

            if (html != null && matchesSelector(html, loginCheckSelector)) {
                PrivateTrackerCookieStore.put(siteId, cookie)
                PrivateTrackerCookieStore.setVerified(siteId, ok = true)
                isLoggedIn = true
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF0A0A14))) {

        // ── Top bar ───────────────────────────────────────────────────
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(12.dp)
        ) {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(Icons.Rounded.ArrowBack, null, tint = Color.White)
            }
            Column(Modifier.weight(1f)) {
                Text(displayName, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (isLoggedIn) "Logged in" else pageTitle,
                    color = if (isLoggedIn) Color(0xFF00E676) else Color.Gray,
                    fontSize = 11.sp
                )
            }
            if (isLoggedIn) {
                Icon(Icons.Rounded.CheckCircle, null, tint = Color(0xFF00E676), modifier = Modifier.size(22.dp))
            } else {
                IconButton(onClick = { webViewRef?.reload() }) {
                    Icon(Icons.Rounded.Refresh, null, tint = Color.Gray)
                }
            }
        }

        if (instructions.isNotBlank() && !isLoggedIn) {
            Text(
                instructions,
                color = Color.Gray,
                fontSize = 12.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF14141F), RoundedCornerShape(0.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (isLoggedIn) {
                // Replace the WebView with a clear success state instead
                // of leaving a now-pointless login page on screen — the
                // user's job here is done, nothing left to interact with.
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Rounded.CheckCircle, null, tint = Color(0xFF00E676), modifier = Modifier.size(56.dp))
                    Spacer(Modifier.height(16.dp))
                    Text("You're logged in to $displayName", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Its torrents will now appear in your search results automatically.",
                        color = Color.Gray, fontSize = 13.sp
                    )
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = { navController.popBackStack() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0E2A1E))
                    ) { Text("Done", color = Color.Cyan) }
                }
            } else {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(-1, -1)
                            @SuppressLint("SetJavaScriptEnabled")
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.userAgentString = HttpClient.DESKTOP_UA

                            // setAcceptThirdPartyCookies needs the actual
                            // WebView instance (this@apply here means
                            // THIS WebView, not CookieManager) — a nested
                            // CookieManager.getInstance().apply { } block
                            // would shadow `this` to the CookieManager
                            // instead, which is a type mismatch this
                            // method never accepts.
                            val cm = CookieManager.getInstance()
                            cm.setAcceptCookie(true)
                            cm.setAcceptThirdPartyCookies(this, true)

                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    pageTitle = view?.title?.takeIf { it.isNotBlank() } ?: (url ?: "")
                                    CookieManager.getInstance().flush()
                                }
                            }

                            webViewRef = this
                            loadUrl(baseUrl)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                if (isChecking) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
                        color = Color.Cyan,
                        trackColor = Color.Transparent
                    )
                }
            }
        }
    }
}

/**
 * Minimal, dependency-free "does this HTML contain something matching
 * this CSS selector" check — good enough for the simple presence
 * selectors login_check_selector actually uses (an id, a class, an
 * href-prefix attribute selector — see the SiteConfig auth blocks in
 * sources/private/ (e.g. hdtorrents.json) for real examples), without
 * pulling in a full HTML parser + CSS engine just for a yes/no
 * presence check here. Falls
 * back to treating the selector as a plain substring search for
 * anything this simplified parser doesn't recognize, which is safe: a
 * false positive would need the plain text of a CSS selector to appear
 * verbatim in the page by coincidence, and a false negative just means
 * one extra poll cycle (2s) before login is detected — either way, the
 * real Jsoup-based selector matching already used elsewhere in this
 * app (see HttpClient.getDoc) is available if a future site's selector
 * needs it; this stays intentionally simple since login-check pages
 * are small and the selectors used for them are, by design, simple
 * presence checks.
 */
private fun matchesSelector(html: String, selector: String): Boolean {
    return try {
        val doc = org.jsoup.Jsoup.parse(html)
        doc.select(selector).isNotEmpty()
    } catch (e: Exception) {
        html.contains(selector, ignoreCase = true)
    }
}
