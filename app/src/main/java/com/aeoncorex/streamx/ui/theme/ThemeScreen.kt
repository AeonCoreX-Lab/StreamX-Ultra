package com.aeoncorex.streamx.ui.theme

import androidx.compose.animation.animateColor
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeScreen(navController: NavController, themeViewModel: ThemeViewModel) {
    val infiniteTransition = rememberInfiniteTransition(label = "bg")
    val color1 by infiniteTransition.animateColor(
        initialValue = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
        targetValue = MaterialTheme.colorScheme.secondary.copy(alpha = 0.3f),
        animationSpec = infiniteRepeatable(tween(10000), RepeatMode.Reverse), label = "color"
    )

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(color1, Color.Transparent))).blur(80.dp))

        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("Change Theme", fontWeight = FontWeight.Bold) },
                    navigationIcon = { IconButton(onClick = { navController.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent)
                )
            },
            containerColor = Color.Transparent
        ) { padding ->
            LazyColumn(contentPadding = padding, modifier = Modifier.padding(16.dp)) {
                items(themes) { theme ->
                    val isSelected = themeViewModel.theme.value.name == theme.name
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).clickable { themeViewModel.changeTheme(theme.name) },
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = if (isSelected) theme.colorScheme.primary.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.1f))
                    ) {
                        Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(theme.name.name.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() }, fontWeight = FontWeight.Bold, color = theme.colorScheme.primary)
                            if (isSelected) Icon(Icons.Default.CheckCircle, null, tint = theme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}