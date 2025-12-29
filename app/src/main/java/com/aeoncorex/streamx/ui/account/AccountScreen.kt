package com.aeoncorex.streamx.ui.account

import android.widget.Toast
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.aeoncorex.streamx.R
import com.aeoncorex.streamx.ui.home.CyberMeshBackground
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    
    // Auth Data
    val user = Firebase.auth.currentUser
    var displayName by remember { mutableStateOf(user?.displayName ?: "User") }
    var photoUrl by remember { mutableStateOf(user?.photoUrl?.toString() ?: "") }
    val email = user?.email ?: "No Email"
    
    // Auth Provider Check
    val providerId = user?.providerData?.get(1)?.providerId ?: "password" // 0 is firebase, 1 is actual provider
    val providerName = when {
        providerId.contains("google.com") -> "Google Account"
        providerId.contains("github.com") -> "GitHub Profile"
        providerId.contains("facebook.com") -> "Facebook Profile"
        else -> "Email/Password"
    }
    val providerIcon = when {
        providerId.contains("google.com") -> R.drawable.ic_google // আপনার প্রোজেক্টে থাকা আইকন দিন
        providerId.contains("github.com") -> R.drawable.ic_github
        else -> null
    }

    // Dialog States
    var showNameDialog by remember { mutableStateOf(false) }
    var showPhotoDialog by remember { mutableStateOf(false) }
    var tempInput by remember { mutableStateOf("") }

    Box(modifier = Modifier.fillMaxSize()) {
        CyberMeshBackground()
        
        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("NEURAL IDENTITY", color = Color.White, fontWeight = FontWeight.Black) },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White)
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent)
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier.padding(padding).padding(20.dp).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Profile Image with Click to Update
                Box(
                    Modifier.size(120.dp)
                        .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                        .clickable { 
                            tempInput = photoUrl
                            showPhotoDialog = true 
                        }
                        .padding(5.dp)
                ) {
                    AsyncImage(
                        model = if (photoUrl.isEmpty()) "https://ui-avatars.com/api/?name=$displayName&background=00FFFF&color=000" else photoUrl,
                        contentDescription = "Profile",
                        modifier = Modifier.fillMaxSize().clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                    Box(Modifier.align(Alignment.BottomEnd).background(MaterialTheme.colorScheme.primary, CircleShape).padding(4.dp)) {
                        Icon(Icons.Default.Edit, null, modifier = Modifier.size(12.dp), tint = Color.Black)
                    }
                }

                Spacer(Modifier.height(24.dp))

                // Editable Username Card
                EditableInfoCard("Username", displayName, Icons.Default.Person) {
                    tempInput = displayName
                    showNameDialog = true
                }

                // Email Card (Non-editable for safety)
                InfoCard("Connected Email", email, Icons.Default.Email)

                // Real-time Provider Info
                InfoCard("Auth Provider", providerName, Icons.Default.VpnKey)

                Spacer(Modifier.height(30.dp))

                // Logout
                Button(
                    onClick = { 
                        Firebase.auth.signOut()
                        navController.navigate("auth") { popUpTo("home") { inclusive = true } }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF0055)),
                    modifier = Modifier.fillMaxWidth().height(55.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("TERMINATE SESSION", fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }

        // --- NAME UPDATE DIALOG ---
        if (showNameDialog) {
            AlertDialog(
                onDismissRequest = { showNameDialog = false },
                title = { Text("Change Username") },
                text = { OutlinedTextField(value = tempInput, onValueChange = { tempInput = it }, label = { Text("New Name") }) },
                confirmButton = {
                    TextButton(onClick = {
                        val profileUpdates = UserProfileChangeRequest.Builder().setDisplayName(tempInput).build()
                        user?.updateProfile(profileUpdates)?.addOnCompleteListener { task ->
                            if (task.isSuccessful) {
                                displayName = tempInput
                                scope.launch { snackbarHostState.showSnackbar("Username Updated Successfully!") }
                            }
                        }
                        showNameDialog = false
                    }) { Text("Update") }
                }
            )
        }

        // --- PHOTO URL UPDATE DIALOG ---
        if (showPhotoDialog) {
            AlertDialog(
                onDismissRequest = { showPhotoDialog = false },
                title = { Text("Update Profile Picture") },
                text = { OutlinedTextField(value = tempInput, onValueChange = { tempInput = it }, label = { Text("Image URL") }) },
                confirmButton = {
                    TextButton(onClick = {
                        val profileUpdates = UserProfileChangeRequest.Builder().setPhotoUri(android.net.Uri.parse(tempInput)).build()
                        user?.updateProfile(profileUpdates)?.addOnCompleteListener { task ->
                            if (task.isSuccessful) {
                                photoUrl = tempInput
                                scope.launch { snackbarHostState.showSnackbar("Profile Picture Updated!") }
                            }
                        }
                        showPhotoDialog = false
                    }) { Text("Update") }
                }
            )
        }
    }
}

@Composable
private fun EditableInfoCard(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onEdit: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(0.05f)).border(1.dp, Color.White.copy(0.1f), RoundedCornerShape(16.dp))
            .clickable { onEdit() }.padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title.uppercase(), fontSize = 10.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                Text(subtitle, fontSize = 16.sp, color = Color.White, fontWeight = FontWeight.Medium)
            }
            Icon(Icons.Default.Edit, null, tint = Color.Gray, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun InfoCard(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(0.03f)).border(1.dp, Color.White.copy(0.05f), RoundedCornerShape(16.dp)).padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(16.dp))
            Column {
                Text(title.uppercase(), fontSize = 10.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                Text(subtitle, fontSize = 16.sp, color = Color.White.copy(0.7f), fontWeight = FontWeight.Medium)
            }
        }
    }
}
