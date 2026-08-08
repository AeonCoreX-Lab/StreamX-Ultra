package com.aeoncorex.streamx.ui.movie

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.aeoncorex.streamx.streaming.ProxyKind
import kotlinx.coroutines.launch

// ═══════════════════════════════════════════════════════════════════════════
//  MovieSettingsScreen — Network & Sources
//
//  REMOVED: "External Player", "Auto-Play Next", "Default Quality", and
//  "Server Region" toggles. All four were backed by MoviePreferences, a
//  plain in-memory `mutableStateOf` object with no persistence (reset on
//  every app restart) and, on searching the rest of the codebase, no
//  reader anywhere else - toggling them changed the UI but had zero
//  effect on actual playback. "Server Region" additionally had an empty
//  onClick {} - it never did anything even within this screen.
//
//  REPLACED WITH: Proxy settings (HTTP/SOCKS4/SOCKS5), backed by
//  ProxySettingsStore (MMKV, encrypted, and actually wired into
//  IndexerNative's Rust-side proxy - see indexer/proxy/mod.rs). This is
//  a real, working feature: saving here immediately changes which
//  connection every indexer search uses.
//
//  Also added a placeholder section for the planned Private Tracker
//  support, so the screen's layout already has a home for it once that
//  feature is built.
// ═══════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MovieSettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val savedProxy = remember { ProxySettingsStore.get() }

    var proxyEnabled by remember { mutableStateOf(savedProxy != null) }
    var proxyKind by remember { mutableStateOf(savedProxy?.kind ?: ProxyKind.SOCKS5) }
    var proxyHost by remember { mutableStateOf(savedProxy?.host ?: "") }
    var proxyPort by remember { mutableStateOf(savedProxy?.port?.takeIf { it > 0 }?.toString() ?: "") }
    var proxyUsername by remember { mutableStateOf(savedProxy?.username ?: "") }
    var proxyPassword by remember { mutableStateOf(savedProxy?.password ?: "") }

    var saveMessage by remember { mutableStateOf<String?>(null) }
    var saveSucceeded by remember { mutableStateOf(true) }

    var trackers by remember { mutableStateOf(PrivateTrackerStore.getAll()) }
    var newTrackerName by remember { mutableStateOf("") }
    var newTrackerUrl by remember { mutableStateOf("") }
    var newTrackerApiKey by remember { mutableStateOf("") }
    var trackerAddMessage by remember { mutableStateOf<String?>(null) }

    // ── Built-in private trackers (HD-Torrents, MySpleen, TorrentBD, …) ──
    // Separate from the Torznab list above: these are sites the indexer
    // registry itself knows how to scrape (see sources/private/*.json in
    // streamx-torrent-indexer) — logging in here makes that specific
    // site's results start appearing in every search automatically,
    // with no per-tracker API key to manage. Loaded on first open AND
    // re-loaded every time this screen resumes (e.g. coming back from
    // TrackerLoginScreen after a successful login), via the standard
    // ON_RESUME lifecycle observer — a plain LaunchedEffect(Unit) alone
    // would only run once and never pick up a login that just happened.
    var builtInTrackers by remember { mutableStateOf<List<com.aeoncorex.streamx.streaming.PrivateTrackerListing>>(emptyList()) }
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                coroutineScope.launch {
                    builtInTrackers = com.aeoncorex.streamx.streaming.IndexerNative.listPrivateTrackers()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = { Text("NETWORK & SOURCES", color = Color.White, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, null, tint = Color.Cyan)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0F0F15), Color.Black))))

            LazyColumn(contentPadding = PaddingValues(16.dp)) {
                item { SectionHeader("PROXY") }
                item {
                    Text(
                        "Route indexer searches through your own HTTP/SOCKS proxy - useful if a " +
                            "torrent site is blocked on your network. This does not affect torrent " +
                            "download traffic, only the search step.",
                        color = Color.Gray,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                item {
                    SwitchSettingItem(
                        "Enable Proxy",
                        if (proxyEnabled) "Indexer searches will use the proxy below" else "Indexer searches connect directly",
                        proxyEnabled
                    ) { proxyEnabled = it }
                }

                if (proxyEnabled) {
                    item { ProxyKindSelector(selected = proxyKind, onSelect = { proxyKind = it }) }
                    item {
                        TextFieldItem(
                            label = "Host",
                            value = proxyHost,
                            placeholder = "e.g. 12.34.56.78",
                            onValueChange = { proxyHost = it }
                        )
                    }
                    item {
                        TextFieldItem(
                            label = "Port",
                            value = proxyPort,
                            placeholder = "e.g. 1080",
                            keyboardType = KeyboardType.Number,
                            onValueChange = { input -> proxyPort = input.filter { it.isDigit() }.take(5) }
                        )
                    }
                    item {
                        TextFieldItem(
                            label = "Username (optional)",
                            value = proxyUsername,
                            placeholder = "leave blank if not needed",
                            onValueChange = { proxyUsername = it }
                        )
                    }
                    item {
                        TextFieldItem(
                            label = "Password (optional)",
                            value = proxyPassword,
                            placeholder = "leave blank if not needed",
                            isPassword = true,
                            onValueChange = { proxyPassword = it }
                        )
                    }
                }

                item {
                    Button(
                        onClick = {
                            val port = proxyPort.toIntOrNull() ?: 0
                            if (proxyEnabled && (proxyHost.isBlank() || port <= 0)) {
                                saveMessage = "Host and a valid port are required"
                                saveSucceeded = false
                                return@Button
                            }
                            val ok = ProxySettingsStore.save(
                                ProxySettingsStore.ProxySettings(
                                    enabled = proxyEnabled,
                                    kind = proxyKind,
                                    host = proxyHost.trim(),
                                    port = port,
                                    username = proxyUsername.trim(),
                                    password = proxyPassword
                                ),
                                context = context
                            )
                            saveSucceeded = ok
                            saveMessage = when {
                                !proxyEnabled -> "Proxy disabled - searches will connect directly"
                                ok -> "Proxy saved and active"
                                else -> "Saved, but the proxy could not be activated - check host/port"
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Cyan, contentColor = Color.Black)
                    ) {
                        Text("Save", fontWeight = FontWeight.Bold)
                    }
                }

                saveMessage?.let { msg ->
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (saveSucceeded) Icons.Default.CheckCircle else Icons.Default.Info,
                                null,
                                tint = if (saveSucceeded) Color(0xFF4CAF50) else Color(0xFFFF9800),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(msg, color = Color.Gray, fontSize = 12.sp)
                        }
                    }
                }

                item { SectionHeader("PRIVATE TRACKERS (BUILT-IN)") }
                item {
                    Text(
                        "Log in once to a supported private tracker and its results appear in every search automatically — no API key to find or paste. You log in on the tracker's own real page; this app only detects when it succeeds and remembers your session.",
                        color = Color.Gray,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                if (builtInTrackers.isEmpty()) {
                    item {
                        Text(
                            "No built-in private trackers available right now.",
                            color = Color.DarkGray,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
                        )
                    }
                } else {
                    items(builtInTrackers, key = { it.id }) { site ->
                        val credential = remember(site.id, builtInTrackers) {
                            com.aeoncorex.streamx.streaming.PrivateTrackerCookieStore.get(site.id)
                        }
                        BuiltInTrackerRow(
                            site = site,
                            isLoggedIn = credential != null,
                            lastVerifiedOk = credential?.lastVerifiedOk,
                            onLogin = {
                                val enc = { s: String -> java.net.URLEncoder.encode(s, "UTF-8") }
                                navController.navigate(
                                    "tracker_login/${enc(site.id)}/${enc(site.displayName)}/${enc(site.baseUrl)}" +
                                        "?instructions=${enc(site.instructions)}" +
                                        "&loginCheckPath=${enc(site.loginCheckPath ?: "")}" +
                                        "&loginCheckSelector=${enc(site.loginCheckSelector ?: "")}"
                                )
                            },
                            onLogout = {
                                com.aeoncorex.streamx.streaming.PrivateTrackerCookieStore.remove(site.id)
                                // Forces the `remember(site.id, builtInTrackers)` above to
                                // re-read the store — reassigning to the same list
                                // reference wouldn't recompute, so this rebuilds the
                                // list to trigger recomposition.
                                builtInTrackers = builtInTrackers.toList()
                            }
                        )
                    }
                }

                item { SectionHeader("PRIVATE TRACKERS (TORZNAB)") }
                item {
                    Text(
                        "For any OTHER private tracker not listed above: add its Torznab-compatible search API (the same convention Jackett/Prowlarr/Sonarr use — most private trackers expose one, often visible in their own API/Torznab settings page). Your API key stays encrypted on this device.",
                        color = Color.Gray,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                items(trackers, key = { it.id }) { tracker ->
                    PrivateTrackerRow(
                        tracker = tracker,
                        onToggle = { enabled ->
                            PrivateTrackerStore.setEnabled(tracker.id, enabled, context)
                            trackers = PrivateTrackerStore.getAll()
                        },
                        onDelete = {
                            PrivateTrackerStore.remove(tracker.id, context)
                            trackers = PrivateTrackerStore.getAll()
                        }
                    )
                }

                item {
                    Box(
                        modifier = Modifier.fillMaxWidth()
                            .padding(vertical = 6.dp)
                            .background(Color(0xFF1A1A1A), RoundedCornerShape(12.dp))
                            .padding(16.dp)
                    ) {
                        Column {
                            Text("Add tracker", color = Color.White, fontWeight = FontWeight.SemiBold)

                            TextFieldItem(
                                label = "Name",
                                value = newTrackerName,
                                placeholder = "e.g. MyTracker",
                                onValueChange = { newTrackerName = it }
                            )
                            TextFieldItem(
                                label = "Torznab base URL",
                                value = newTrackerUrl,
                                placeholder = "e.g. https://tracker.example/api/v1/torznab",
                                onValueChange = { newTrackerUrl = it }
                            )
                            TextFieldItem(
                                label = "API key",
                                value = newTrackerApiKey,
                                placeholder = "from the tracker's own API/Torznab settings page",
                                isPassword = true,
                                onValueChange = { newTrackerApiKey = it }
                            )

                            Button(
                                onClick = {
                                    if (newTrackerName.isBlank() || newTrackerUrl.isBlank() || newTrackerApiKey.isBlank()) {
                                        trackerAddMessage = "Name, URL, and API key are all required"
                                        return@Button
                                    }
                                    if (!newTrackerUrl.startsWith("http://") && !newTrackerUrl.startsWith("https://")) {
                                        trackerAddMessage = "URL must start with http:// or https://"
                                        return@Button
                                    }
                                    PrivateTrackerStore.add(newTrackerName, newTrackerUrl, newTrackerApiKey, context)
                                    trackers = PrivateTrackerStore.getAll()
                                    newTrackerName = ""
                                    newTrackerUrl = ""
                                    newTrackerApiKey = ""
                                    trackerAddMessage = "Tracker added"
                                },
                                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Cyan, contentColor = Color.Black)
                            ) {
                                Text("Add", fontWeight = FontWeight.Bold)
                            }

                            trackerAddMessage?.let { msg ->
                                Text(msg, color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(title, color = Color.Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp, start = 8.dp))
}

@Composable
fun SwitchSettingItem(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).background(Color(0xFF1A1A1A), RoundedCornerShape(12.dp)).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Color.Gray, fontSize = 12.sp)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = Color.Cyan))
    }
}

/**
 * One configured private tracker in the list — name + enable toggle +
 * delete, following [SwitchSettingItem]'s same card layout so the list
 * reads consistently with the rest of this screen. The API key itself
 * is deliberately never shown here (not even masked) — there's no
 * "edit" flow, only add/remove, so there's no UI moment that would need
 * to display it after the initial add.
 */
@Composable
private fun PrivateTrackerRow(
    tracker: PrivateTracker,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).background(Color(0xFF1A1A1A), RoundedCornerShape(12.dp)).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f)) {
            Text(tracker.name, color = Color.White, fontWeight = FontWeight.SemiBold)
            Text(tracker.baseUrl, color = Color.Gray, fontSize = 12.sp)
        }
        Switch(checked = tracker.enabled, onCheckedChange = onToggle, colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = Color.Cyan))
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "Remove tracker", tint = Color.Gray)
        }
    }
}

/**
 * One built-in, auth-required registry tracker (HD-Torrents, MySpleen,
 * TorrentBD, …) — shows login state and a Login/Logout action. Distinct
 * from [PrivateTrackerRow] (the Torznab list) since these have no API
 * key to display or delete-vs-disable choice — just "logged in" or not.
 */
@Composable
private fun BuiltInTrackerRow(
    site: com.aeoncorex.streamx.streaming.PrivateTrackerListing,
    isLoggedIn: Boolean,
    lastVerifiedOk: Boolean?,
    onLogin: () -> Unit,
    onLogout: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).background(Color(0xFF1A1A1A), RoundedCornerShape(12.dp)).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f)) {
            Text(site.displayName, color = Color.White, fontWeight = FontWeight.SemiBold)
            val statusText = when {
                !isLoggedIn -> "Not logged in"
                lastVerifiedOk == false -> "Session expired — please log in again"
                else -> "Logged in"
            }
            val statusColor = when {
                !isLoggedIn -> Color.Gray
                lastVerifiedOk == false -> Color(0xFFFF9800)
                else -> Color(0xFF4CAF50)
            }
            Text(statusText, color = statusColor, fontSize = 12.sp)
        }
        if (isLoggedIn) {
            TextButton(onClick = onLogout) {
                Text("Log out", color = Color.Gray, fontSize = 13.sp)
            }
        } else {
            Button(
                onClick = onLogin,
                colors = ButtonDefaults.buttonColors(containerColor = Color.Cyan, contentColor = Color.Black),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Text("Log in", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun ProxyKindSelector(selected: ProxyKind, onSelect: (ProxyKind) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ProxyKind.entries.forEach { kind ->
            val isSelected = kind == selected
            Box(
                modifier = Modifier.weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isSelected) Color.Cyan else Color(0xFF1A1A1A))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onSelect(kind) }
                    )
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    kind.name,
                    color = if (isSelected) Color.Black else Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp
                )
            }
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun TextFieldItem(
    label: String,
    value: String,
    placeholder: String,
    isPassword: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    onValueChange: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp, start = 4.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder, color = Color.DarkGray) },
            singleLine = true,
            visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = Color.Cyan,
                unfocusedBorderColor = Color.DarkGray,
                cursorColor = Color.Cyan
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}
