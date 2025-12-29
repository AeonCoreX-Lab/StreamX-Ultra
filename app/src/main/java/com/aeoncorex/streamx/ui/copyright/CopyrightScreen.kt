package com.aeoncorex.streamx.ui.copyright

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
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
fun CopyrightScreen(navController: NavController) {
    Box(modifier = Modifier.fillMaxSize()) {
        CyberMeshBackground()
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("LEGAL NOTICE", color = Color.White, fontWeight = FontWeight.Black) },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White)
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent)
                )
            }
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp).verticalScroll(rememberScrollState())) {
                DisclaimerPanel("Digital Rights Disclaimer", "StreamX Ultra acts solely as a media player. We do not host, store, or distribute any digital content. All streams provided are sourced from publicly available internet links.")
                Spacer(Modifier.height(16.dp))
                DisclaimerPanel("DMCA Compliance", "Any copyright infringement claims should be directed to the original content host. We provide automated link organization only.")
            }
        }
    }
}

@Composable
private fun DisclaimerPanel(title: String, content: String) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color.White.copy(0.05f))
            .border(1.dp, Color.White.copy(0.1f), RoundedCornerShape(20.dp)).padding(20.dp)
    ) {
        Column {
            Text(title.uppercase(), color = Color(0xFF00FFFF), fontWeight = FontWeight.Black, fontSize = 14.sp)
            Spacer(Modifier.height(12.dp))
            Text(content, color = Color.White.copy(0.7f), fontSize = 13.sp, lineHeight = 20.sp)
        }
    }
}
