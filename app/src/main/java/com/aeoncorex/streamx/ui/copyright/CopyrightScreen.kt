package com.aeoncorex.streamx.ui.copyright

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CopyrightScreen(navController: NavController) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Copyright Disclaimer") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            DisclaimerSection(
                title = "Disclaimer for StreamX Ultra",
                content = "StreamX Ultra is an IPTV player application that provides users with access to publicly available, open-source streaming links. We do not host, own, create, or control any of the channels or content available through this application."
            )

            Spacer(modifier = Modifier.height(24.dp))

            DisclaimerSection(
                title = "Content Ownership",
                content = "All channels, logos, and trademarks displayed within the app are the property of their respective owners. The content provided is streamed directly from third-party servers that are publicly accessible on the internet. StreamX Ultra acts solely as an index or directory of these publicly available links."
            )

            Spacer(modifier = Modifier.height(24.dp))

            DisclaimerSection(
                title = "No Affiliation",
                content = "We are not affiliated with any of the TV channel providers or content owners. The inclusion of any link or channel does not imply endorsement or responsibility for its content."
            )
            
            Spacer(modifier = Modifier.height(24.dp))

            DisclaimerSection(
                title = "Copyright Concerns (DMCA)",
                content = "If you are a copyright owner and believe that any content available through our application infringes upon your copyright, please note that we do not host the content. Any takedown request must be sent to the hosting provider of the content. We are simply providing links that are already available on the internet in an organized manner."
            )

            Spacer(modifier = Modifier.height(32.dp))
            
            Text(
                text = "By using StreamX Ultra, you acknowledge and agree to these terms.",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun DisclaimerSection(title: String, content: String) {
    Column {
        Text(
            text = title,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = content,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}