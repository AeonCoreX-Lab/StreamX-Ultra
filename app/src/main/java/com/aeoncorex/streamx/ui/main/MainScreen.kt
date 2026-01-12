package com.aeoncorex.streamx.ui.main

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.aeoncorex.streamx.ui.home.LiveTVScreen
import com.aeoncorex.streamx.ui.movie.MovieHomeScreen
import com.aeoncorex.streamx.ui.music.MusicScreen

@Composable
fun MainScreen(navController: NavController) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val primaryColor = MaterialTheme.colorScheme.primary

    Scaffold(
        containerColor = Color.Black,
        bottomBar = {
            // --- Futuristic Floating Navigation Bar ---
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 20.dp)
                    .height(70.dp)
                    .shadow(20.dp, RoundedCornerShape(35.dp), ambientColor = primaryColor, spotColor = primaryColor)
                    .background(
                        brush = Brush.verticalGradient(
                            listOf(Color(0xFF1A1A1A).copy(alpha = 0.9f), Color.Black)
                        ),
                        shape = RoundedCornerShape(35.dp)
                    )
                    // [FIX] withAlpha deprecated chilo, copy(alpha = ...) use kora hoyeche
                    .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(35.dp)),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    NavItem(
                        icon = Icons.Default.Home,
                        label = "HOME",
                        isSelected = selectedTab == 0,
                        primaryColor = primaryColor,
                        onClick = { selectedTab = 0 }
                    )
                    
                    NavItem(
                        icon = Icons.Default.MusicNote,
                        label = "MUSIC",
                        isSelected = selectedTab == 1,
                        primaryColor = primaryColor,
                        onClick = { selectedTab = 1 }
                    )
                    
                    NavItem(
                        icon = Icons.Default.LiveTv,
                        label = "LIVE TV",
                        isSelected = selectedTab == 2,
                        primaryColor = primaryColor,
                        onClick = { selectedTab = 2 }
                    )
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 0.dp) 
        ) {
            when (selectedTab) {
                0 -> MovieHomeScreen(navController)
                1 -> MusicScreen(navController)
                2 -> LiveTVScreen(navController)
            }
        }
    }
}

@Composable
fun NavItem(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    primaryColor: Color,
    onClick: () -> Unit
) {
    val animatedColor by animateColorAsState(
        targetValue = if (isSelected) primaryColor else Color.Gray,
        animationSpec = tween(500),
        label = "color"
    )
    
    val animatedSize by animateDpAsState(
        targetValue = if (isSelected) 30.dp else 24.dp,
        animationSpec = tween(300),
        label = "size"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(8.dp)
    ) {
        if (isSelected) {
            Box(
                modifier = Modifier
                    .size(4.dp)
                    .background(primaryColor, CircleShape)
                    .shadow(10.dp, CircleShape, spotColor = primaryColor)
                    .blur(2.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
        }

        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = animatedColor,
            modifier = Modifier.size(animatedSize)
        )
        
        Text(
            text = label,
            color = animatedColor,
            fontSize = 10.sp,
            fontWeight = if (isSelected) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal,
            letterSpacing = 1.sp
        )
    }
}
