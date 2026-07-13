package com.aeoncorex.streamx.ui.movie

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
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

                item { SectionHeader("PRIVATE TRACKERS") }
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth()
                            .padding(vertical = 6.dp)
                            .background(Color(0xFF1A1A1A), RoundedCornerShape(12.dp))
                            .padding(16.dp)
                    ) {
                        Column {
                            Text("Coming soon", color = Color.White, fontWeight = FontWeight.SemiBold)
                            Text(
                                "Add your own private-tracker login to include it in searches. " +
                                    "Your credentials will stay on this device only.",
                                color = Color.Gray,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 4.dp)
                            )
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
