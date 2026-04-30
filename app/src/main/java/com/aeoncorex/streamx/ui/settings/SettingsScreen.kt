package com.aeoncorex.streamx.ui.settings

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.aeoncorex.streamx.ui.premium.PremiumManager
import com.aeoncorex.streamx.ui.home.CyberMeshBackground
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.firestore

private val Purple      = Color(0xFF7C4DFF)
private val PurpleLight = Color(0xFFB388FF)
private val Gold        = Color(0xFFFFD700)
private val Cyan        = Color(0xFF00FFFF)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavController) {

    // ── Real-time premium status ──────────────────────────────────
    val uid       = Firebase.auth.currentUser?.uid
    var isPremium by remember { mutableStateOf<Boolean>(PremiumManager.isPremiumCached()) }

    DisposableEffect(uid) {
        if (uid == null) return@DisposableEffect onDispose {}
        val listener = Firebase.firestore.collection("users").document(uid)
            .addSnapshotListener { snapshot, _ ->
                isPremium = snapshot?.getBoolean("isPremium") ?: false
            }
        onDispose { listener.remove() }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        CyberMeshBackground()
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Text("SYSTEM SETTINGS", color = Cyan,
                            fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                    },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White)
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent)
                )
            }
        ) { padding ->
            LazyColumn(
                contentPadding = padding,
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {

                // ════════════════════════════════════════════════
                //  PREMIUM BANNER — top of settings
                // ════════════════════════════════════════════════
                item {
                    Spacer(Modifier.height(8.dp))
                    if (isPremium) {
                        PremiumActiveBanner()
                    } else {
                        PremiumUpgradeBanner(onTap = { navController.navigate("premium") })
                    }
                    Spacer(Modifier.height(16.dp))
                    // Section label
                    Text("PREFERENCES", color = Color.Gray, fontSize = 10.sp,
                        letterSpacing = 1.5.sp, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
                }

                // ── Settings items ────────────────────────────────
                item { SettingsItem(Icons.Default.InvertColors, "Visual Theme",    "Customize your interface")   { navController.navigate("theme") } }
                item { SettingsItem(Icons.Default.Person,       "User Account",    "Profile and linked services") { navController.navigate("account") } }

                item {
                    Spacer(Modifier.height(12.dp))
                    Text("LEGAL", color = Color.Gray, fontSize = 10.sp,
                        letterSpacing = 1.5.sp, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
                }

                item { SettingsItem(Icons.Default.Copyright, "Copyright Notice", "Legal disclaimers & DMCA")    { navController.navigate("copyright") } }
                item { SettingsItem(Icons.Default.Info,       "About System",     "Build version and core info") { navController.navigate("about") } }
                item { SettingsItem(Icons.Default.Policy,     "Legal Protocols",  "Privacy policy and terms")   { navController.navigate("privacy") } }

                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  Premium Active Banner (settings top)
// ═══════════════════════════════════════════════════════════════════
@Composable
private fun PremiumActiveBanner() {
    val infiniteTransition = rememberInfiniteTransition(label = "glow")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f, targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(1800), RepeatMode.Reverse),
        label = "glow"
    )

    Box(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF1A0E35), Color(0xFF100820))))
            .border(1.dp, Brush.horizontalGradient(listOf(Gold.copy(glowAlpha), Purple.copy(glowAlpha))), RoundedCornerShape(18.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).background(Gold.copy(0.15f), CircleShape)
                    .border(1.dp, Gold.copy(0.5f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.WorkspacePremium, null, tint = Gold, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("StreamX Premium", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier.clip(RoundedCornerShape(5.dp)).background(Color(0xFF1B5E20)).padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text("ACTIVE", color = Color(0xFF69F0AE), fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.8.sp)
                    }
                }
                Spacer(Modifier.height(3.dp))
                Text("All AI features unlocked", color = Gold.copy(0.8f), fontSize = 12.sp)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Color.Gray)
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  Premium Upgrade Banner (settings top, free user)
// ═══════════════════════════════════════════════════════════════════
@Composable
private fun PremiumUpgradeBanner(onTap: () -> Unit) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1.00f, targetValue = 1.015f,
        animationSpec = infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "scale"
    )

    Box(
        Modifier.fillMaxWidth().scale(scale)
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF120830), Color(0xFF0A0618))))
            .border(1.dp, Brush.horizontalGradient(listOf(Purple, PurpleLight)), RoundedCornerShape(18.dp))
            .clickable { onTap() }
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Icon
            Box(
                Modifier.size(44.dp)
                    .background(Brush.radialGradient(listOf(Purple.copy(0.35f), Color.Transparent)), CircleShape)
                    .border(1.dp, PurpleLight.copy(0.5f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.AutoAwesome, null, tint = PurpleLight, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Unlock AI Features", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(3.dp))
                Text("Scene analysis • Voice • Unlimited  —  $4.99/yr", color = PurpleLight.copy(0.8f), fontSize = 11.sp)
            }
            // Arrow
            Box(
                Modifier.clip(RoundedCornerShape(8.dp))
                    .background(Purple.copy(0.3f))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text("GET", color = PurpleLight, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  Settings Item (same style, unchanged)
// ═══════════════════════════════════════════════════════════════════
@Composable
private fun SettingsItem(
    icon:     ImageVector,
    title:    String,
    subtitle: String,
    onClick:  () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(0.05f))
            .border(1.dp, Color.White.copy(0.1f), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Cyan, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title.uppercase(),  color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(subtitle, color = Color.Gray, fontSize = 12.sp)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Color.Gray)
        }
    }
}
