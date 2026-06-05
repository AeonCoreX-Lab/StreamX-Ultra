package com.aeoncorex.streamx.ui.addons

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.aeoncorex.streamx.streaming.AddonDescriptor
import com.aeoncorex.streamx.streaming.AddonInfo
import com.aeoncorex.streamx.streaming.AddonManager
import com.aeoncorex.streamx.streaming.AddonSource
import com.aeoncorex.streamx.streaming.AddonStorage
import com.aeoncorex.streamx.streaming.registry.AddonRegistry
import kotlinx.coroutines.launch

private val BG      = Color(0xFF06060F)
private val CARD    = Color(0xFF0E0E18)
private val ACCENT  = Color(0xFF82B1FF)
private val GREEN   = Color(0xFF81C784)
private val RED     = Color(0xFFEF9A9A)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddonScreen(
    navController:  NavController,
    autoInstallUrl: String? = null   // from streamx://install-addon deeplink
) {
    val scope = rememberCoroutineScope()

    var sources    by remember { mutableStateOf(AddonManager.getSources()) }
    var available  by remember { mutableStateOf<List<AddonInfo>>(emptyList()) }
    var installed  by remember { mutableStateOf(AddonManager.getInstalled()) }
    var httpAddons by remember { mutableStateOf(AddonStorage.getHttpAddons()) }
    var loading    by remember { mutableStateOf(false) }
    var error      by remember { mutableStateOf<String?>(null) }
    var tab        by remember { mutableIntStateOf(0) }
    var showAddSrc by remember { mutableStateOf(false) }
    var showAddUrl by remember { mutableStateOf(false) }
    var autoUrl    by remember { mutableStateOf(autoInstallUrl) }  // pre-fill from deeplink

    fun refreshLists() {
        installed  = AddonManager.getInstalled()
        httpAddons = AddonStorage.getHttpAddons()
        sources    = AddonManager.getSources()
    }

    // Load manifest on open
    LaunchedEffect(Unit) {
        loading = true
        runCatching { AddonManager.fetchManifest() }
            .onSuccess  { available = it; refreshLists() }
            .onFailure  { error = it.message }
        loading = false
    }

    // Auto-open install dialog if deeplink delivered a URL
    LaunchedEffect(autoInstallUrl) {
        if (!autoInstallUrl.isNullOrEmpty()) {
            tab        = 2           // switch to HTTP tab
            autoUrl    = autoInstallUrl
            showAddUrl = true        // open dialog pre-filled
        }
    }

    fun refresh(force: Boolean = false) = scope.launch {
        loading = true; error = null
        runCatching { AddonManager.fetchManifest(force = force) }
            .onSuccess  { available = it; refreshLists() }
            .onFailure  { error = it.message }
        loading = false
    }

    Column(Modifier.fillMaxSize().background(BG)) {

        // ── Top bar ───────────────────────────────────────────────────────────
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(Icons.Default.ArrowBack, "Back", tint = Color.White)
            }
            Text("Addons", color = Color.White, fontSize = 18.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = { showAddSrc = true }) {
                Icon(Icons.Rounded.AddCircle, "Add repo", tint = ACCENT)
            }
            IconButton(onClick = { autoUrl = null; showAddUrl = true }) {
                Icon(Icons.Rounded.Link, "Add by URL", tint = GREEN)
            }
            IconButton(onClick = { refresh(true) }) {
                Icon(Icons.Rounded.Refresh, "Refresh", tint = Color.Gray)
            }
        }

        // ── Source chips ──────────────────────────────────────────────────────
        if (sources.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                sources.forEach { src ->
                    SourceChip(src,
                        onSetDefault = {
                            AddonManager.setDefaultSource(src.author)
                            sources = AddonManager.getSources()
                            refresh(true)
                        },
                        onRemove = {
                            AddonManager.removeSource(src.author)
                            sources = AddonManager.getSources()
                        }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // ── Tabs ──────────────────────────────────────────────────────────────
        TabRow(selectedTabIndex = tab, containerColor = CARD, contentColor = Color.White) {
            Tab(tab == 0, { tab = 0 }, text = { Text("Browse (${available.size})") })
            Tab(tab == 1, { tab = 1 }, text = { Text("Installed (${installed.size + httpAddons.size})") })
            Tab(tab == 2, { tab = 2 }, text = { Text("HTTP / Stremio") })
        }

        // ── Error ─────────────────────────────────────────────────────────────
        if (error != null) {
            Row(Modifier.fillMaxWidth().padding(16.dp)
                .background(Color(0xFF3A1010), RoundedCornerShape(8.dp)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Error, null, tint = Color(0xFFEF5350), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(error ?: "", color = RED, fontSize = 12.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = { error = null }, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Rounded.Close, null, tint = Color.Gray, modifier = Modifier.size(16.dp))
                }
            }
        }

        if (loading) {
            Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) {
                CircularProgressIndicator(color = ACCENT, modifier = Modifier.size(32.dp))
            }
            return@Column
        }

        // ── Tab content ───────────────────────────────────────────────────────
        when (tab) {
            0 -> BrowseTab(available, installed,
                onInstall   = { addon ->
                    scope.launch {
                        runCatching { AddonManager.install(addon) }
                            .onFailure { error = "Install failed: ${it.message}" }
                        refreshLists()
                        available = available.map { a ->
                            if (a.value == addon.value) a.copy(installed = true) else a
                        }
                    }
                },
                onUninstall = { addon ->
                    AddonManager.uninstall(addon.value, addon.sourceAuthor)
                    refreshLists()
                    available = available.map { a ->
                        if (a.value == addon.value) a.copy(installed = false) else a
                    }
                }
            )
            1 -> InstalledTab(installed, httpAddons,
                onUninstallBundle = { addon ->
                    AddonManager.uninstall(addon.value, addon.sourceAuthor)
                    refreshLists()
                },
                onUninstallHttp  = { desc ->
                    AddonStorage.removeHttpAddon(desc.manifest.id)
                    refreshLists()
                }
            )
            2 -> HttpAddonsTab(httpAddons,
                onAddByUrl = { autoUrl = null; showAddUrl = true },
                onRemove   = { desc -> AddonStorage.removeHttpAddon(desc.manifest.id); refreshLists() }
            )
        }
    }

    // ── Add bundle source dialog ──────────────────────────────────────────────
    if (showAddSrc) {
        AddSourceDialog(
            onDismiss = { showAddSrc = false },
            onConfirm = { author, url ->
                AddonManager.addSource(author, url)
                sources = AddonManager.getSources()
                showAddSrc = false
                refresh(true)
            }
        )
    }

    // ── Add HTTP addon by URL dialog (also opened by deeplink) ────────────────
    if (showAddUrl) {
        AddByUrlDialog(
            initialUrl = autoUrl ?: "",       // pre-fill if from deeplink
            onDismiss  = { showAddUrl = false; autoUrl = null },
            onConfirm  = { url ->
                scope.launch {
                    showAddUrl = false; autoUrl = null
                    loading = true
                    runCatching { AddonRegistry.installByUrl(url) }
                        .onSuccess { desc ->
                            AddonStorage.saveHttpAddon(desc)
                            refreshLists()
                            tab   = 1   // switch to Installed tab
                            error = null
                        }
                        .onFailure { error = "Failed: ${it.message}" }
                    loading = false
                }
            }
        )
    }
}

// ── Tab composables ───────────────────────────────────────────────────────────

@Composable
private fun BrowseTab(
    available:   List<AddonInfo>,
    installed:   List<AddonInfo>,
    onInstall:   (AddonInfo) -> Unit,
    onUninstall: (AddonInfo) -> Unit
) {
    if (available.isEmpty()) {
        EmptyState("No addons found.\nAdd a source first.", Icons.Rounded.ExtensionOff)
        return
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(available, key = { it.value + it.sourceAuthor }) { addon ->
            val isInst = installed.any { it.value == addon.value && it.sourceAuthor == addon.sourceAuthor }
            BundleAddonCard(addon, isInst, { onInstall(addon) }, { onUninstall(addon) })
        }
        item { Spacer(Modifier.height(32.dp)) }
    }
}

@Composable
private fun InstalledTab(
    bundleAddons:      List<AddonInfo>,
    httpAddons:        List<AddonDescriptor>,
    onUninstallBundle: (AddonInfo) -> Unit,
    onUninstallHttp:   (AddonDescriptor) -> Unit
) {
    if (bundleAddons.isEmpty() && httpAddons.isEmpty()) {
        EmptyState("No addons installed yet.", Icons.Rounded.Extension)
        return
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (bundleAddons.isNotEmpty()) {
            item { SectionLabel("Bundle Addons (${bundleAddons.size})") }
            items(bundleAddons, key = { it.value + it.sourceAuthor }) { addon ->
                BundleAddonCard(addon, true, {}, { onUninstallBundle(addon) })
            }
        }
        if (httpAddons.isNotEmpty()) {
            item { SectionLabel("HTTP / Stremio Addons (${httpAddons.size})") }
            items(httpAddons, key = { it.manifest.id }) { desc ->
                HttpAddonCard(desc) { onUninstallHttp(desc) }
            }
        }
        item { Spacer(Modifier.height(32.dp)) }
    }
}

@Composable
private fun HttpAddonsTab(
    httpAddons: List<AddonDescriptor>,
    onAddByUrl: () -> Unit,
    onRemove:   (AddonDescriptor) -> Unit
) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(Modifier.fillMaxWidth()
                .background(Color(0xFF0D1B0D), RoundedCornerShape(10.dp))
                .border(1.dp, Color(0xFF2E7D32).copy(.5f), RoundedCornerShape(10.dp))
                .padding(12.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Rounded.Info, null, tint = GREEN, modifier = Modifier.size(18.dp))
                Column {
                    Text("Stremio-Compatible Addons", color = GREEN, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text("Any Stremio addon URL works — paste it below or visit addons.streamx.app",
                        color = Color.Gray, fontSize = 11.sp)
                }
            }
        }
        item {
            Button(onClick = onAddByUrl, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0D1B0D)),
                border = androidx.compose.foundation.BorderStroke(1.dp, GREEN.copy(.6f)),
                shape  = RoundedCornerShape(10.dp)) {
                Icon(Icons.Rounded.AddLink, null, tint = GREEN, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Add Addon by URL", color = GREEN)
            }
        }
        if (httpAddons.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(40.dp), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.Link, null, tint = Color.Gray, modifier = Modifier.size(36.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("No HTTP addons yet.\nTap above or visit the catalog website.",
                            color = Color.Gray, fontSize = 13.sp, textAlign = TextAlign.Center)
                    }
                }
            }
        } else {
            items(httpAddons, key = { it.manifest.id }) { desc ->
                HttpAddonCard(desc) { onRemove(desc) }
            }
        }
        item { Spacer(Modifier.height(32.dp)) }
    }
}

// ── Card composables ──────────────────────────────────────────────────────────

@Composable
private fun BundleAddonCard(
    addon: AddonInfo, isInstalled: Boolean,
    onInstall: () -> Unit, onUninstall: () -> Unit
) {
    var busy by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth()
        .background(CARD, RoundedCornerShape(12.dp))
        .border(1.dp, Color.White.copy(.06f), RoundedCornerShape(12.dp))
        .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).background(Color(0xFF1A1A2E), RoundedCornerShape(8.dp)), Alignment.Center) {
            if (addon.icon.isNotEmpty())
                AsyncImage(addon.icon, null, modifier = Modifier.size(36.dp).clip(RoundedCornerShape(6.dp)))
            else Icon(Icons.Rounded.Extension, null, tint = ACCENT, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(addon.displayName, color = Color.White, fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                TypeBadge(addon.type)
            }
            Text("v${addon.version} · ${addon.sourceAuthor}", color = Color.Gray, fontSize = 11.sp)
        }
        Spacer(Modifier.width(8.dp))
        if (busy) {
            CircularProgressIndicator(color = ACCENT, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        } else if (isInstalled) {
            OutlinedButton(onClick = onUninstall,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = RED),
                border = androidx.compose.foundation.BorderStroke(1.dp, RED.copy(.5f)),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier.height(32.dp)) {
                Text("Remove", fontSize = 12.sp)
            }
        } else {
            Button(onClick = { busy = true; onInstall() },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A3A1A)),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier.height(32.dp)) {
                Text("Install", color = GREEN, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun HttpAddonCard(desc: AddonDescriptor, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth()
        .background(CARD, RoundedCornerShape(12.dp))
        .border(1.dp, Color(0xFF1A2E1A), RoundedCornerShape(12.dp))
        .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).background(Color(0xFF0D1B0D), RoundedCornerShape(8.dp)), Alignment.Center) {
            if (!desc.manifest.logo.isNullOrEmpty())
                AsyncImage(desc.manifest.logo, null, modifier = Modifier.size(36.dp).clip(RoundedCornerShape(6.dp)))
            else Icon(Icons.Rounded.Language, null, tint = GREEN, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(desc.manifest.name, color = Color.White, fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box(Modifier.background(Color(0xFF0D1B0D), RoundedCornerShape(4.dp))
                    .padding(horizontal = 5.dp, vertical = 2.dp)) {
                    Text("HTTP", color = GREEN, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
            Text("v${desc.manifest.version} · ${desc.manifest.resources.joinToString(", ")}",
                color = Color.Gray, fontSize = 11.sp)
            Text(desc.transportUrl.removePrefix("https://").take(42),
                color = Color(0xFF2A4A2A), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = onRemove,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = RED),
            border = androidx.compose.foundation.BorderStroke(1.dp, RED.copy(.5f)),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            modifier = Modifier.height(32.dp)) {
            Text("Remove", fontSize = 12.sp)
        }
    }
}

// ── Small composables ─────────────────────────────────────────────────────────

@Composable
private fun SourceChip(source: AddonSource, onSetDefault: () -> Unit, onRemove: () -> Unit) {
    Row(Modifier.background(
            if (source.isDefault) Color(0xFF1A3A1A) else Color(0xFF141420), RoundedCornerShape(20.dp))
        .border(1.dp,
            if (source.isDefault) Color(0xFF4CAF50) else Color.White.copy(.1f), RoundedCornerShape(20.dp))
        .clickable { onSetDefault() }
        .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (source.isDefault) Box(Modifier.size(6.dp).background(Color(0xFF4CAF50), CircleShape))
        Text(source.author, color = Color.White, fontSize = 12.sp)
        Icon(Icons.Rounded.Close, "Remove", tint = Color.Gray,
            modifier = Modifier.size(14.dp).clickable { onRemove() })
    }
}

@Composable
private fun TypeBadge(type: String) {
    val (bg, fg) = when (type.lowercase()) {
        "english" -> Color(0xFF0D1B3E) to ACCENT
        "india"   -> Color(0xFF1B0D0D) to Color(0xFFFF8A65)
        "anime"   -> Color(0xFF1B0D2E) to Color(0xFFCE93D8)
        "drama"   -> Color(0xFF0D1B1B) to Color(0xFF80CBC4)
        else      -> Color(0xFF1A1A2E) to Color.Gray
    }
    Box(Modifier.background(bg, RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 2.dp)) {
        Text(type, color = fg, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = Color.Gray, fontSize = 11.sp,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
}

@Composable
private fun EmptyState(msg: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Box(Modifier.fillMaxWidth().padding(48.dp), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = Color.Gray, modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(8.dp))
            Text(msg, color = Color.Gray, fontSize = 13.sp, textAlign = TextAlign.Center)
        }
    }
}

// ── Dialogs ───────────────────────────────────────────────────────────────────

@Composable
private fun AddSourceDialog(onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var author by remember { mutableStateOf("") }
    var url    by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, containerColor = CARD,
        title = { Text("Add Bundle Source", color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("GitHub Pages URL serving manifest.json + dist/ JS files.",
                    color = Color.Gray, fontSize = 12.sp)
                OutlinedTextField(value = author, onValueChange = { author = it },
                    label = { Text("Author name") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White, unfocusedTextColor = Color.Gray,
                        focusedBorderColor = ACCENT),
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = url, onValueChange = { url = it },
                    label = { Text("GitHub Pages URL") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White, unfocusedTextColor = Color.Gray,
                        focusedBorderColor = ACCENT),
                    modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(onClick = { if (author.isNotBlank() && url.isNotBlank()) onConfirm(author, url) },
                enabled = author.isNotBlank() && url.isNotBlank(),
                colors  = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A3A1A))) {
                Text("Add")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Color.Gray) } }
    )
}

@Composable
private fun AddByUrlDialog(
    initialUrl: String,
    onDismiss:  () -> Unit,
    onConfirm:  (String) -> Unit
) {
    var url by remember { mutableStateOf(initialUrl) }
    AlertDialog(onDismissRequest = onDismiss, containerColor = CARD,
        title = { Text("Add Addon by URL", color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.background(Color(0xFF0D1B0D), RoundedCornerShape(8.dp)).padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment     = Alignment.Top) {
                    Icon(Icons.Rounded.CheckCircle, null, tint = GREEN, modifier = Modifier.size(16.dp))
                    Text("Any Stremio community addon works.\nURL must end with /manifest.json",
                        color = Color.Gray, fontSize = 11.sp)
                }
                OutlinedTextField(value = url, onValueChange = { url = it },
                    label = { Text("Manifest URL") },
                    placeholder = { Text("https://addon.vercel.app/manifest.json", color = Color.Gray) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White, unfocusedTextColor = Color.Gray,
                        focusedBorderColor = GREEN),
                    modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(onClick = { if (url.isNotBlank()) onConfirm(url.trim()) },
                enabled = url.isNotBlank(),
                colors  = ButtonDefaults.buttonColors(containerColor = Color(0xFF0D1B0D)),
                border  = androidx.compose.foundation.BorderStroke(1.dp, GREEN.copy(.6f))) {
                Text("Add", color = GREEN)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Color.Gray) } }
    )
}
