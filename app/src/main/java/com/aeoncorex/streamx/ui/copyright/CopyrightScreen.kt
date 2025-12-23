package com.aeoncorex.streamx.ui.copyright

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.aeoncorex.streamx.ui.home.FuturisticBackground

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CopyrightScreen(navController: NavController) {
    Box(modifier = Modifier.fillMaxSize()) {
        FuturisticBackground()
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("Disclaimer", color = Color.White, fontWeight = FontWeight.Bold) },
                    navigationIcon = { IconButton(onClick = { navController.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) } },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent)
                )
            },
            containerColor = Color.Transparent
        ) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)
            ) {
                DisclaimerCard("Content Ownership", "StreamX Ultra does not host any content. All channels and logos are property of their respective owners. We only provide links to content readily available on the internet.")
                Spacer(modifier = Modifier.height(16.dp))
                DisclaimerCard("No Affiliation", "We are not affiliated with any TV channel providers. The inclusion of a link does not imply endorsement.")
                Spacer(modifier = Modifier.height(16.dp))
                DisclaimerCard("DMCA / Copyright", "If you believe any content infringes your copyright, please contact the hosting provider. We act as an index only.")
            }
        }
    }
}

@Composable
fun DisclaimerCard(title: String, content: String) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.1f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))
            Text(content, color = Color.White.copy(0.8f), lineHeight = 20.sp)
        }
    }
}