package com.aeoncorex.streamx.ui.account

import android.widget.Toast
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.aeoncorex.streamx.R
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(navController: NavController) {
    val context = LocalContext.current
    val auth = Firebase.auth
    val currentUser = auth.currentUser
    var isEditingName by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf(currentUser?.displayName ?: "") }

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
                    title = { Text("My Account", fontWeight = FontWeight.Bold) },
                    navigationIcon = { IconButton(onClick = { navController.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent)
                )
            },
            containerColor = Color.Transparent
        ) { padding ->
            if (currentUser == null) return@Scaffold

            Column(
                modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                AsyncImage(
                    model = currentUser.photoUrl, contentDescription = null, placeholder = painterResource(id = R.mipmap.ic_launcher),
                    modifier = Modifier.size(120.dp).clip(CircleShape).border(2.dp, MaterialTheme.colorScheme.primary, CircleShape), contentScale = ContentScale.Crop
                )
                Spacer(modifier = Modifier.height(24.dp))

                if (isEditingName) {
                    OutlinedTextField(value = newName, onValueChange = { newName = it }, label = { Text("New Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(8.dp))
                    Row {
                        Button(onClick = {
                            currentUser.updateProfile(UserProfileChangeRequest.Builder().setDisplayName(newName).build()).addOnCompleteListener { 
                                if(it.isSuccessful) { Toast.makeText(context, "Updated", Toast.LENGTH_SHORT).show(); isEditingName = false }
                            }
                        }) { Text("Save") }
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(onClick = { isEditingName = false }) { Text("Cancel") }
                    }
                } else {
                    InfoCard(Icons.Default.Person, "Name", currentUser.displayName ?: "Set Name") { isEditingName = true }
                }
                Spacer(modifier = Modifier.height(12.dp))
                InfoCard(Icons.Default.Email, "Email", currentUser.email ?: "N/A", null)
                
                Spacer(modifier = Modifier.height(32.dp))
                Button(
                    onClick = { auth.signOut(); navController.navigate("auth") { popUpTo("home") { inclusive = true } } },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.8f))
                ) {
                    Icon(Icons.AutoMirrored.Filled.ExitToApp, null); Spacer(modifier = Modifier.width(8.dp)); Text("Logout")
                }
            }
        }
    }
}

@Composable
fun InfoCard(icon: ImageVector, title: String, subtitle: String, onClick: (() -> Unit)?) {
    Card(
        modifier = Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.1f))
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(16.dp))
            Column { Text(title, style = MaterialTheme.typography.labelMedium); Text(subtitle, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold) }
        }
    }
}