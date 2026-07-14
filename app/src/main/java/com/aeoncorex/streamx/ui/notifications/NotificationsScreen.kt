package com.aeoncorex.streamx.ui.notifications

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import coil.compose.AsyncImage
import com.aeoncorex.streamx.data.FirestoreDb
import com.aeoncorex.streamx.ui.announcement.Announcement
import com.google.firebase.firestore.Query
import java.text.SimpleDateFormat
import java.util.*

// ─────────────────────────────────────────────────────────────────
//  NotificationsScreen — Full professional notification inbox
//  • Real-time Firestore listener (all announcements, newest first)
//  • Unread badge tracking (in-memory per session + SharedPrefs)
//  • Expand/collapse each item
//  • Mark all as read
//  • Pull-to-refresh style reload
//  • Action link support
//  • Empty state
// ─────────────────────────────────────────────────────────────────

@Composable
fun NotificationsScreen() {
    val context       = LocalContext.current
    val primaryColor  = Color(0xFF7C3AED)
    val bgColor       = Color(0xFF0A0A12)
    val surfaceColor  = Color(0xFF111120)

    var notifications   by remember { mutableStateOf<List<Announcement>>(emptyList()) }
    var isLoading       by remember { mutableStateOf(true) }
    var expandedId      by remember { mutableStateOf<String?>(null) }

    // Read tracking — persisted in SharedPrefs
    val prefs = remember { context.getSharedPreferences("notif_read", 0) }
    // Use immutable Set<String> so the + operator resolves correctly
    var readIds by remember {
        mutableStateOf<Set<String>>(
            prefs.getStringSet("read_ids", emptySet<String>()) ?: emptySet()
        )
    }

    fun markRead(id: String) {
        val updated = readIds + id
        readIds = updated
        prefs.edit().putStringSet("read_ids", updated).apply()
    }

    fun markAllRead() {
        val allIds: Set<String> = notifications.map { it.id }.toSet()
        readIds = allIds
        prefs.edit().putStringSet("read_ids", allIds).apply()
    }

    var loadError    by remember { mutableStateOf<String?>(null) }

    // Real-time Firestore — all announcements (active + inactive), newest first
    DisposableEffect(Unit) {
        val currentUser = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
        android.util.Log.d("NotificationsScreen", "Attaching listener. authUid=${currentUser?.uid ?: "NULL (not signed in)"}")

        val listener = FirestoreDb.instance
            .collection("announcements")
            .orderBy("ts", Query.Direction.DESCENDING)
            .limit(50)
            .addSnapshotListener { snapshot, error ->
                isLoading = false
                if (error != null) {
                    android.util.Log.e("NotificationsScreen", "Firestore listener error: ${error.code} — ${error.message}", error)
                    loadError = "${error.code}: ${error.message}"
                    notifications = emptyList()
                    return@addSnapshotListener
                }
                loadError = null
                android.util.Log.d("NotificationsScreen", "Snapshot received. docCount=${snapshot?.size() ?: 0}")
                notifications = snapshot?.documents?.mapNotNull { doc ->
                    try {
                        Announcement(
                            id          = doc.id,
                            title       = doc.getString("title")       ?: "",
                            body        = doc.getString("body")        ?: "",
                            description = doc.getString("description") ?: "",
                            type        = doc.getString("type")        ?: "info",
                            imageUrl    = doc.getString("imageUrl"),
                            actionUrl   = doc.getString("actionUrl"),
                            actionLabel = doc.getString("actionLabel") ?: "Learn More",
                            active      = doc.getBoolean("active")     ?: true,
                            expiresAt   = doc.getLong("expiresAt"),
                            ts          = doc.getLong("ts")            ?: 0L,
                        )
                    } catch (e: Exception) {
                        android.util.Log.e("NotificationsScreen", "Failed to parse doc ${doc.id}: ${e.message}")
                        null
                    }
                } ?: emptyList()
            }
        onDispose { listener.remove() }
    }

    val unreadCount: Int = notifications.count { it.id !in readIds }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
    ) {
        // ── Header ───────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(listOf(Color(0xFF0F0F1E), bgColor))
                )
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            text       = "NOTIFICATIONS",
                            color      = Color.White,
                            fontWeight = FontWeight.Black,
                            fontSize   = 22.sp,
                            letterSpacing = 2.sp,
                        )
                        if (unreadCount > 0) {
                            Text(
                                text      = "$unreadCount unread",
                                color     = primaryColor,
                                fontSize  = 12.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        } else {
                            Text(
                                text     = "All caught up",
                                color    = Color.Gray,
                                fontSize = 12.sp,
                            )
                        }
                    }
                    if (unreadCount > 0) {
                        TextButton(
                            onClick = { markAllRead() },
                            colors  = ButtonDefaults.textButtonColors(contentColor = primaryColor),
                        ) {
                            Icon(Icons.Rounded.DoneAll, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Mark all read", fontSize = 12.sp)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                HorizontalDivider(color = primaryColor.copy(0.15f))
            }
        }

        // ── Content ───────────────────────────────────────────────
        when {
            isLoading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = primaryColor, modifier = Modifier.size(36.dp))
                }
            }

            notifications.isEmpty() -> {
                EmptyState(primaryColor)
            }

            else -> {
                LazyColumn(
                    modifier            = Modifier.fillMaxSize(),
                    contentPadding      = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(notifications, key = { it.id }) { notif ->
                        val isRead: Boolean = readIds.contains(notif.id)
                        val isExpanded = expandedId == notif.id

                        NotificationItem(
                            notif      = notif,
                            isRead     = isRead,
                            isExpanded = isExpanded,
                            primaryColor = primaryColor,
                            surfaceColor = surfaceColor,
                            onTap      = {
                                expandedId = if (isExpanded) null else notif.id
                                markRead(notif.id)
                            },
                        )
                    }
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
//  Single notification item
// ─────────────────────────────────────────────────────────────────
@Composable
private fun NotificationItem(
    notif:        Announcement,
    isRead:       Boolean,
    isExpanded:   Boolean,
    primaryColor: Color,
    surfaceColor: Color,
    onTap:        () -> Unit,
) {
    val context = LocalContext.current
    val now     = remember { System.currentTimeMillis() }

    val (accentColor, typeIcon) = remember(notif.type) {
        when (notif.type) {
            "warning" -> Color(0xFFF59E0B) to Icons.Rounded.Warning
            "success" -> Color(0xFF10B981) to Icons.Rounded.CheckCircle
            "urgent"  -> Color(0xFFF43F5E) to Icons.Rounded.NotificationsActive
            else      -> Color(0xFF7C3AED) to Icons.Rounded.Campaign
        }
    }

    val isExpired = notif.expiresAt != null && notif.expiresAt < now
    val hasExtra  = notif.description.isNotBlank() ||
                    !notif.imageUrl.isNullOrBlank() ||
                    !notif.actionUrl.isNullOrBlank()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation   = if (isExpanded) 12.dp else 2.dp,
                shape       = RoundedCornerShape(16.dp),
                spotColor   = accentColor.copy(0.3f),
            )
            .clip(RoundedCornerShape(16.dp))
            .background(surfaceColor)
            .border(
                width = if (!isRead) 1.dp else 0.5.dp,
                color = if (!isRead) accentColor.copy(0.5f) else Color.White.copy(0.05f),
                shape = RoundedCornerShape(16.dp),
            )
            .clickable { onTap() }
    ) {
        // ── Main row ─────────────────────────────────────────────
        Row(
            modifier          = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            // Type icon circle
            Box(
                modifier          = Modifier
                    .size(40.dp)
                    .background(accentColor.copy(0.12f), CircleShape)
                    .border(1.dp, accentColor.copy(0.3f), CircleShape),
                contentAlignment  = Alignment.Center,
            ) {
                Icon(typeIcon, null, tint = accentColor, modifier = Modifier.size(20.dp))
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (!isRead) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .background(accentColor, CircleShape)
                        )
                    }
                    Text(
                        text       = notif.title,
                        color      = if (isRead) Color.White.copy(0.75f) else Color.White,
                        fontWeight = if (isRead) FontWeight.Medium else FontWeight.Bold,
                        fontSize   = 14.sp,
                        maxLines   = 2,
                        overflow   = TextOverflow.Ellipsis,
                    )
                }
                if (notif.body.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text     = notif.body,
                        color    = Color.White.copy(if (isRead) 0.45f else 0.65f),
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        maxLines = if (isExpanded) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment     = Alignment.CenterVertically,
                ) {
                    Text(
                        text     = formatTime(notif.ts),
                        color    = Color.White.copy(0.3f),
                        fontSize = 10.sp,
                    )
                    if (isExpired) {
                        Text("Expired", color = Color(0xFFF43F5E).copy(0.7f), fontSize = 10.sp)
                    }
                    if (!notif.active) {
                        Text("Removed", color = Color.Gray.copy(0.5f), fontSize = 10.sp)
                    }
                    if (!notif.imageUrl.isNullOrBlank()) {
                        Icon(Icons.Rounded.Image, null,
                            tint = Color.White.copy(0.25f), modifier = Modifier.size(11.dp))
                    }
                    if (!notif.actionUrl.isNullOrBlank()) {
                        Icon(Icons.Rounded.Link, null,
                            tint = Color.White.copy(0.25f), modifier = Modifier.size(11.dp))
                    }
                }
            }

            Spacer(Modifier.width(6.dp))
            if (hasExtra) {
                Icon(
                    imageVector        = if (isExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint               = accentColor.copy(0.6f),
                    modifier           = Modifier.size(18.dp).padding(top = 2.dp),
                )
            }
        }

        // ── Expanded detail ───────────────────────────────────────
        AnimatedVisibility(
            visible = isExpanded && hasExtra,
            enter   = expandVertically() + fadeIn(),
            exit    = shrinkVertically() + fadeOut(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
            ) {
                HorizontalDivider(color = accentColor.copy(0.12f))
                Spacer(Modifier.height(10.dp))

                // Banner image
                if (!notif.imageUrl.isNullOrBlank()) {
                    AsyncImage(
                        model              = notif.imageUrl,
                        contentDescription = null,
                        contentScale       = ContentScale.FillWidth,
                        modifier           = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                    Spacer(Modifier.height(10.dp))
                }

                // Full description
                if (notif.description.isNotBlank()) {
                    Text(
                        text       = notif.description,
                        color      = Color.White.copy(0.6f),
                        fontSize   = 12.sp,
                        lineHeight = 19.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                }

                // Action button
                if (!notif.actionUrl.isNullOrBlank()) {
                    Button(
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(notif.actionUrl))
                                )
                            }
                        },
                        colors  = ButtonDefaults.buttonColors(containerColor = accentColor),
                        shape   = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Icon(Icons.Rounded.OpenInNew, null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text     = notif.actionLabel.ifBlank { "Learn More" },
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
//  Empty state
// ─────────────────────────────────────────────────────────────────
@Composable
private fun EmptyState(primaryColor: Color) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val pulse = rememberInfiniteTransition(label = "p")
            val alpha by pulse.animateFloat(0.3f, 0.7f,
                infiniteRepeatable(tween(1200), RepeatMode.Reverse), "a")
            Icon(
                Icons.Rounded.NotificationsNone,
                null,
                tint     = primaryColor.copy(alpha),
                modifier = Modifier.size(72.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text("No notifications yet", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(6.dp))
            Text("Announcements from StreamX\nwill appear here.", color = Color.Gray,
                fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                lineHeight = 20.sp)
        }
    }
}

// ─────────────────────────────────────────────────────────────────
//  Time formatter
// ─────────────────────────────────────────────────────────────────
private fun formatTime(ts: Long): String {
    if (ts == 0L) return ""
    val diff = System.currentTimeMillis() - ts
    return when {
        diff < 60_000L              -> "Just now"
        diff < 3_600_000L           -> "${diff / 60_000}m ago"
        diff < 86_400_000L          -> "${diff / 3_600_000}h ago"
        diff < 604_800_000L         -> "${diff / 86_400_000}d ago"
        else -> SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(ts))
    }
}

// ─────────────────────────────────────────────────────────────────
//  Unread count helper — call from MainScreen to show badge
// ─────────────────────────────────────────────────────────────────
@Composable
fun rememberUnreadCount(): State<Int> {
    val context = LocalContext.current
    val prefs   = remember { context.getSharedPreferences("notif_read", 0) }
    var count   by remember { mutableIntStateOf(0) }

    DisposableEffect(Unit) {
        val listener = FirestoreDb.instance
            .collection("announcements")
            .whereEqualTo("active", true)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    android.util.Log.e("NotificationsScreen", "Unread count listener error", error)
                    return@addSnapshotListener
                }
                val readIds = prefs.getStringSet("read_ids", emptySet()) ?: emptySet()
                count = snapshot?.documents?.count { doc -> !readIds.contains(doc.id) } ?: 0
            }
        onDispose { listener.remove() }
    }

    return rememberUpdatedState(count)
}
