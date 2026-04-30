package com.aeoncorex.streamx.ui.main

import android.app.Activity
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.aeoncorex.streamx.ui.account.AccountScreen
import com.aeoncorex.streamx.ui.announcement.AnnouncementBanner
import com.aeoncorex.streamx.ui.home.LiveTVScreen
import com.aeoncorex.streamx.ui.movie.MovieScreen
import com.aeoncorex.streamx.ui.music.MusicManager
import com.aeoncorex.streamx.ui.music.MusicScreen
import com.aeoncorex.streamx.ui.notifications.NotificationsScreen
import com.aeoncorex.streamx.ui.notifications.rememberUnreadCount
import com.aeoncorex.streamx.util.NetworkMonitor

@Composable
fun MainScreen(navController: NavController) {
    var selectedTab     by remember { mutableIntStateOf(0) }
    val primaryColor    = MaterialTheme.colorScheme.primary
    val currentSong     by MusicManager.currentSong.collectAsState()
    val isPlaying       by MusicManager.isPlaying.collectAsState()
    val context         = LocalContext.current

    // ── Global network state ──────────────────────────────────────
    val isOnline by NetworkMonitor.observe(context).collectAsState(initial = true)

    // ── Unread notification count (badge) ─────────────────────────
    val unreadCount by rememberUnreadCount()

    // ── Global offline dialog ─────────────────────────────────────
    if (!isOnline) {
        AlertDialog(
            onDismissRequest = {},
            icon  = { Icon(Icons.Rounded.SignalWifiOff, null, tint = primaryColor) },
            title = { Text("OFFLINE MODE", color = Color.White, fontWeight = FontWeight.Bold) },
            text  = {
                Text(
                    "Neural Uplink Disconnected.\nCheck your internet connection.",
                    color = Color.Gray, fontSize = 14.sp,
                )
            },
            confirmButton = {
                Button(
                    onClick  = {},
                    colors   = ButtonDefaults.buttonColors(containerColor = primaryColor),
                    enabled  = false,
                ) { Text("RECONNECTING…", color = Color.Black) }
            },
            dismissButton = {
                TextButton(onClick = { (context as? Activity)?.finish() }) {
                    Text("EXIT", color = Color.Gray)
                }
            },
            containerColor = Color(0xFF0F0F1A),
            shape          = RoundedCornerShape(20.dp),
        )
    }

    Scaffold(
        containerColor = Color.Black,
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Transparent)
            ) {
                // ── Mini player ───────────────────────────────────
                AnimatedVisibility(
                    visible = currentSong != null,
                    enter   = slideInVertically { it } + fadeIn(),
                    exit    = slideOutVertically { it } + fadeOut(),
                ) {
                    currentSong?.let { song ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(60.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .border(1.dp, primaryColor.copy(0.3f), RoundedCornerShape(16.dp))
                                    .clickable { navController.navigate("music_player") },
                                color          = Color(0xFF1E1E1E).copy(0.9f),
                                tonalElevation = 8.dp,
                            ) {
                                Row(
                                    modifier          = Modifier.fillMaxSize().padding(6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    AsyncImage(
                                        model              = song.coverUrl,
                                        contentDescription = null,
                                        modifier           = Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)),
                                        contentScale       = ContentScale.Crop,
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(song.title, color = Color.White,
                                            fontWeight = FontWeight.Bold, fontSize = 13.sp,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(song.artist, color = primaryColor.copy(0.8f),
                                            fontSize = 11.sp, maxLines = 1)
                                    }
                                    IconButton(onClick = { MusicManager.togglePlayPause() }) {
                                        Icon(
                                            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                            "Play/Pause", tint = primaryColor,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // ── Bottom navigation bar ─────────────────────────
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, bottom = 24.dp, top = 6.dp)
                        .height(70.dp)
                        .shadow(20.dp, RoundedCornerShape(35.dp), spotColor = primaryColor.copy(0.5f))
                        .clip(RoundedCornerShape(35.dp))
                        .background(Color(0xFF0F0F0F).copy(0.95f))
                        .border(
                            width = 1.dp,
                            brush = Brush.horizontalGradient(
                                listOf(primaryColor.copy(0.1f), primaryColor.copy(0.5f), primaryColor.copy(0.1f))
                            ),
                            shape = RoundedCornerShape(35.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        modifier              = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment     = Alignment.CenterVertically,
                    ) {
                        FuturisticNavItem(
                            icon       = Icons.Rounded.Tv,
                            label      = "LIVE TV",
                            isSelected = selectedTab == 0,
                            primary    = primaryColor,
                        ) { selectedTab = 0 }

                        FuturisticNavItem(
                            icon       = Icons.Rounded.Movie,
                            label      = "MOVIES",
                            isSelected = selectedTab == 1,
                            primary    = primaryColor,
                        ) { selectedTab = 1 }

                        FuturisticNavItem(
                            icon       = Icons.Default.MusicNote,
                            label      = "MUSIC",
                            isSelected = selectedTab == 2,
                            primary    = primaryColor,
                        ) { selectedTab = 2 }

                        // ── Notifications tab with badge ──────────
                        FuturisticNavItem(
                            icon       = Icons.Rounded.Notifications,
                            label      = "INBOX",
                            isSelected = selectedTab == 3,
                            primary    = primaryColor,
                            badgeCount = unreadCount,
                        ) { selectedTab = 3 }

                        FuturisticNavItem(
                            icon       = Icons.Default.Person,
                            label      = "PROFILE",
                            isSelected = selectedTab == 4,
                            primary    = primaryColor,
                        ) { selectedTab = 4 }
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = paddingValues.calculateBottomPadding())
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Global announcement banner — shown on all tabs except Notifications
                if (selectedTab != 3) {
                    AnnouncementBanner()
                }

                AnimatedContent(
                    targetState = selectedTab,
                    label       = "TabAnimation",
                    modifier    = Modifier.weight(1f),
                    transitionSpec = {
                        fadeIn(tween(220)) togetherWith fadeOut(tween(180))
                    },
                ) { tab ->
                    when (tab) {
                        0 -> LiveTVScreen(navController)
                        1 -> MovieScreen(navController)
                        2 -> MusicScreen(navController)
                        3 -> NotificationsScreen()
                        4 -> AccountScreen(navController)
                        else -> LiveTVScreen(navController)
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
//  FuturisticNavItem — nav icon with optional unread badge
// ─────────────────────────────────────────────────────────────────
@Composable
fun FuturisticNavItem(
    icon:       ImageVector,
    label:      String,
    isSelected: Boolean,
    primary:    Color,
    badgeCount: Int    = 0,
    onClick:    () -> Unit,
) {
    val scale    by animateFloatAsState(if (isSelected) 1.15f else 1f, label = "scale")
    val glowAlpha by animateFloatAsState(if (isSelected) 0.18f else 0f, label = "glow")

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication        = null,
            ) { onClick() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            // Glow background
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        Brush.radialGradient(listOf(primary.copy(glowAlpha), Color.Transparent)),
                        CircleShape,
                    )
            )
            // Icon
            Icon(
                imageVector        = icon,
                contentDescription = label,
                tint               = if (isSelected) primary else Color.Gray,
                modifier           = Modifier.size(24.dp * scale),
            )
            // Unread badge
            if (badgeCount > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 6.dp, y = (-4).dp)
                        .defaultMinSize(minWidth = 16.dp, minHeight = 16.dp)
                        .background(Color(0xFFF43F5E), CircleShape)
                        .padding(horizontal = 3.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text      = if (badgeCount > 99) "99+" else badgeCount.toString(),
                        color     = Color.White,
                        fontSize  = 9.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        if (isSelected) {
            Box(
                modifier = Modifier
                    .size(4.dp)
                    .background(primary, CircleShape)
                    .shadow(4.dp, CircleShape, spotColor = primary)
            )
        }
    }
}
