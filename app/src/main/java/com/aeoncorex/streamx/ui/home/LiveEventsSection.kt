package com.aeoncorex.streamx.ui.home

import android.widget.Toast
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.navigation.NavController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.aeoncorex.streamx.data.EventRepository
import com.aeoncorex.streamx.model.EventStream
import com.aeoncorex.streamx.model.LiveEvent
import com.valentinilk.shimmer.shimmer
import kotlinx.coroutines.launch
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*

// ══════════════════════════════════════════════════════════════════
//  Helpers
// ══════════════════════════════════════════════════════════════════

fun sportColor(hex: String): Color = try {
    Color(android.graphics.Color.parseColor(hex))
} catch (_: Exception) { Color(0xFFE53935) }

/** Decode HTML entities — fixes "&amp;" → "&", "&apos;" → "'" etc. */
@Suppress("DEPRECATION")
private fun String.decodeHtml(): String =
    android.text.Html.fromHtml(this, android.text.Html.FROM_HTML_MODE_COMPACT)
        .toString().trim()

/** "2025-05-25T20:00:00Z" → "Today  8:00 PM" / "Tomorrow  …" / "May 25  •  8 PM" */
private fun formatTime(iso: String): String {
    if (iso.isBlank()) return ""
    return try {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault())
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        val date = fmt.parse(iso) ?: return ""
        val now  = Calendar.getInstance()
        val ev   = Calendar.getInstance().apply { time = date }
        val t    = SimpleDateFormat("h:mm a", Locale.getDefault()).format(date)
        when {
            now[Calendar.YEAR] == ev[Calendar.YEAR] &&
            now[Calendar.DAY_OF_YEAR] == ev[Calendar.DAY_OF_YEAR] &&
            now.timeInMillis > ev.timeInMillis                          -> "● LIVE  $t"
            now[Calendar.YEAR] == ev[Calendar.YEAR] &&
            now[Calendar.DAY_OF_YEAR] == ev[Calendar.DAY_OF_YEAR]     -> "Today  $t"
            now[Calendar.YEAR] == ev[Calendar.YEAR] &&
            now[Calendar.DAY_OF_YEAR] + 1 == ev[Calendar.DAY_OF_YEAR] -> "Tomorrow  $t"
            else -> SimpleDateFormat("MMM d  •  h:mm a", Locale.getDefault()).format(date)
        }
    } catch (_: Exception) { "" }
}

/** "Team A vs Team B" → Pair("TE", "TE") initials for badge circles */
private fun teamInitials(title: String): Pair<String, String> {
    val decoded = title.decodeHtml()
    val sep = listOf(" vs ", " VS ", " Vs ", " v ", " V ")
    for (s in sep) {
        if (decoded.contains(s)) {
            val parts = decoded.split(s, limit = 2)
            val home  = parts[0].trim().split(" ").filter { it.isNotBlank() }
                .take(2).joinToString("") { it[0].uppercase() }.take(3)
            val away  = parts[1].trim().split(" ").filter { it.isNotBlank() }
                .take(2).joinToString("") { it[0].uppercase() }.take(3)
            return Pair(home, away)
        }
    }
    // Single-team event (channel name)
    val words = decoded.trim().split(" ").filter { it.isNotBlank() }
    val init  = words.take(2).joinToString("") { it[0].uppercase() }.take(3)
    return Pair(init, "")
}

/** Team names from title */
private fun teamNames(title: String): Pair<String, String> {
    val decoded = title.decodeHtml()
    val sep = listOf(" vs ", " VS ", " Vs ", " v ", " V ")
    for (s in sep) {
        if (decoded.contains(s)) {
            val parts = decoded.split(s, limit = 2)
            return Pair(
                parts[0].trim().take(18),
                parts[1].trim().take(18)
            )
        }
    }
    return Pair(decoded.take(22), "")
}

// ══════════════════════════════════════════════════════════════════
//  Filter tab enum
// ══════════════════════════════════════════════════════════════════
private enum class EFilter { ALL, LIVE, TODAY, TOMORROW }

// ══════════════════════════════════════════════════════════════════
//  LiveEventsSection — full screen, sport-grouped, 2-column grid
// ══════════════════════════════════════════════════════════════════
@Composable
fun LiveEventsSection(
    navController      : NavController,
    onRefreshRequested : (() -> Unit)? = null
) {
    val scope   = rememberCoroutineScope()
    val context = LocalContext.current

    var allEvents     by remember { mutableStateOf<List<LiveEvent>>(emptyList()) }
    var isLoading     by remember { mutableStateOf(true) }
    var activeFilter  by remember { mutableStateOf(EFilter.ALL) }
    var showDialog    by remember { mutableStateOf(false) }
    var selectedEvent by remember { mutableStateOf<LiveEvent?>(null) }

    LaunchedEffect(Unit) {
        scope.launch {
            try { allEvents = EventRepository.getActiveEvents() }
            catch (_: Exception) { }
            isLoading = false
        }
    }

    if (!isLoading && allEvents.isEmpty()) return

    // ── Filter ────────────────────────────────────────────────────
    val todayCal = Calendar.getInstance()
    val displayedEvents = allEvents.filter { ev ->
        when (activeFilter) {
            EFilter.ALL      -> true
            EFilter.LIVE     -> ev.isLive
            EFilter.TODAY    -> {
                if (ev.startTime.isBlank()) false
                else try {
                    val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault())
                    fmt.timeZone = TimeZone.getTimeZone("UTC")
                    val d = fmt.parse(ev.startTime) ?: return@filter false
                    val ec = Calendar.getInstance().apply { time = d }
                    todayCal[Calendar.DAY_OF_YEAR] == ec[Calendar.DAY_OF_YEAR] &&
                    todayCal[Calendar.YEAR]        == ec[Calendar.YEAR]
                } catch (_: Exception) { false }
            }
            EFilter.TOMORROW -> {
                if (ev.startTime.isBlank()) false
                else try {
                    val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault())
                    fmt.timeZone = TimeZone.getTimeZone("UTC")
                    val d = fmt.parse(ev.startTime) ?: return@filter false
                    val ec = Calendar.getInstance().apply { time = d }
                    todayCal[Calendar.DAY_OF_YEAR] + 1 == ec[Calendar.DAY_OF_YEAR] &&
                    todayCal[Calendar.YEAR]             == ec[Calendar.YEAR]
                } catch (_: Exception) { false }
            }
        }
    }

    // Group by sport — "Other" always last
    val grouped = displayedEvents
        .groupBy { it.sport.decodeHtml().ifBlank { "Other" } }
        .entries
        .sortedWith(compareBy {
            when {
                it.value.any { e -> e.isLive } -> "0_${it.key}" // live sports first
                it.key.equals("other", true)   -> "zzz"
                else                            -> it.key
            }
        })

    val liveCount     = allEvents.count { it.isLive }
    val todayCount    = allEvents.count { !it.isLive && it.startTime.isNotBlank() }

    // ─────────────────────────────────────────────────────────────
    LazyColumn(
        modifier            = Modifier.fillMaxSize(),
        contentPadding      = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {

        // ── Section title ─────────────────────────────────────────
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.LiveTv, null,
                    tint = Color(0xFFFF1744), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(7.dp))
                Text("LIVE & UPCOMING", color = Color.White,
                    fontWeight = FontWeight.ExtraBold, fontSize = 14.sp,
                    letterSpacing = 1.sp)
                Spacer(Modifier.weight(1f))
                if (liveCount > 0) LiveCountBadge(liveCount)
            }
        }

        // ── Filter chips ─────────────────────────────────────────
        item {
            LazyRow(
                contentPadding        = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier              = Modifier.padding(bottom = 14.dp)
            ) {
                item {
                    EFilterChip("ALL", activeFilter == EFilter.ALL,
                        Color(0xFF9B59FF)) { activeFilter = EFilter.ALL }
                }
                if (liveCount > 0) item {
                    EFilterChip("LIVE NOW  $liveCount", activeFilter == EFilter.LIVE,
                        Color(0xFFFF1744)) { activeFilter = EFilter.LIVE }
                }
                if (todayCount > 0) item {
                    EFilterChip("TODAY'S", activeFilter == EFilter.TODAY,
                        Color(0xFF00BFFF)) { activeFilter = EFilter.TODAY }
                }
                item {
                    EFilterChip("TOMORROW", activeFilter == EFilter.TOMORROW,
                        Color(0xFF00C853)) { activeFilter = EFilter.TOMORROW }
                }
            }
        }

        // ── Shimmer ───────────────────────────────────────────────
        if (isLoading) {
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    repeat(2) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            repeat(2) {
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .height(170.dp)
                                        .clip(RoundedCornerShape(18.dp))
                                        .shimmer()
                                        .background(Color(0xFF1E1E2A))
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }
            return@LazyColumn
        }

        // ── Sport sections ────────────────────────────────────────
        if (displayedEvents.isEmpty()) {
            item {
                Box(
                    Modifier.fillMaxWidth().padding(vertical = 60.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.SearchOff, null,
                            tint = Color.Gray, modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("No events found", color = Color.Gray, fontSize = 14.sp)
                    }
                }
            }
        }

        grouped.forEach { (sport, events) ->
            val sportAccent = sportColor(events.first().sportColor)

            // Sport section header
            item(key = "header_$sport") {
                SportHeader(
                    sport  = sport.uppercase(),
                    accent = sportAccent,
                    count  = events.size,
                    isLive = events.any { it.isLive }
                )
            }

            // Cards in rows of 2
            // weight(1f) inside a Row = always exactly half screen, never cut off
            val rows = events.chunked(2)
            rows.forEach { pair ->
                item(key = "row_${sport}_${pair.first().eventId}") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        pair.forEach { event ->
                            EventCard(
                                event    = event,
                                modifier = Modifier.weight(1f),
                                onClick  = { selectedEvent = event; showDialog = true }
                            )
                        }
                        // Odd row — fill empty half with invisible spacer
                        if (pair.size == 1) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }

            // Section divider
            item(key = "div_$sport") {
                Spacer(Modifier.height(6.dp))
                HorizontalDivider(
                    color     = Color.White.copy(.06f),
                    modifier  = Modifier.padding(horizontal = 16.dp)
                )
                Spacer(Modifier.height(10.dp))
            }
        }
    }

    // ── Stream picker dialog ──────────────────────────────────────
    if (showDialog && selectedEvent != null) {
        EventStreamDialog(
            event     = selectedEvent!!,
            onDismiss = { showDialog = false },
            onStreamSelected = { stream ->
                showDialog = false
                try {
                    val ev           = selectedEvent!!
                    val idx          = ev.streams.indexOfFirst { it.url == stream.url }.coerceAtLeast(0)
                    val encodedTitle = URLEncoder.encode(ev.title, "UTF-8")
                    navController.navigate("event_player/${ev.eventId}/$idx/$encodedTitle")
                } catch (_: Exception) {
                    Toast.makeText(context, "Invalid stream", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }
}

// ══════════════════════════════════════════════════════════════════
//  Sport section header
// ══════════════════════════════════════════════════════════════════
@Composable
private fun SportHeader(
    sport  : String,
    accent : Color,
    count  : Int,
    isLive : Boolean
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Coloured left bar
        Box(Modifier.width(3.dp).height(20.dp).background(
            Brush.verticalGradient(listOf(accent, accent.copy(.2f))),
            RoundedCornerShape(2.dp)
        ))
        Spacer(Modifier.width(10.dp))
        Text(
            sport,
            color         = accent,
            fontWeight    = FontWeight.ExtraBold,
            fontSize      = 12.sp,
            letterSpacing = 1.5.sp
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "$count",
            color    = accent.copy(.5f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
        if (isLive) {
            Spacer(Modifier.width(8.dp))
            LiveBadge()
        }
    }
}

// ══════════════════════════════════════════════════════════════════
//  Event Card — weight(1f) based, always fills exactly half width
//  VS layout with team initials circles + thumbnail fallback
// ══════════════════════════════════════════════════════════════════
@Composable
private fun EventCard(
    event    : LiveEvent,
    modifier : Modifier,
    onClick  : () -> Unit
) {
    val context          = LocalContext.current
    val accent           = sportColor(event.sportColor)
    val title            = event.title.decodeHtml()
    val (initA, initB)   = teamInitials(title)
    val (nameA, nameB)   = teamNames(title)
    val timeLabel        = if (event.isLive) "" else formatTime(event.startTime)
    val hasTwoTeams      = initB.isNotEmpty()
    val serverCount      = event.streams.size.takeIf { it > 0 } ?: event.streamCount

    Box(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF10101A))
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    listOf(accent.copy(.35f), accent.copy(.08f))
                ),
                shape = RoundedCornerShape(16.dp)
            )
            .clickable(onClick = onClick)
    ) {

        Column {

            // ── Top bar: status + server count ───────────────────
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(accent.copy(.12f))
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (event.isLive) {
                    LiveBadge()
                } else if (timeLabel.isNotEmpty()) {
                    Text(
                        timeLabel,
                        color    = Color(0xFF00BFFF),
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                } else {
                    Spacer(Modifier.size(1.dp))
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Sensors, null,
                        tint     = Color.Gray.copy(.7f),
                        modifier = Modifier.size(9.dp))
                    Spacer(Modifier.width(2.dp))
                    Text(
                        "$serverCount",
                        color    = Color.Gray.copy(.7f),
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // ── Centre: thumbnail or team circles ─────────────────
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(100.dp)
                    .background(
                        Brush.radialGradient(
                            listOf(accent.copy(.15f), Color.Transparent)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (event.thumbnail.isNotEmpty()) {
                    // ✅ Thumbnail with Referer header for streamed.pk badge images
                    // streamed.pk/api/images/*.webp requires Referer header
                    val thumbModel = remember(event.thumbnail) {
                        ImageRequest.Builder(context)
                            .data(event.thumbnail)
                            .addHeader("Referer", "https://streamed.pk/")
                            .addHeader("Origin",  "https://streamed.pk")
                            .addHeader("User-Agent",
                                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0.0.0")
                            .crossfade(true)
                            .build()
                    }
                    AsyncImage(
                        model              = thumbModel,
                        contentDescription = null,
                        contentScale       = ContentScale.Fit,
                        modifier           = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        onError            = { /* fall through to initials below */ }
                    )
                } else if (hasTwoTeams) {
                    // VS layout with initials
                    Row(
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        TeamCircle(initA, accent)
                        Text(
                            "VS",
                            color      = Color.White.copy(.4f),
                            fontSize   = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.sp
                        )
                        TeamCircle(initB, accent.copy(.6f))
                    }
                } else {
                    // Single team / channel
                    TeamCircle(initA, accent, size = 52.dp, textSize = 16.sp)
                }
            }

            // ── Bottom: sport + title + team names ───────────────
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Sport pill
                Box(
                    Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(accent.copy(.15f))
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                ) {
                    Text(
                        event.sport.decodeHtml().uppercase(),
                        color         = accent,
                        fontSize      = 7.sp,
                        fontWeight    = FontWeight.ExtraBold,
                        letterSpacing = .7.sp
                    )
                }

                // Team names or title
                if (hasTwoTeams) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            nameA,
                            color      = Color.White,
                            fontSize   = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines   = 1,
                            overflow   = TextOverflow.Ellipsis,
                            textAlign  = TextAlign.Start,
                            modifier   = Modifier.weight(1f)
                        )
                        Text(
                            nameB,
                            color      = Color.White.copy(.7f),
                            fontSize   = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines   = 1,
                            overflow   = TextOverflow.Ellipsis,
                            textAlign  = TextAlign.End,
                            modifier   = Modifier.weight(1f)
                        )
                    }
                } else {
                    Text(
                        title,
                        color      = Color.White,
                        fontSize   = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines   = 2,
                        overflow   = TextOverflow.Ellipsis,
                        lineHeight = 14.sp
                    )
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════
//  Team initial circle
// ══════════════════════════════════════════════════════════════════
@Composable
private fun TeamCircle(
    initials : String,
    color    : Color,
    size     : androidx.compose.ui.unit.Dp = 42.dp,
    textSize : androidx.compose.ui.unit.TextUnit = 13.sp
) {
    Box(
        Modifier
            .size(size)
            .background(
                Brush.radialGradient(listOf(color.copy(.4f), color.copy(.12f))),
                CircleShape
            )
            .border(1.5.dp, color.copy(.5f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            initials,
            color      = Color.White,
            fontSize   = textSize,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (.5).sp
        )
    }
}

// ══════════════════════════════════════════════════════════════════
//  Filter chip
// ══════════════════════════════════════════════════════════════════
@Composable
private fun EFilterChip(
    label    : String,
    selected : Boolean,
    color    : Color,
    onClick  : () -> Unit
) {
    val bg  = if (selected) color.copy(.18f) else Color.White.copy(.06f)
    val br  = if (selected) color.copy(.6f)  else Color.White.copy(.1f)
    val txt = if (selected) color             else Color.Gray

    Box(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .border(1.dp, br, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Text(label, color = txt, fontSize = 11.sp, fontWeight = FontWeight.Bold,
            letterSpacing = .4.sp)
    }
}

// ══════════════════════════════════════════════════════════════════
//  Live badge
// ══════════════════════════════════════════════════════════════════
@Composable
fun LiveBadge() {
    val inf   = rememberInfiniteTransition(label = "lb")
    val pulse by inf.animateFloat(.6f, 1f,
        infiniteRepeatable(tween(900, easing = EaseInOutSine), RepeatMode.Reverse), "p")
    Row(
        Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(Color(0xFFFF1744).copy(.18f))
            .border(.5.dp, Color(0xFFFF1744).copy(.6f), RoundedCornerShape(5.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(5.dp).background(Color(0xFFFF1744).copy(pulse), CircleShape))
        Spacer(Modifier.width(4.dp))
        Text("LIVE", color = Color(0xFFFF1744), fontSize = 8.sp,
            fontWeight = FontWeight.ExtraBold, letterSpacing = .8.sp)
    }
}

// ══════════════════════════════════════════════════════════════════
//  Live count badge (header)
// ══════════════════════════════════════════════════════════════════
@Composable
fun LiveCountBadge(count: Int) {
    val inf   = rememberInfiniteTransition(label = "cn")
    val pulse by inf.animateFloat(.5f, 1f,
        infiniteRepeatable(tween(1000, easing = EaseInOutSine), RepeatMode.Reverse), "p")
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFFFF1744).copy(.12f))
            .border(1.dp, Color(0xFFFF1744).copy(pulse * .5f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(6.dp).background(Color(0xFFFF1744).copy(pulse), CircleShape))
        Spacer(Modifier.width(5.dp))
        Text("$count LIVE", color = Color(0xFFFF1744), fontSize = 10.sp,
            fontWeight = FontWeight.ExtraBold, letterSpacing = .6.sp)
    }
}

// Kept for backward compat usage in dialog
@Composable
fun BlinkingLiveDot() {
    val alpha by rememberInfiniteTransition(label = "d").animateFloat(
        1f, .2f, infiniteRepeatable(tween(600, easing = LinearEasing), RepeatMode.Reverse), "b")
    Box(Modifier.size(5.dp).background(Color.White.copy(alpha), CircleShape))
}

// ══════════════════════════════════════════════════════════════════
//  Stream picker dialog
// ══════════════════════════════════════════════════════════════════
@Composable
fun EventStreamDialog(
    event            : LiveEvent,
    onDismiss        : () -> Unit,
    onStreamSelected : (EventStream) -> Unit
) {
    val context   = LocalContext.current
    val accent    = sportColor(event.sportColor)
    val title     = event.title.decodeHtml()
    val timeLabel = formatTime(event.startTime)

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape  = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0E0E18)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {

                // ── Thumbnail / badge header ──────────────────────
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(110.dp)
                        .background(
                            Brush.linearGradient(listOf(accent.copy(.3f), Color(0xFF0A0A14)))
                        )
                ) {
                    if (event.thumbnail.isNotEmpty()) {
                        val thumbModel2 = remember(event.thumbnail) {
                            ImageRequest.Builder(context)
                                .data(event.thumbnail)
                                .addHeader("Referer",    "https://streamed.pk/")
                                .addHeader("Origin",     "https://streamed.pk")
                                .addHeader("User-Agent",
                                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0.0.0")
                                .crossfade(true)
                                .build()
                        }
                        AsyncImage(
                            model              = thumbModel2,
                            contentDescription = null,
                            contentScale       = ContentScale.Fit,
                            modifier           = Modifier
                                .fillMaxHeight()
                                .align(Alignment.Center)
                                .padding(horizontal = 32.dp, vertical = 8.dp)
                        )
                    } else {
                        // Team circles in dialog header
                        val (iA, iB) = teamInitials(title)
                        Row(
                            Modifier.align(Alignment.Center),
                            verticalAlignment     = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            TeamCircle(iA, accent, size = 52.dp, textSize = 16.sp)
                            if (iB.isNotEmpty()) {
                                Text("VS", color = Color.White.copy(.4f), fontSize = 12.sp,
                                    fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
                                TeamCircle(iB, accent.copy(.6f), size = 52.dp, textSize = 16.sp)
                            }
                        }
                    }
                    // Fade bottom
                    Box(
                        Modifier.fillMaxWidth().height(40.dp).align(Alignment.BottomCenter)
                            .background(Brush.verticalGradient(
                                listOf(Color.Transparent, Color(0xFF0E0E18))))
                    )
                    if (event.isLive) {
                        Box(Modifier.align(Alignment.TopEnd).padding(10.dp)) { LiveBadge() }
                    }
                }

                Column(Modifier.padding(horizontal = 18.dp).padding(bottom = 18.dp)) {

                    // Sport + time
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.clip(RoundedCornerShape(5.dp))
                                .background(accent.copy(.18f))
                                .padding(horizontal = 7.dp, vertical = 3.dp)
                        ) {
                            Text(event.sport.decodeHtml().uppercase(), color = accent,
                                fontSize = 9.sp, fontWeight = FontWeight.ExtraBold,
                                letterSpacing = .8.sp)
                        }
                        if (!event.isLive && timeLabel.isNotEmpty()) {
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.Rounded.Schedule, null,
                                tint = Color(0xFF00BFFF).copy(.7f),
                                modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(3.dp))
                            Text(timeLabel, color = Color(0xFF00BFFF),
                                fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    Spacer(Modifier.height(6.dp))
                    Text(title, color = Color.White, fontSize = 16.sp,
                        fontWeight = FontWeight.ExtraBold, maxLines = 2,
                        lineHeight = 21.sp, overflow = TextOverflow.Ellipsis)

                    Spacer(Modifier.height(3.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Sensors, null, tint = Color.Gray,
                            modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(4.dp))
                        val hdCount = event.streams.count { it.name.contains("HD", true) }
                        Text(
                            "${event.streams.size} server${if (event.streams.size != 1) "s" else ""}" +
                            if (hdCount > 0) "  ·  $hdCount HD" else "",
                            color = Color.Gray, fontSize = 11.sp
                        )
                    }

                    HorizontalDivider(color = Color.White.copy(.08f),
                        modifier = Modifier.padding(vertical = 12.dp))

                    // Stream rows
                    event.streams.forEachIndexed { i, stream ->
                        val isHd   = stream.name.contains("HD", ignoreCase = true)
                        val isBest = i == 0

                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isBest) accent.copy(.1f) else Color.White.copy(.05f))
                                .border(1.dp,
                                    if (isBest) accent.copy(.3f) else Color.White.copy(.07f),
                                    RoundedCornerShape(12.dp))
                                .clickable { onStreamSelected(stream) }
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Number
                            Box(
                                Modifier.size(32.dp)
                                    .background(
                                        if (isBest) accent.copy(.25f) else Color.White.copy(.08f),
                                        CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("${i + 1}", color = if (isBest) accent else Color.Gray,
                                    fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(stream.name, color = Color.White, fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("Tap to watch", color = Color.Gray, fontSize = 10.sp)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                if (isHd) {
                                    Box(
                                        Modifier.clip(RoundedCornerShape(4.dp))
                                            .background(Color(0xFF00C853).copy(.18f))
                                            .border(.5.dp, Color(0xFF00C853).copy(.5f), RoundedCornerShape(4.dp))
                                            .padding(horizontal = 5.dp, vertical = 2.dp)
                                    ) {
                                        Text("HD", color = Color(0xFF00C853),
                                            fontSize = 8.sp, fontWeight = FontWeight.ExtraBold)
                                    }
                                }
                                if (isBest) {
                                    Box(
                                        Modifier.clip(RoundedCornerShape(4.dp))
                                            .background(accent.copy(.18f))
                                            .padding(horizontal = 5.dp, vertical = 2.dp)
                                    ) {
                                        Text("BEST", color = accent,
                                            fontSize = 8.sp, fontWeight = FontWeight.ExtraBold)
                                    }
                                }
                                Icon(Icons.Rounded.PlayCircle, null,
                                    tint = if (isBest) accent else Color.Gray,
                                    modifier = Modifier.size(20.dp))
                            }
                        }
                        if (i < event.streams.lastIndex) Spacer(Modifier.height(7.dp))
                    }

                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = onDismiss,
                        modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text("DISMISS", color = Color.Gray, fontSize = 12.sp, letterSpacing = .5.sp)
                    }
                }
            }
        }
    }
}
