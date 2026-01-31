package com.aeoncorex.streamx.ui.auth

import android.app.Activity
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.aeoncorex.streamx.R
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.*
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

enum class AuthScreenState { LOGIN, SIGN_UP }

// --- Futuristic Theme Colors ---
val DarkBackground = Color(0xFF0A0A1E)
val CardBackground = Color(0xFF12122A)
val NeonCyan = Color(0xFF00E5FF)
val NeonPurple = Color(0xFFAA00FF)
val TextWhite = Color(0xFFEEEEEE)
val TextGray = Color(0xFFAAAAAA)

@Composable
fun AuthScreen(navController: NavController) {
    var authState by rememberSaveable { mutableStateOf(AuthScreenState.LOGIN) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // সফল লগইনের পর মেসেজ দেখানো এবং নেভিগেট করার ফাংশন
    fun onAuthSuccess(message: String) {
        scope.launch {
            snackbarHostState.showSnackbar(
                message = message,
                duration = SnackbarDuration.Short
            )
            // মেসেজ দেখানোর একটু পর নেভিগেট করবে
            kotlinx.coroutines.delay(1000)
            navController.navigate("home") { popUpTo("auth") { inclusive = true } }
        }
    }

    fun onAuthError(message: String) {
        scope.launch {
            snackbarHostState.showSnackbar(message = "Error: $message")
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(colors = listOf(DarkBackground, Color.Black)))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // নতুন বড় লোগো
            Image(
                painter = painterResource(id = R.drawable.streamx_ultra_logo),
                contentDescription = "App Logo",
                modifier = Modifier.height(120.dp),
                contentScale = ContentScale.Fit
            )
            Spacer(modifier = Modifier.height(32.dp))

            // অথেন্টিকেশন কার্ড
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = CardBackground),
                elevation = CardDefaults.cardElevation(defaultElevation = 10.dp)
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Crossfade(
                        targetState = authState,
                        animationSpec = tween(500),
                        label = "auth_crossfade"
                    ) { state ->
                        when (state) {
                            AuthScreenState.LOGIN -> LoginContent(
                                onSwitchToSignUp = { authState = AuthScreenState.SIGN_UP },
                                onSuccess = { onAuthSuccess("Welcome back! Login Successful.") },
                                onError = { onAuthError(it) }
                            )
                            AuthScreenState.SIGN_UP -> SignUpContent(
                                onSwitchToLogin = { authState = AuthScreenState.LOGIN },
                                onSuccess = { onAuthSuccess("Account Created Successfully!") },
                                onError = { onAuthError(it) }
                            )
                        }
                    }
                }
            }
        }
        
        // Snackbar Host for messages
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp)
        ) { data ->
            Snackbar(
                snackbarData = data,
                containerColor = CardBackground,
                contentColor = NeonCyan,
                shape = RoundedCornerShape(12.dp)
            )
        }
    }
}

@Composable
fun LoginContent(onSwitchToSignUp: () -> Unit, onSuccess: () -> Unit, onError: (String) -> Unit) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Sign In", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = TextWhite)
        Text("Access your futuristic stream.", fontSize = 14.sp, color = TextGray)
        Spacer(modifier = Modifier.height(32.dp))
        
        FuturisticTextField(value = email, onValueChange = { email = it }, label = "Email", icon = Icons.Default.Email, keyboardType = KeyboardType.Email)
        Spacer(modifier = Modifier.height(16.dp))
        FuturisticTextField(value = password, onValueChange = { password = it }, label = "Password", icon = Icons.Default.Lock, isPassword = true)
        Spacer(modifier = Modifier.height(32.dp))
        
        FuturisticButton(
            text = "LOGIN",
            isLoading = isLoading,
            onClick = {
                if (email.isNotBlank() && password.isNotBlank()) {
                    isLoading = true
                    scope.launch {
                        try {
                            Firebase.auth.signInWithEmailAndPassword(email, password).await()
                            onSuccess()
                        } catch (e: Exception) {
                            onError(e.message ?: "Login failed")
                            isLoading = false
                        }
                    }
                } else {
                    onError("Please fill all fields")
                }
            }
        )
        
        AuthFooter(
            promptText = "New here? ",
            actionText = "Create Account",
            onActionClick = onSwitchToSignUp,
            onSuccess = onSuccess,
            onError = onError
        )
    }
}

@Composable
fun SignUpContent(onSwitchToLogin: () -> Unit, onSuccess: () -> Unit, onError: (String) -> Unit) {
    var username by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Create Account", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = TextWhite)
        Text("Join the future of streaming.", fontSize = 14.sp, color = TextGray)
        Spacer(modifier = Modifier.height(32.dp))

        FuturisticTextField(value = username, onValueChange = { username = it }, label = "Username", icon = Icons.Default.Person)
        Spacer(modifier = Modifier.height(16.dp))
        FuturisticTextField(value = email, onValueChange = { email = it }, label = "Email", icon = Icons.Default.Email, keyboardType = KeyboardType.Email)
        Spacer(modifier = Modifier.height(16.dp))
        FuturisticTextField(value = password, onValueChange = { password = it }, label = "Password", icon = Icons.Default.Lock, isPassword = true)
        Spacer(modifier = Modifier.height(32.dp))

        FuturisticButton(
            text = "SIGN UP",
            isLoading = isLoading,
            onClick = {
                if (username.isNotBlank() && email.isNotBlank() && password.isNotBlank()) {
                    isLoading = true
                    scope.launch {
                        try {
                            val result = Firebase.auth.createUserWithEmailAndPassword(email, password).await()
                            val user = result.user
                            val profileUpdates = UserProfileChangeRequest.Builder().setDisplayName(username).build()
                            user?.updateProfile(profileUpdates)?.await()
                            onSuccess()
                        } catch (e: Exception) {
                            onError(e.message ?: "Sign up failed")
                            isLoading = false
                        }
                    }
                } else {
                    onError("Please fill all fields")
                }
            }
        )
        
        AuthFooter(
            promptText = "Already a member? ",
            actionText = "Login Now",
            onActionClick = onSwitchToLogin,
            onSuccess = onSuccess,
            onError = onError
        )
    }
}

@Composable
fun AuthFooter(promptText: String, actionText: String, onActionClick: () -> Unit, onSuccess: () -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    
    // Google Sign-In Logic
    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        scope.launch {
            handleGoogleSignInResult(result, onSuccess, onError)
        }
    }
    
    val googleSignInClient = remember {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(context.getString(R.string.default_web_client_id))
            .requestEmail()
            .build()
        GoogleSignIn.getClient(context, gso)
    }

    Spacer(modifier = Modifier.height(24.dp))
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(modifier = Modifier.weight(1f), color = CardBackground)
        Text(" OR CONTINUE WITH ", color = TextGray, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp))
        HorizontalDivider(modifier = Modifier.weight(1f), color = CardBackground)
    }
    Spacer(modifier = Modifier.height(24.dp))
    
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        // Google Button
        SocialLoginButton(iconRes = R.drawable.google_logo, onClick = { googleSignInLauncher.launch(googleSignInClient.signInIntent) })
        Spacer(modifier = Modifier.width(24.dp))
        // GitHub Button
        SocialLoginButton(iconRes = R.drawable.github_logo, onClick = { if (activity != null) signInWithGitHub(activity, onSuccess, onError) })
    }
    
    Spacer(modifier = Modifier.height(32.dp))
    val annotatedString = buildAnnotatedString {
        withStyle(style = SpanStyle(color = TextGray)) { append(promptText) }
        withStyle(style = SpanStyle(color = NeonCyan, fontWeight = FontWeight.Bold)) { append(actionText) }
    }
    Text(
        text = annotatedString,
        modifier = Modifier.clickable { onActionClick() }
    )
}

// --- Custom Futuristic UI Components ---

@Composable
fun FuturisticTextField(
    value: String, onValueChange: (String) -> Unit, label: String, icon: ImageVector,
    isPassword: Boolean = false, keyboardType: KeyboardType = KeyboardType.Text
) {
    var passwordVisible by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = NeonCyan) },
        trailingIcon = if (isPassword) {
            {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        contentDescription = if (passwordVisible) "Hide password" else "Show password",
                        tint = TextGray
                    )
                }
            }
        } else null,
        shape = RoundedCornerShape(12.dp),
        singleLine = true,
        visualTransformation = if (isPassword && !passwordVisible) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (isPassword) KeyboardType.Password else keyboardType),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
            focusedBorderColor = NeonCyan,
            unfocusedBorderColor = TextGray.copy(alpha = 0.5f),
            focusedTextColor = TextWhite,
            unfocusedTextColor = TextWhite,
            focusedLabelColor = NeonCyan,
            unfocusedLabelColor = TextGray,
            cursorColor = NeonCyan
        )
    )
}

@Composable
fun FuturisticButton(text: String, isLoading: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .background(
                brush = Brush.horizontalGradient(colors = listOf(NeonCyan, NeonPurple)),
                shape = RoundedCornerShape(16.dp)
            ),
        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
        shape = RoundedCornerShape(16.dp),
        enabled = !isLoading,
        contentPadding = PaddingValues() // Gradient দেখানোর জন্য প্যাডিং জিরো করা
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AnimatedVisibility(visible = isLoading, enter = fadeIn(), exit = fadeOut()) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = TextWhite, strokeWidth = 2.dp)
            }
            AnimatedVisibility(visible = !isLoading, enter = fadeIn(), exit = fadeOut()) {
                Text(text, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextWhite, letterSpacing = 1.sp)
            }
        }
    }
}

@Composable
fun SocialLoginButton(iconRes: Int, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(50.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(CardBackground)
            .border(1.dp, TextGray.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        // আইকনের কালার সাদা বা হালকা রাখা হয়েছে ডার্ক ব্যাকগ্রাউন্ডের জন্য
        Image(
            painter = painterResource(id = iconRes),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            // যদি আইকনগুলো রঙিন হয় তবে tint রিমুভ করতে পারেন, সাদা-কালো হলে tint ব্যবহার করুন
            // colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(TextWhite) 
        )
    }
}

// --- Helper Functions for Authentication (Updated for Coroutines) ---

private fun signInWithGitHub(activity: Activity, onSuccess: () -> Unit, onError: (String) -> Unit) {
    val provider = OAuthProvider.newBuilder("github.com").build()
    Firebase.auth.startActivityForSignInWithProvider(activity, provider)
        .addOnSuccessListener {
            Log.d("AuthScreen", "GitHub sign-in successful.")
            onSuccess()
        }
        .addOnFailureListener { e ->
            Log.w("AuthScreen", "GitHub sign-in failed.", e)
            onError(e.message ?: "GitHub Sign-in Failed")
        }
}

private suspend fun handleGoogleSignInResult(result: ActivityResult, onSuccess: () -> Unit, onError: (String) -> Unit) {
    try {
        val account = GoogleSignIn.getSignedInAccountFromIntent(result.data).await()
        val credential = GoogleAuthProvider.getCredential(account.idToken!!, null)
        Firebase.auth.signInWithCredential(credential).await()
        Log.d("AuthScreen", "Google sign-in to Firebase successful.")
        onSuccess()
    } catch (e: Exception) {
        Log.w("AuthScreen", "Google sign-in failed.", e)
        onError(e.message ?: "Google Sign-in Failed.")
    }
}
