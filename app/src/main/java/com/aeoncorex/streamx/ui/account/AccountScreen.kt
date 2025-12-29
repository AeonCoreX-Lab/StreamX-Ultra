package com.aeoncorex.streamx.ui.account

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.aeoncorex.streamx.R
import com.aeoncorex.streamx.ui.home.CyberMeshBackground

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(navController: NavController) {
    Box(modifier = Modifier.fillMaxSize()) {
        CyberMeshBackground()
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("My Profile", color = Color.White, fontWeight = FontWeight.Black) },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White)
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent)
                )
            }
        ) { padding ->
            Column(modifier = Modifier.padding(padding).padding(20.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                // Profile Image with Glow
                Box(Modifier.size(110.dp).border(2.dp, Color(0xFF00FFFF), CircleShape).padding(5.dp)) {
                    AsyncImage(
                        model = "https://ui-avatars.com/api/?name=User&background=00FFFF&color=000",
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize().clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                }
                
                Spacer(Modifier.height(24.dp))
                
                // Info Cards
                InfoCard("Identity", "Streamer_0X24", Icons.Default.Person)
                InfoCard("Network Address", "user@streamx.core", Icons.Default.Email)
                InfoCard("Access Level", "Premium Neural Link", Icons.Default.VpnKey)
                
                Spacer(Modifier.height(30.dp))
                
                Button(
                    onClick = { /* Logout Logic */ },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF0055)),
                    modifier = Modifier.fillMaxWidth().height(55.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("TERMINATE SESSION", fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun InfoCard(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Box(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(0.05f)).border(1.dp, Color.White.copy(0.1f), RoundedCornerShape(16.dp)).padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Color(0xFF00FFFF), modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(16.dp))
            Column {
                Text(title.uppercase(), fontSize = 10.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                Text(subtitle, fontSize = 16.sp, color = Color.White, fontWeight = FontWeight.Medium)
            }
        }
    }
}
