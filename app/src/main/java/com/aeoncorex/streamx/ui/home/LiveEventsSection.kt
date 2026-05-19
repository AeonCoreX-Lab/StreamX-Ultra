package com.aeoncorex.streamx.ui.home

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SportsScore
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.navigation.NavController
import com.aeoncorex.streamx.data.EventRepository
import com.aeoncorex.streamx.model.EventStream
import com.aeoncorex.streamx.model.LiveEvent
import com.valentinilk.shimmer.shimmer
import kotlinx.coroutines.launch
import java.net.URLEncoder
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext

// ─── Sport colour helper ─────────────────────────────────────────────────────
fun sportColor(hex: String): Color = try {
    Color(android.graphics.Color.parseColor(hex))
} catch (_: Exception) {
    Color(0xFFE53935)
}

// ════════════════════════════════════════════════════════════════════════════
//  MAIN SECTION COMPOSABLE
// ════════════════════════════════════════════════════════════════════════════

@Composable
fun LiveEventsSection(
    navController: NavController,
    onRefreshRequested: (() -> Unit)? = null
) {
    val scope   = rememberCoroutineScope()
    val context = LocalContext.current

    val primaryColor   = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary

    var events       by remember { mutableStateOf<List<LiveEvent>>(emptyList()) }
    var isLoading    by remember { mutableStateOf(true) }
    var showDialog   by remember { mutableStateOf(false) }
    var selectedEvent by remember { mutableStateOf<LiveEvent?>(null) }

    // Fetch events on first composition
    LaunchedEffect(Unit) {
        scope.launch {
            try {
                events    = EventRepository.getActiveEvents()
                isLoading = false
            } catch (_: Exception) {
                isLoading = false
            }
        }
    }

    // ── Hide section entirely if no events ───────────────────────────────
    if (!isLoading && events.isEmpty()) return

    Column(modifier = Modifier.fillMaxWidth()) {

        // ── Section Header ────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.LiveTv,
                contentDescription = null,
                tint = Color.Red,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "LIVE EVENTS",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                letterSpacing = 1.5.sp
            )
            Spacer(Modifier.weight(1f))
            if (events.isNotEmpty()) {
                // Live count badge
                val liveCount = events.count { it.isLive }
                if (liveCount > 0) {
                    LiveCountBadge(count = liveCount, primaryColor = primaryColor)
                }
            }
        }

        // ── Loading shimmer ──────────────────────────────────────────────
        if (isLoading) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(vertical = 8.dp)
            ) {
                items(3) {
                    Box(
                        Modifier
                            .width(180.dp)
                            .height(120.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .shimmer()
                            .background(Color.DarkGray)
                    )
                }
            }
            return@Column
        }

        // ── Horizontal Events List ───────────────────────────────────────
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(events, key = { it.eventId }) { event ->
                LiveEventCard(
                    event      = event,
                    accentColor = primaryColor,
                    onClick    = {
                        selectedEvent = event
                        showDialog    = true
                    }
                )
            }
        }

        HorizontalDivider(
            color    = Color.White.copy(0.08f),
            modifier = Modifier.padding(top = 4.dp)
        )
    }

    // ── Stream-picker dialog ─────────────────────────────────────────────
    if (showDialog && selectedEvent != null) {
        EventStreamDialog(
            event     = selectedEvent!!,
            onDismiss = { showDialog = false },
            onStreamSelected = { stream ->
                showDialog = false
                try {
                    val encoded = URLEncoder.encode(stream.url, "UTF-8")
                    navController.navigate("player/$encoded")
                } catch (_: Exception) {
                    Toast.makeText(context, "Invalid stream URL", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  EVENT CARD
// ════════════════════════════════════════════════════════════════════════════

@Composable
fun LiveEventCard(
    event:       LiveEvent,
    accentColor: Color,
    onClick:     () -> Unit
) {
    val sportAccent  = sportColor(event.sportColor)
    val cardBg       = Color(0xFF1A1A24)

    Card(
        modifier = Modifier
            .width(190.dp)
            .height(130.dp)
            .clickable(onClick = onClick),
        shape  = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        elevation = CardDefaults.cardElevation(4.dp)
    ) {
        Box(Modifier.fillMaxSize()) {

            // Gradient overlay — sport colour tint at top
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                sportAccent.copy(0.25f),
                                Color.Transparent,
                                Color.Black.copy(0.5f)
                            )
                        )
                    )
            )

            // Left sport colour accent bar
            Box(
                Modifier
                    .fillMaxHeight()
                    .width(4.dp)
                    .background(sportAccent)
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 12.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // ── Top row: LIVE badge + sport ──────────────────────────
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (event.isLive) {
                        BlinkingLiveDot()
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        event.sport.uppercase(),
                        color     = sportAccent,
                        fontSize  = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }

                // ── Title ────────────────────────────────────────────────
                Text(
                    event.title,
                    color      = Color.White,
                    fontSize   = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines   = 2,
                    overflow   = TextOverflow.Ellipsis,
                    lineHeight = 17.sp
                )

                // ── Bottom row: server count + play button ───────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "${event.streams.size} SERVER${if (event.streams.size > 1) "S" else ""}",
                        color     = Color.Gray,
                        fontSize  = 9.sp,
                        letterSpacing = 0.5.sp
                    )

                    // Play button
                    Box(
                        Modifier
                            .size(28.dp)
                            .background(sportAccent.copy(0.9f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = "Watch",
                            tint   = Color.Black,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  BLINKING LIVE DOT
// ════════════════════════════════════════════════════════════════════════════

@Composable
fun BlinkingLiveDot() {
    val infiniteTransition = rememberInfiniteTransition(label = "dot")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue  = 0.2f,
        animationSpec = infiniteRepeatable(
            tween(600, easing = LinearEasing),
            RepeatMode.Reverse
        ),
        label = "blink"
    )
    Box(
        Modifier
            .size(7.dp)
            .background(Color.Red.copy(alpha = alpha), CircleShape)
    )
}

// ════════════════════════════════════════════════════════════════════════════
//  LIVE COUNT BADGE
// ════════════════════════════════════════════════════════════════════════════

@Composable
fun LiveCountBadge(count: Int, primaryColor: Color) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Red.copy(0.15f))
            .border(1.dp, Color.Red.copy(0.4f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BlinkingLiveDot()
        Spacer(Modifier.width(5.dp))
        Text(
            "$count LIVE",
            color     = Color.Red,
            fontSize  = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp
        )
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  STREAM-PICKER DIALOG (HD Streamz style multi-server)
// ════════════════════════════════════════════════════════════════════════════

@Composable
fun EventStreamDialog(
    event:            LiveEvent,
    onDismiss:        () -> Unit,
    onStreamSelected: (EventStream) -> Unit
) {
    val sportAccent = sportColor(event.sportColor)
    val surfaceColor = MaterialTheme.colorScheme.surface

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape  = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF121218)),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, sportAccent.copy(0.4f), RoundedCornerShape(20.dp))
        ) {
            Column(Modifier.padding(20.dp)) {

                // ── Dialog header ────────────────────────────────────────
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (event.isLive) {
                        BlinkingLiveDot()
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        event.sport.uppercase(),
                        color     = sportAccent,
                        fontSize  = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    event.title,
                    color      = Color.White,
                    fontSize   = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines   = 2
                )

                Spacer(Modifier.height(4.dp))
                Text(
                    "SELECT SERVER TO WATCH",
                    color     = Color.Gray,
                    fontSize  = 10.sp,
                    letterSpacing = 1.sp
                )

                HorizontalDivider(
                    color    = Color.White.copy(0.1f),
                    modifier = Modifier.padding(vertical = 12.dp)
                )

                // ── Server list ──────────────────────────────────────────
                event.streams.forEachIndexed { index, stream ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF1E1E2A))
                            .border(1.dp, Color.White.copy(0.08f), RoundedCornerShape(10.dp))
                            .clickable { onStreamSelected(stream) }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(32.dp)
                                .background(sportAccent.copy(0.15f), CircleShape)
                                .border(1.dp, sportAccent.copy(0.5f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint   = sportAccent,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(
                                stream.name,
                                color      = Color.White,
                                fontSize   = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "Tap to play",
                                color    = Color.Gray,
                                fontSize = 11.sp
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        if (index == 0) {
                            // Recommended badge on first server
                            Text(
                                "BEST",
                                color     = sportAccent,
                                fontSize  = 9.sp,
                                fontWeight = FontWeight.Bold,
                                modifier  = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(sportAccent.copy(0.15f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    if (index < event.streams.lastIndex) {
                        Spacer(Modifier.height(8.dp))
                    }
                }

                Spacer(Modifier.height(12.dp))
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("CANCEL", color = Color.Gray, fontSize = 12.sp)
                }
            }
        }
    }
}
