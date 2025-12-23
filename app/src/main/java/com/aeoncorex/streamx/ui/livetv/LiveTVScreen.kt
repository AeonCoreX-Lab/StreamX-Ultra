package com.aeoncorex.streamx.ui.livetv

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.aeoncorex.streamx.ui.home.HomeViewModel
import com.aeoncorex.streamx.ui.home.SectionTitle
import com.aeoncorex.streamx.ui.home.SmallChannelCard
import com.aeoncorex.streamx.util.ChannelGenre

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveTVScreen(
    navController: NavController,
    homeViewModel: HomeViewModel = viewModel()
) {
    val allChannels by homeViewModel.allChannels.collectAsState()
    
    val genres = remember { ChannelGenre.values().filterNot { it == ChannelGenre.UNKNOWN } }
    var selectedGenre by remember { mutableStateOf<ChannelGenre?>(null) }

    // Pre-calculate derived state so we don't do complex logic inside the LazyColumn composition
    val channelsByCountry = remember(allChannels, selectedGenre) {
        val filteredChannels = if (selectedGenre != null) {
            allChannels.filter { it.genre == selectedGenre }
        } else {
            allChannels
        }
        filteredChannels.groupBy { it.country }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Live TV") }) }
    ) { padding ->
        LazyColumn(contentPadding = padding) {
            item {
                SectionTitle("Explore by Genre", onSeeAllClick = {})
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(items = genres, key = { it.name }) { genre ->
                        FilterChip(
                            selected = selectedGenre == genre,
                            onClick = { selectedGenre = if (selectedGenre == genre) null else genre },
                            label = { Text(genre.name.lowercase().replaceFirstChar { it.uppercase() }) }
                        )
                    }
                }
            }

            // Iterate over the map directly in the LazyColumn scope
            channelsByCountry.forEach { (country, channels) ->
                if (channels.isNotEmpty()) {
                    item {
                        SectionTitle(country, onSeeAllClick = { /* ... */ })
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            items(items = channels.take(8), key = { it.id }) { channel ->
                                SmallChannelCard(channel = channel, onClick = { /* ... */ })
                            }
                        }
                    }
                }
            }
        }
    }
}
