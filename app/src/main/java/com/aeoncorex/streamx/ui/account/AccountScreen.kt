package com.aeoncorex.streamx.ui.account

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.aeoncorex.streamx.R
import com.aeoncorex.streamx.ai.PremiumManager
import com.aeoncorex.streamx.ui.home.CyberMeshBackground
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

// ── Design tokens (match PremiumScreen) ───────────────────────────
private val Purple      = Color(0xFF7C4DFF)
private val PurpleLight = Color(0xFFB388FF)
private val Gold        = Color(0xFFFFD700)
private val Cyan        = Color(0xFF00FFFF)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(navController: NavController) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // ── Auth state ────────────────────────────────────────────────
    val user        = Firebase.auth.currentUser
    var displayName by remember { mutableStateOf(user?.displayName ?: "User") }
    var photoUrl    by remember { mutableStateOf(user?.photoUrl?.toString() ?: "") }
    val email       = user?.email ?: "No Email"

    val providerId   = user?.providerData?.getOrNull(1)?.providerId ?: "password"
    val providerName = when {
        providerId.contains("google.com")   -> "Google Account"
        providerId.contains("github.com")   -> "GitHub Profile"
        providerId.contains("facebook.com") -> "Facebook Profile"
        else -> "Email / Password"
    }

    // ── Premium state — real-time Firestore listener ──────────────
    var isPremium      by remember { mutableStateOf(false) }
    var premiumExpiry  by remember { mutableStateOf<Long?>(null) }
    var premiumLoading by remember { mutableStateOf(true) }

    // Real-time listener: updates instantly when Firestore changes
    DisposableEffect(user?.uid) {
        val uid = user?.uid
        if (uid == null) { premiumLoading = false; return@DisposableEffect onDispose {} }

        val listener = Firebase.firestore.collection("users").document(uid)
            .addSnapshotListener { snapshot, _ ->
                premiumLoading = false
                isPremium     = snapshot?.getBoolean("isPremium")    ?: false
                premiumExpiry = snapshot?.getLong("premiumExpiry")
                // Sync in-memory cache
                if (isPremium) PremiumManager.invalidateCache()
            }
        onDispose { listener.remove() }
    }

    // ── Dialog states ──────────────────────────────────────────────
    var showNameDialog  by remember { mutableStateOf(false) }
    var showPhotoDialog by remember { mutableStateOf(false) }
    var tempInput       by remember { mutableStateOf("") }

    // ── UI ─────────────────────────────────────────────────────────
    Box(modifier = Modifier.fillMaxSize()) {
        CyberMeshBackground()

        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost   = { SnackbarHost(snackbarHostState) },
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Text("NEURAL IDENTITY", color = Color.White, fontWeight = FontWeight.Black)
                    },
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
                modifier = Modifier
                    .padding(padding)
                    .padding(horizontal = 20.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(8.dp))

                // ── Profile avatar ────────────────────────────────
                Box(
                    Modifier.size(120.dp)
                        .border(
                            2.dp,
                            if (isPremium)
                                Brush.sweepGradient(listOf(Gold, PurpleLight, Gold))
                            else
                                Brush.sweepGradient(listOf(Cyan, Cyan)),
                            CircleShape
                        )
                        .clickable { tempInput = photoUrl; showPhotoDialog = true }
                        .padding(5.dp)
                ) {
                    AsyncImage(
                        model = if (photoUrl.isEmpty())
                            "https://ui-avatars.com/api/?name=$displayName&background=00FFFF&color=000"
                        else photoUrl,
                        contentDescription = "Profile",
                        modifier = Modifier.fillMaxSize().clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                    // Edit badge
                    Box(
                        Modifier.align(Alignment.BottomEnd)
                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                            .padding(4.dp)
                    ) {
                        Icon(Icons.Default.Edit, null, modifier = Modifier.size(12.dp), tint = Color.Black)
                    }
                    // Premium crown badge
                    if (isPremium) {
                        Box(
                            Modifier.align(Alignment.TopEnd)
                                .background(Gold, CircleShape)
                                .padding(4.dp)
                        ) {
                            Icon(Icons.Rounded.WorkspacePremium, null, modifier = Modifier.size(14.dp), tint = Color.Black)
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Display name under avatar
                Text(displayName, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(email,       color = Color.Gray,  fontSize = 13.sp)

                Spacer(Modifier.height(24.dp))

                // ════════════════════════════════════════════════
                //  PREMIUM STATUS CARD — real-time
                // ════════════════════════════════════════════════
                if (premiumLoading) {
                    PremiumCardLoading()
                } else if (isPremium) {
                    PremiumActiveCard(expiryMs = premiumExpiry)
                } else {
                    PremiumUpgradeCard(onUpgrade = { navController.navigate("premium") })
                }

                Spacer(Modifier.height(20.dp))

                // ── Profile info cards ────────────────────────────
                EditableInfoCard("Username", displayName, Icons.Default.Person) {
                    tempInput = displayName; showNameDialog = true
                }
                InfoCard("Connected Email",  email,        Icons.Default.Email)
                InfoCard("Auth Provider",    providerName, Icons.Default.VpnKey)

                Spacer(Modifier.height(28.dp))

                // ── Sign out ──────────────────────────────────────
                Button(
                    onClick = {
                        PremiumManager.invalidateCache()
                        Firebase.auth.signOut()
                        navController.navigate("auth") { popUpTo("home") { inclusive = true } }
                    },
                    colors   = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF0055)),
                    modifier = Modifier.fillMaxWidth().height(55.dp),
                    shape    = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Logout, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("TERMINATE SESSION", fontWeight = FontWeight.Bold, color = Color.White)
                }

                Spacer(Modifier.height(32.dp))
            }
        }

        // ── Dialogs ───────────────────────────────────────────────
        if (showNameDialog) {
            AlertDialog(
                onDismissRequest = { showNameDialog = false },
                title   = { Text("Change Username") },
                text    = { OutlinedTextField(value = tempInput, onValueChange = { tempInput = it }, label = { Text("New Name") }) },
                confirmButton = {
                    TextButton(onClick = {
                        user?.updateProfile(
                            UserProfileChangeRequest.Builder().setDisplayName(tempInput).build()
                        )?.addOnCompleteListener { task ->
                            if (task.isSuccessful) {
                                displayName = tempInput
                                scope.launch { snackbarHostState.showSnackbar("Username Updated!") }
                            }
                        }
                        showNameDialog = false
                    }) { Text("Update") }
                }
            )
        }

        if (showPhotoDialog) {
            AlertDialog(
                onDismissRequest = { showPhotoDialog = false },
                title   = { Text("Update Profile Picture") },
                text    = { OutlinedTextField(value = tempInput, onValueChange = { tempInput = it }, label = { Text("Image URL") }) },
                confirmButton = {
                    TextButton(onClick = {
                        user?.updateProfile(
                            UserProfileChangeRequest.Builder()
                                .setPhotoUri(android.net.Uri.parse(tempInput)).build()
                        )?.addOnCompleteListener { task ->
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

// ═══════════════════════════════════════════════════════════════════
//  Premium Status Cards
// ═══════════════════════════════════════════════════════════════════

/** Shimmer loading while Firestore responds */
@Composable
private fun PremiumCardLoading() {
    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f, targetValue = 0.7f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "alpha"
    )
    Box(
        Modifier.fillMaxWidth()
            .height(100.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha))
    )
}

/** User IS premium — shows plan + expiry date */
@Composable
private fun PremiumActiveCard(expiryMs: Long?) {
    val expiryText = expiryMs?.let {
        val sdf  = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
        "Active until ${sdf.format(Date(it))}"
    } ?: "Lifetime Access"

    val infiniteTransition = rememberInfiniteTransition(label = "glow")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.98f, targetValue = 1.02f,
        animationSpec = infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "scale"
    )

    Box(
        Modifier.fillMaxWidth().scale(scale)
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(listOf(Color(0xFF1C1040), Color(0xFF0D0818), Color(0xFF1A1030)))
            )
            .border(
                1.5.dp,
                Brush.horizontalGradient(listOf(Purple, Gold, PurpleLight)),
                RoundedCornerShape(20.dp)
            )
            .padding(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Crown icon
            Box(
                Modifier.size(56.dp)
                    .background(
                        Brush.radialGradient(listOf(Gold.copy(0.3f), Color.Transparent)),
                        CircleShape
                    )
                    .border(1.5.dp, Gold.copy(0.6f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.WorkspacePremium, null, tint = Gold, modifier = Modifier.size(28.dp))
            }

            Spacer(Modifier.width(16.dp))

            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("StreamX Premium", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.width(8.dp))
                    // Active badge
                    Box(
                        Modifier.clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF1B5E20))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text("ACTIVE", color = Color(0xFF69F0AE), fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(expiryText, color = Gold.copy(0.85f), fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                // Features row
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MiniFeatureChip("AI Vision")
                    MiniFeatureChip("Voice")
                    MiniFeatureChip("∞ Requests")
                }
            }
        }
    }
}

@Composable
private fun MiniFeatureChip(label: String) {
    Box(
        Modifier.clip(RoundedCornerShape(6.dp))
            .background(Purple.copy(0.2f))
            .border(0.5.dp, PurpleLight.copy(0.4f), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp)
    ) {
        Text(label, color = PurpleLight, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** User NOT premium — upgrade CTA */
@Composable
private fun PremiumUpgradeCard(onUpgrade: () -> Unit) {
    Box(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(listOf(Color(0xFF0F0A20), Color(0xFF0A0818)))
            )
            .border(1.dp, Purple.copy(0.4f), RoundedCornerShape(20.dp))
            .padding(20.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.WorkspacePremium, null, tint = Gold.copy(0.7f), modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Free Plan", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text("AI features locked", color = Color.Gray, fontSize = 12.sp)
                }
                // Lock badge
                Box(
                    Modifier.clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF3E2723))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text("FREE", color = Color(0xFFFF8A65), fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                }
            }

            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = Color.White.copy(0.06f))
            Spacer(Modifier.height(14.dp))

            // What they're missing
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                LockedFeatureItem(Icons.Rounded.AutoAwesome, "AI Vision")
                LockedFeatureItem(Icons.Rounded.RecordVoiceOver, "Voice")
                LockedFeatureItem(Icons.Rounded.AllInclusive, "Unlimited")
            }

            Spacer(Modifier.height(16.dp))

            // Upgrade button
            Button(
                onClick   = onUpgrade,
                modifier  = Modifier.fillMaxWidth().height(48.dp),
                colors    = ButtonDefaults.buttonColors(containerColor = Purple),
                shape     = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Rounded.WorkspacePremium, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Upgrade to Premium — $4.99/yr", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun LockedFeatureItem(icon: ImageVector, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(40.dp)
                .background(Color.White.copy(0.05f), CircleShape)
                .border(1.dp, Color.White.copy(0.08f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = Color.Gray, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = Color.Gray, fontSize = 10.sp)
    }
}

// ═══════════════════════════════════════════════════════════════════
//  Reusable info cards (same style as before)
// ═══════════════════════════════════════════════════════════════════
@Composable
private fun EditableInfoCard(title: String, subtitle: String, icon: ImageVector, onEdit: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(0.05f)).border(1.dp, Color.White.copy(0.1f), RoundedCornerShape(16.dp))
            .clickable { onEdit() }.padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title.uppercase(),  fontSize = 10.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                Text(subtitle, fontSize = 16.sp, color = Color.White, fontWeight = FontWeight.Medium)
            }
            Icon(Icons.Default.Edit, null, tint = Color.Gray, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun InfoCard(title: String, subtitle: String, icon: ImageVector) {
    Box(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(0.03f)).border(1.dp, Color.White.copy(0.05f), RoundedCornerShape(16.dp)).padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(16.dp))
            Column {
                Text(title.uppercase(),  fontSize = 10.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                Text(subtitle, fontSize = 16.sp, color = Color.White.copy(0.7f), fontWeight = FontWeight.Medium)
            }
        }
    }
}
