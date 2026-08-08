package com.aeoncorex.streamx.ui.movie

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.aeoncorex.streamx.ads.AdManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.net.URLDecoder
import java.net.URLEncoder

// ═══════════════════════════════════════════════════════════════════════════
//  Screen
// ═══════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MovieLinkSelectionScreen(
    navController: NavController,
    imdbId: String, tmdbId: Int, title: String,
    type: String, season: Int, episode: Int
) {
    val context      = LocalContext.current
    val activity     = context as? Activity
    val decodedTitle = remember(title) {
        try { URLDecoder.decode(title, "UTF-8") } catch (_: Exception) { title }
    }
    val isSeries = type.equals("SERIES", true) || type.equals("TV", true)

    // ── Episode/season selection — starts from whatever MovieDetailsScreen
    // passed in, but is fully user-adjustable from here now. This is the
    // piece that was previously missing: MovieDetailsScreen fixed
    // (season, episode) once and this screen never let the user change
    // it without navigating back.
    var selectedSeason  by remember { mutableIntStateOf(season.coerceAtLeast(1)) }
    var selectedEpisode by remember { mutableIntStateOf(episode.coerceAtLeast(1)) }
    var availableSeasons  by remember { mutableStateOf<List<SeasonDto>>(emptyList()) }
    var availableEpisodes by remember { mutableStateOf<List<EpisodeDto>>(emptyList()) }
    var isLoadingSeasons  by remember { mutableStateOf(isSeries) }
    var isLoadingEpisodes by remember { mutableStateOf(false) }
    var showEpisodeSheet  by remember { mutableStateOf(false) }

    // ── Unified result pool + search bar ─────────────────────────────
    // Replaces the old per-language chip row (which re-triggered a fresh
    // network search on every tap — slow, and split results across 9
    // separate searches the user had to click through one at a time).
    // Now: ONE fetch merges the native/English-provider pass with the
    // dubbed-provider pass (DualAudio pulls in the same Hindi/Tamil/
    // Telugu-friendly sources — TorrentCSV, SolidTorrents, Nyaa,
    // AnimeTosho — that the old per-language chips used individually,
    // since those providers return whatever they find rather than
    // filtering server-side by a single requested language), then the
    // search bar filters that single merged list client-side by title —
    // instant, no re-fetch, and multi/dual-audio releases (tagged via
    // StreamLink.audioTag, see MovieModels.kt) are just as searchable as
    // a plain language name typed into the box.
    var searchQuery  by remember { mutableStateOf("") }
    var torrentLinks by remember { mutableStateOf<List<StreamLink>>(emptyList()) }
    var isLoading    by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var adLoading    by remember { mutableStateOf(false) }

    val filteredLinks = remember(torrentLinks, searchQuery) {
        if (searchQuery.isBlank()) torrentLinks
        else torrentLinks.filter { link ->
            link.title.contains(searchQuery, ignoreCase = true) ||
            link.source.contains(searchQuery, ignoreCase = true) ||
            link.quality.contains(searchQuery, ignoreCase = true) ||
            link.audioTag?.label?.contains(searchQuery, ignoreCase = true) == true
        }
    }

    // ── Load season list once (series only) — needed so the episode
    // sheet shows real season numbers/episode counts instead of a blind
    // stepper. Movies skip this entirely (isLoadingSeasons starts false).
    LaunchedEffect(tmdbId, isSeries) {
        if (!isSeries || tmdbId <= 0) { isLoadingSeasons = false; return@LaunchedEffect }
        isLoadingSeasons = true
        try {
            val full = MovieRepository.getFullDetails(tmdbId, MovieType.SERIES)
            availableSeasons = full?.seasons ?: emptyList()
        } catch (_: Exception) {
            availableSeasons = emptyList()
        } finally {
            isLoadingSeasons = false
        }
    }

    // ── Load episode list whenever the selected season changes ─────────
    LaunchedEffect(selectedSeason, isSeries, tmdbId) {
        if (!isSeries || tmdbId <= 0) return@LaunchedEffect
        isLoadingEpisodes = true
        try {
            availableEpisodes = MovieRepository.getEpisodes(tmdbId, selectedSeason)
        } catch (_: Exception) {
            availableEpisodes = emptyList()
        } finally {
            isLoadingEpisodes = false
        }
    }

    // ── Torrent search — re-fires on title/season/episode change only.
    // Fetches BOTH the native/English pass and the DualAudio pass in
    // parallel and merges them (dedupe by magnet happens inside each
    // TorrentRepository call already; a second distinctBy here catches
    // any overlap between the two passes), so the search bar has one
    // complete pool to filter instead of the user needing to re-search
    // per language.
    LaunchedEffect(decodedTitle, selectedSeason, selectedEpisode) {
        isLoading = true; errorMessage = null; torrentLinks = emptyList()
        try {
            val movieType = if (isSeries) MovieType.SERIES else MovieType.MOVIE
            val isAnime   = listOf("Naruto","One Piece","Demon Slayer","Jujutsu","Attack on Titan","Dragon Ball")
                .any { decodedTitle.contains(it, ignoreCase = true) }
            val validImdb = if (imdbId != "null" && imdbId.isNotEmpty()) imdbId else null

            val nativeResult = TorrentRepository.getStreamLinks(
                type = movieType, title = decodedTitle, imdbId = validImdb,
                season = selectedSeason, episode = selectedEpisode, isAnime = isAnime,
                dubLang = DubLanguage.English
            )
            currentCoroutineContext().ensureActive()

            val dubbedResult = try {
                TorrentRepository.getStreamLinks(
                    type = movieType, title = decodedTitle, imdbId = validImdb,
                    season = selectedSeason, episode = selectedEpisode, isAnime = isAnime,
                    dubLang = DubLanguage.DualAudio
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList() // dubbed pass is best-effort — native pass alone still shows results
            }
            currentCoroutineContext().ensureActive()

            torrentLinks = (nativeResult + dubbedResult).distinctBy { it.magnet }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorMessage = "Search failed: ${e.localizedMessage}"
        } finally {
            isLoading = false
        }
    }

    // Takes the whole StreamLink (not just a magnet string) so private-
    // tracker results — which carry torrentFileUrl instead of a usable
    // magnet — can be routed correctly. The actual cookie itself is
    // deliberately NOT passed through the nav route string: only siteId
    // is, and MoviePlayerScreen looks up the live cookie from
    // PrivateTrackerCookieStore itself at play time. This keeps a
    // potentially-sensitive session cookie out of the nav back stack /
    // any future deep-link logging, and means a cookie refreshed after
    // this screen was opened (e.g. the user re-logged in from Settings
    // in another tab) is always the one actually used.
    fun playTorrent(link: StreamLink) {
        if (activity == null) return
        adLoading = true
        AdManager.showInterstitial(activity) {
            adLoading = false
            val playableUrl = link.torrentFileUrl ?: link.magnet
            val enc = URLEncoder.encode(playableUrl, "UTF-8")
            val encImdb = URLEncoder.encode(imdbId.takeIf { it != "null" } ?: "", "UTF-8")
            val encSiteId = URLEncoder.encode(if (link.requiresTorrentAuth) link.siteId else "", "UTF-8")
            navController.navigate("torrent_player/$enc?imdbId=$encImdb&trackerSiteId=$encSiteId")
        }
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("4K / 1080P Sources", color = Color.White,
                                fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Text(
                                if (isSeries) "$decodedTitle · S${selectedSeason}E${selectedEpisode}" else decodedTitle,
                                color = Color.Gray, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.Default.ArrowBack, null, tint = Color.Cyan)
                        }
                    },
                    actions = {
                        if (isSeries) {
                            TextButton(onClick = { showEpisodeSheet = true }) {
                                Icon(Icons.Rounded.ViewList, null, tint = Color.Cyan, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("S${selectedSeason}E${selectedEpisode}", color = Color.Cyan, fontSize = 13.sp)
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0A0A14))
                )
            },
            containerColor = Color.Black
        ) { padding ->
            Box(
                Modifier.fillMaxSize().padding(padding)
                    .background(Brush.verticalGradient(listOf(Color(0xFF0A0A14), Color.Black)))
            ) {
                Column(Modifier.fillMaxSize()) {

                    // ── Search bar ────────────────────────────────────────
                    // Filters the single merged result pool by title, source,
                    // quality, or audio tag — e.g. typing "hindi", "dual",
                    // "multi", "1080p", or a release-group name all narrow
                    // the same list instantly, no re-fetch, no separate tabs.
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        placeholder = {
                            Text("Search by title, language, quality…", color = Color.Gray, fontSize = 13.sp)
                        },
                        leadingIcon = {
                            Icon(Icons.Rounded.Search, null, tint = Color.Gray, modifier = Modifier.size(20.dp))
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Rounded.Close, null, tint = Color.Gray, modifier = Modifier.size(18.dp))
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = Color.Cyan,
                            unfocusedBorderColor = Color.White.copy(0.12f),
                            focusedContainerColor   = Color(0xFF0F0F1A),
                            unfocusedContainerColor = Color(0xFF0F0F1A),
                            cursorColor = Color.Cyan,
                            focusedTextColor   = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp)
                    )

                    // ── Results area (torrent) ───────────────────────────
                    Box(Modifier.fillMaxSize()) {
                        when {
                            isLoading -> Column(
                                Modifier.align(Alignment.Center),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                CircularProgressIndicator(color = Color.Cyan, modifier = Modifier.size(36.dp), strokeWidth = 3.dp)
                                Spacer(Modifier.height(12.dp))
                                Text("Scanning P2P Networks…", color = Color.Gray, fontSize = 13.sp)
                                Text("YTS · RARBG · EZTV · 1337x", color = Color.Gray.copy(0.5f), fontSize = 11.sp)
                            }
                            errorMessage != null -> Column(
                                Modifier.align(Alignment.Center).padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(Icons.Rounded.SearchOff, null, tint = Color.Gray, modifier = Modifier.size(44.dp))
                                Spacer(Modifier.height(12.dp))
                                Text(errorMessage!!, color = Color.Gray, textAlign = TextAlign.Center, fontSize = 13.sp)
                            }
                            torrentLinks.isEmpty() -> Column(
                                Modifier.align(Alignment.Center).padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(Icons.Rounded.CloudOff, null, tint = Color.Gray, modifier = Modifier.size(44.dp))
                                Spacer(Modifier.height(12.dp))
                                Text("No sources found", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    "No torrents were found for this title right now",
                                    color = Color.Gray, fontSize = 13.sp, textAlign = TextAlign.Center
                                )
                                Spacer(Modifier.height(16.dp))
                                Button(
                                    onClick = { navController.popBackStack() },
                                    colors  = ButtonDefaults.buttonColors(containerColor = Color(0xFF0E2A1E))
                                ) { Text("← Go Back", color = Color.Cyan) }
                            }
                            filteredLinks.isEmpty() -> Column(
                                Modifier.align(Alignment.Center).padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(Icons.Rounded.SearchOff, null, tint = Color.Gray, modifier = Modifier.size(44.dp))
                                Spacer(Modifier.height(12.dp))
                                Text("No matches for \"$searchQuery\"", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    "${torrentLinks.size} sources available · try a different search term",
                                    color = Color.Gray, fontSize = 13.sp, textAlign = TextAlign.Center
                                )
                                Spacer(Modifier.height(16.dp))
                                TextButton(onClick = { searchQuery = "" }) {
                                    Text("Clear search", color = Color.Cyan)
                                }
                            }
                            else -> LazyColumn(
                                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                item {
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                                        Icon(Icons.Rounded.DownloadForOffline, null, tint = Color.Cyan, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            "${filteredLinks.size} of ${torrentLinks.size} sources",
                                            color = Color.Cyan, fontSize = 13.sp, fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                                items(filteredLinks) { link ->
                                    TorrentCard(link) { playTorrent(link) }
                                }
                                item { Spacer(Modifier.height(20.dp)) }
                            }
                        }
                    }
                }
            }
        }

        if (adLoading) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(0.88f)), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.Cyan, modifier = Modifier.size(40.dp))
            }
        }

        if (showEpisodeSheet && isSeries) {
            EpisodeSelectSheet(
                seasons = availableSeasons,
                episodes = availableEpisodes,
                selectedSeason = selectedSeason,
                selectedEpisode = selectedEpisode,
                isLoadingSeasons = isLoadingSeasons,
                isLoadingEpisodes = isLoadingEpisodes,
                onSeasonSelected = { selectedSeason = it },
                onEpisodeSelected = { selectedEpisode = it; showEpisodeSheet = false },
                onDismiss = { showEpisodeSheet = false }
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
//  Episode/season picker sheet
// ═══════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EpisodeSelectSheet(
    seasons: List<SeasonDto>,
    episodes: List<EpisodeDto>,
    selectedSeason: Int,
    selectedEpisode: Int,
    isLoadingSeasons: Boolean,
    isLoadingEpisodes: Boolean,
    onSeasonSelected: (Int) -> Unit,
    onEpisodeSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0A0A14)
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                "Select Episode",
                color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
            )

            // ── Season row ───────────────────────────────────────────
            if (isLoadingSeasons) {
                Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.Cyan, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            } else if (seasons.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(seasons) { s ->
                        val isSelected = s.seasonNumber == selectedSeason
                        Box(
                            Modifier
                                .background(
                                    if (isSelected) Color(0xFF003333) else Color(0xFF12121C),
                                    RoundedCornerShape(10.dp)
                                )
                                .border(
                                    1.dp,
                                    if (isSelected) Color.Cyan else Color.White.copy(0.1f),
                                    RoundedCornerShape(10.dp)
                                )
                                .clickable { onSeasonSelected(s.seasonNumber) }
                                .padding(horizontal = 16.dp, vertical = 10.dp)
                        ) {
                            Text(
                                "S${s.seasonNumber}",
                                color = if (isSelected) Color.Cyan else Color.White.copy(0.8f),
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            } else {
                // Fallback when no season metadata is available at all —
                // still lets the user step through season numbers manually
                // instead of being stuck on whatever MovieDetailsScreen sent.
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { if (selectedSeason > 1) onSeasonSelected(selectedSeason - 1) }) {
                        Icon(Icons.Rounded.Remove, null, tint = Color.Cyan)
                    }
                    Text(
                        "Season $selectedSeason", color = Color.White, fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f), textAlign = TextAlign.Center
                    )
                    IconButton(onClick = { onSeasonSelected(selectedSeason + 1) }) {
                        Icon(Icons.Rounded.Add, null, tint = Color.Cyan)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = Color.White.copy(0.08f))
            Spacer(Modifier.height(8.dp))

            // ── Episode list ─────────────────────────────────────────
            Box(Modifier.heightIn(max = 420.dp)) {
                when {
                    isLoadingEpisodes -> Box(
                        Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = Color.Cyan, modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                    }
                    episodes.isEmpty() -> Box(
                        Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center
                    ) {
                        Text("No episode metadata — pick manually below", color = Color.Gray, fontSize = 12.sp)
                    }
                    else -> LazyColumn(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(episodes) { ep ->
                            val isSelected = ep.episodeNumber == selectedEpisode
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .background(
                                        if (isSelected) Color(0xFF003333) else Color(0xFF12121C),
                                        RoundedCornerShape(10.dp)
                                    )
                                    .border(
                                        1.dp,
                                        if (isSelected) Color.Cyan else Color.White.copy(0.07f),
                                        RoundedCornerShape(10.dp)
                                    )
                                    .clickable { onEpisodeSelected(ep.episodeNumber) }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    Modifier.size(28.dp).background(Color(0xFF0A0A1A), RoundedCornerShape(6.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("${ep.episodeNumber}", color = Color.Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        ep.name ?: "Episode ${ep.episodeNumber}",
                                        color = Color.White, fontSize = 13.sp,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                    if (ep.runtime != null) {
                                        Text("${ep.runtime} min", color = Color.Gray, fontSize = 11.sp)
                                    }
                                }
                                if (isSelected) {
                                    Icon(Icons.Rounded.CheckCircle, null, tint = Color.Cyan, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }

            // Manual fallback stepper — always available even with a full
            // episode list, for shows whose TMDB episode count is wrong/lagging.
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                IconButton(onClick = { if (selectedEpisode > 1) onEpisodeSelected(selectedEpisode - 1) }) {
                    Icon(Icons.Rounded.Remove, null, tint = Color.Gray)
                }
                Text("Ep. $selectedEpisode", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp))
                IconButton(onClick = { onEpisodeSelected(selectedEpisode + 1) }) {
                    Icon(Icons.Rounded.Add, null, tint = Color.Gray)
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
//  Torrent result card (unchanged)
// ═══════════════════════════════════════════════════════════════════════════

@Composable
fun TorrentCard(link: StreamLink, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0F0F1A), RoundedCornerShape(10.dp))
            .border(1.dp, Color.White.copy(0.07f), RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(44.dp)) {
            val seedColor = if (link.seeds > 20) Color.Green else if (link.seeds > 5) Color.Yellow else Color.Red
            Icon(Icons.Rounded.ArrowUpward, null, tint = seedColor, modifier = Modifier.size(18.dp))
            Text("${link.seeds}", color = seedColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text("seeds", color = Color.Gray, fontSize = 9.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(link.title, color = Color.White, fontSize = 13.sp,
                fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(5.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val (qbg, qfg) = when (link.quality) {
                    "4K","2160P" -> Color(0xFF1A0A00) to Color(0xFFFF6D00)
                    "1080P"      -> Color(0xFF001A0A) to Color(0xFF00E676)
                    "720P"       -> Color(0xFF001A1A) to Color(0xFF00E5FF)
                    else         -> Color(0xFF1A1A1A) to Color.Gray
                }
                Box(Modifier.background(qbg, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                    Text(link.quality, color = qfg, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
                Box(
                    Modifier.background(Color(0xFF0A0A1A), RoundedCornerShape(4.dp))
                        .border(1.dp, Color.Cyan.copy(0.2f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) { Text(link.source.take(8), color = Color.Cyan.copy(0.8f), fontSize = 9.sp) }
                link.audioTag?.let { tag ->
                    Box(
                        Modifier.background(Color(0xFF1A0A1A), RoundedCornerShape(4.dp))
                            .border(1.dp, Color(0xFFFF4FD8).copy(0.3f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(tag.label, color = Color(0xFFFF4FD8), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }
                if (link.size.isNotEmpty()) {
                    Text(link.size, color = Color.Gray, fontSize = 10.sp,
                        modifier = Modifier.align(Alignment.CenterVertically))
                }
            }
        }
        Icon(Icons.Rounded.PlayCircle, null, tint = Color.Cyan, modifier = Modifier.size(30.dp))
    }
}
