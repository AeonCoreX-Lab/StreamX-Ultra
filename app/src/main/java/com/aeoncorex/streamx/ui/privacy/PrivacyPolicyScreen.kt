package com.aeoncorex.streamx.ui.privacy

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.aeoncorex.streamx.ui.home.FuturisticBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyPolicyScreen(navController: NavController) {
    val privacyPolicyUrl = "https://raw.githubusercontent.com/cybernahid-dev/StreamX-Ultra/main/PRIVACY_POLICY.md"
    var policyText by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        try { policyText = withContext(Dispatchers.IO) { URL(privacyPolicyUrl).readText() } } 
        catch (e: Exception) { policyText = "Error loading policy." } 
        finally { isLoading = false }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        FuturisticBackground()
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("Privacy Policy", color = Color.White, fontWeight = FontWeight.Bold) },
                    navigationIcon = { IconButton(onClick = { navController.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) } },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent)
                )
            },
            containerColor = Color.Transparent
        ) { padding ->
            if (isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color.Cyan) }
            } else {
                Column(modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
                    policyText?.lines()?.forEach { line ->
                        when {
                            line.startsWith("# ") -> Text(line.removePrefix("# "), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp))
                            line.startsWith("## ") -> Text(line.removePrefix("## "), style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                            line.startsWith("* ") -> Text("• ${line.removePrefix("* ")}", color = Color.White.copy(0.8f), modifier = Modifier.padding(start = 8.dp))
                            else -> Text(line, color = Color.White.copy(0.7f), lineHeight = 20.sp)
                        }
                    }
                }
            }
        }
    }
}