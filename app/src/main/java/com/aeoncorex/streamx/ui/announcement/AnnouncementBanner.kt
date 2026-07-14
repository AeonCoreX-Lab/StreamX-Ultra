package com.aeoncorex.streamx.ui.announcement

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.aeoncorex.streamx.data.FirestoreDb
import com.google.firebase.firestore.Query

data class Announcement(
    val id:          String  = "",
    val title:       String  = "",
    val body:        String  = "",
    val description: String  = "",
    val type:        String  = "info",
    val imageUrl:    String? = null,
    val actionUrl:   String? = null,
    val actionLabel: String  = "Learn More",
    val active:      Boolean = true,
    val expiresAt:   Long?   = null,
    val ts:          Long    = 0L,
)

@Composable
fun AnnouncementBanner() {
    var announcements by remember { mutableStateOf<List<Announcement>>(emptyList()) }
    var dismissedIds  by remember { mutableStateOf<Set<String>>(emptySet()) }
    val now = remember { System.currentTimeMillis() }

    DisposableEffect(Unit) {
        val listener = FirestoreDb.instance
            .collection("announcements")
            .whereEqualTo("active", true)
            .orderBy("ts", Query.Direction.DESCENDING)
            .limit(5)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    android.util.Log.e("AnnouncementBanner", "Firestore listen failed", error)
                    return@addSnapshotListener
                }
                announcements = snapshot?.documents?.mapNotNull { doc ->
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
                    } catch (_: Exception) { null }
                } ?: emptyList()
            }
        onDispose { listener.remove() }
    }

    val visible = announcements.filter { a ->
        a.id !in dismissedIds && (a.expiresAt == null || a.expiresAt > now)
    }.take(3)

    Column(modifier = Modifier.fillMaxWidth()) {
        visible.forEach { a ->
            key(a.id) {
                AnimatedVisibility(
                    visible = true,
                    enter   = expandVertically(spring(Spring.DampingRatioMediumBouncy)) + fadeIn(),
                    exit    = shrinkVertically() + fadeOut(),
                ) {
                    AnnouncementCard(a = a, onDismiss = { dismissedIds = dismissedIds + a.id })
                }
            }
        }
    }
}

@Composable
private fun AnnouncementCard(a: Announcement, onDismiss: () -> Unit) {
    val context = LocalContext.current

    val (borderColor, bgColor, icon) = remember(a.type) {
        when (a.type) {
            "warning" -> Triple(Color(0xFFF59E0B), Color(0x18F59E0B), Icons.Rounded.Warning)
            "success" -> Triple(Color(0xFF10B981), Color(0x1810B981), Icons.Rounded.CheckCircle)
            "urgent"  -> Triple(Color(0xFFF43F5E), Color(0x18F43F5E), Icons.Rounded.NotificationsActive)
            else      -> Triple(Color(0xFF7C3AED), Color(0x187C3AED), Icons.Rounded.Campaign)
        }
    }

    val pulse = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by pulse.animateFloat(
        initialValue  = 0.5f,
        targetValue   = 1f,
        animationSpec = infiniteRepeatable(tween(880), RepeatMode.Reverse),
        label         = "a",
    )

    val hasExtra = a.description.isNotBlank() || !a.imageUrl.isNullOrBlank() || !a.actionUrl.isNullOrBlank()
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(bgColor)
            .border(
                width = if (a.type == "urgent") 1.5.dp else 1.dp,
                brush = Brush.horizontalGradient(listOf(
                    borderColor.copy(if (a.type == "urgent") pulseAlpha else 0.6f),
                    borderColor.copy(0.12f),
                    borderColor.copy(if (a.type == "urgent") pulseAlpha else 0.6f),
                )),
                shape = RoundedCornerShape(14.dp),
            ),
    ) {
        // ── Header row ───────────────────────────────────────────
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (hasExtra) Modifier.clickable { expanded = !expanded } else Modifier)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Icon(icon, null, tint = borderColor, modifier = Modifier.size(18.dp).padding(top = 1.dp))
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                if (a.title.isNotBlank()) {
                    Text(a.title, color = Color.White, fontWeight = FontWeight.Bold,
                        fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (a.body.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(a.body, color = Color.White.copy(0.72f), fontSize = 12.sp,
                        lineHeight = 17.sp,
                        maxLines = if (expanded) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(6.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.Close, "Dismiss", tint = Color.White.copy(0.4f),
                    modifier = Modifier.size(17.dp).clickable { onDismiss() })
                if (hasExtra) {
                    Spacer(Modifier.height(4.dp))
                    Icon(
                        if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        null, tint = borderColor.copy(0.8f), modifier = Modifier.size(17.dp))
                }
            }
        }

        // ── Expanded content ─────────────────────────────────────
        AnimatedVisibility(visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit  = shrinkVertically() + fadeOut()) {
            Column(modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, bottom = 14.dp)) {

                // Banner image
                if (!a.imageUrl.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    AsyncImage(
                        model = a.imageUrl,
                        contentDescription = "Announcement image",
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .clip(RoundedCornerShape(10.dp)),
                    )
                }

                // Full description
                if (a.description.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = borderColor.copy(0.18f), thickness = 0.5.dp)
                    Spacer(Modifier.height(8.dp))
                    Text(a.description, color = Color.White.copy(0.65f),
                        fontSize = 12.sp, lineHeight = 18.sp)
                }

                // Action link
                if (!a.actionUrl.isNullOrBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(borderColor.copy(0.14f))
                            .clickable {
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW,
                                        Uri.parse(a.actionUrl)))
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Icon(Icons.Rounded.OpenInNew, null,
                            tint = borderColor, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(a.actionLabel.ifBlank { "Learn More" },
                            color = borderColor, fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
