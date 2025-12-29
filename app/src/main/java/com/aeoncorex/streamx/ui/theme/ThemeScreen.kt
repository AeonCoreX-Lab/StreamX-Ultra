package com.aeoncorex.streamx.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.aeoncorex.streamx.ui.home.CyberMeshBackground

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeScreen(navController: NavController, themeViewModel: ThemeViewModel) {
    Box(modifier = Modifier.fillMaxSize()) {
        CyberMeshBackground()
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("NEURAL THEMES", color = Color(0xFFBC13FE), fontWeight = FontWeight.Black) },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White)
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent)
                )
            }
        ) { padding ->
            LazyColumn(contentPadding = padding, modifier = Modifier.padding(16.dp)) {
                items(themes) { theme ->
                    val isSelected = themeViewModel.theme.value.name == theme.name
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(if(isSelected) theme.colorScheme.primary.copy(0.15f) else Color.White.copy(0.05f))
                            .border(1.dp, if(isSelected) theme.colorScheme.primary else Color.White.copy(0.1f), RoundedCornerShape(20.dp))
                            .clickable { themeViewModel.changeTheme(theme.name) }
                            .padding(20.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(12.dp).background(theme.colorScheme.primary, RoundedCornerShape(2.dp)))
                                Spacer(Modifier.width(16.dp))
                                Text(
                                    text = theme.name.name.replace("_", " "),
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                )
                            }
                            if (isSelected) Icon(Icons.Default.CheckCircle, null, tint = theme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}
