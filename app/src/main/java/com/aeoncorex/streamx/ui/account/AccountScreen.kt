package com.aeoncorex.streamx.ui.account

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My Account") },
                navigationIcon = { IconButton(onClick = { navController.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }
            )
        }
    ) { padding ->
        if (currentUser == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { Text("No user is logged in.") }
            return@Scaffold
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AsyncImage(
                model = currentUser.photoUrl,
                contentDescription = "Profile Picture",
                placeholder = painterResource(id = R.mipmap.ic_launcher_foreground),
                modifier = Modifier.size(120.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
                contentScale = ContentScale.Crop
            )
            Spacer(modifier = Modifier.height(24.dp))

            if (isEditingName) {
                OutlinedTextField(value = newName, onValueChange = { newName = it }, label = { Text("Enter new name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
                Row {
                    Button(onClick = {
                        val profileUpdates = UserProfileChangeRequest.Builder().setDisplayName(newName).build()
                        currentUser.updateProfile(profileUpdates).addOnCompleteListener { task ->
                            if (task.isSuccessful) {
                                Toast.makeText(context, "Name updated successfully!", Toast.LENGTH_SHORT).show()
                                isEditingName = false
                            }
                        }
                    }) { Text("Save") }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(onClick = { isEditingName = false }) { Text("Cancel") }
                }
            } else {
                InfoCard(icon = Icons.Default.Person, title = "Display Name", subtitle = currentUser.displayName ?: "No name set", onClick = { isEditingName = true })
            }
            Spacer(modifier = Modifier.height(16.dp))

            InfoCard(icon = Icons.Default.Email, title = "Email", subtitle = currentUser.email ?: "No email available")
            Spacer(modifier = Modifier.height(16.dp))
            
            val providerId = currentUser.providerData.find { it.providerId != "firebase" }?.providerId
            val (providerIcon, providerName) = when (providerId) {
                "google.com" -> painterResource(id = R.drawable.google_logo) to "Google"
                "github.com" -> painterResource(id = R.drawable.github_logo) to "GitHub"
                "facebook.com" -> painterResource(id = R.drawable.facebook_logo) to "Facebook"
                else -> Icons.Default.AlternateEmail to "Email & Password"
            }
            ProviderInfoCard(icon = providerIcon, title = "Signed in with", subtitle = providerName)
            Spacer(modifier = Modifier.height(16.dp))

            if (providerId == "password") {
                InfoCard(icon = Icons.Default.VpnKey, title = "Change Password", subtitle = "Send password reset email", onClick = {
                    auth.sendPasswordResetEmail(currentUser.email!!).addOnCompleteListener { task ->
                        if (task.isSuccessful) {
                            Toast.makeText(context, "Password reset email sent!", Toast.LENGTH_SHORT).show()
                        }
                    }
                })
                Spacer(modifier = Modifier.height(16.dp))
            }
            
            Spacer(modifier = Modifier.height(32.dp))

            OutlinedButton(onClick = { auth.signOut(); navController.navigate("auth") { popUpTo("main_screen") { inclusive = true } } }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.AutoMirrored.Filled.ExitToApp, "Logout"); Spacer(modifier = Modifier.width(8.dp)); Text("Logout")
            }
        }
    }
}

@Composable
private fun InfoCard(icon: ImageVector, title: String, subtitle: String, onClick: (() -> Unit)? = null) {
    Card(
        modifier = Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, title, tint = MaterialTheme.colorScheme.primary); Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(title, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                Text(subtitle, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun ProviderInfoCard(icon: Any, title: String, subtitle: String) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon is ImageVector) Icon(icon, title, tint = MaterialTheme.colorScheme.primary)
            else if (icon is Painter) Image(painter = icon, title, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(title, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                Text(subtitle, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}