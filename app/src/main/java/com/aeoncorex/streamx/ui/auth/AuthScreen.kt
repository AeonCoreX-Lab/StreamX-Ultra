package com.aeoncorex.streamx.ui.auth

import android.app.Activity
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.aeoncorex.streamx.R
import com.aeoncorex.streamx.services.EmailService // Import the new service
import com.facebook.CallbackManager
import com.facebook.FacebookCallback
import com.facebook.FacebookException
import com.facebook.login.LoginManager
import com.facebook.login.LoginResult
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.*
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase

// --- Theme Colors for Ultimate Look ---
val StreamXRed = Color(0xFFE50914)
val DarkBackground = Color(0xFF0F0F0F)
val CardBackground = Color(0xFF1A1A1A)
val TextWhite = Color(0xFFFFFFFF)
val TextGray = Color(0xFFB3B3B3)

enum class AuthScreenState { LOGIN, SIGN_UP }

@Composable
fun AuthScreen(navController: NavController) {
    var authState by rememberSaveable { mutableStateOf(AuthScreenState.LOGIN) }

    // Cinematic Background Gradient
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Black, DarkBackground, Color(0xFF120203))
                )
            )
    ) {
        // Optional: Add a subtle background image here with low alpha if desired
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Logo Section
            Image(
                painter = painterResource(id = R.mipmap.ic_launcher),
                contentDescription = "StreamX Logo",
                modifier = Modifier
                    .size(100.dp)
                    .clip(RoundedCornerShape(20.dp)),
                contentScale = ContentScale.Crop
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = "STREAMX",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = StreamXRed,
                letterSpacing = 2.sp
            )

            Spacer(modifier = Modifier.height(40.dp))

            // Auth Card with Animation
            AnimatedContent(
                targetState = authState,
                transitionSpec = {
                    fadeIn(animationSpec = tween(300)) + slideInHorizontally { width -> width } togetherWith
                            fadeOut(animationSpec = tween(300)) + slideOutHorizontally { width -> -width }
                },
                label = "AuthTransition"
            ) { state ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = CardBackground),
                    shape = RoundedCornerShape(16.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    when (state) {
                        AuthScreenState.LOGIN -> LoginContent(
                            onSwitchToSignUp = { authState = AuthScreenState.SIGN_UP },
                            navController = navController
                        )
                        AuthScreenState.SIGN_UP -> SignUpContent(
                            onSwitchToLogin = { authState = AuthScreenState.LOGIN },
                            navController = navController
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun LoginContent(onSwitchToSignUp: () -> Unit, navController: NavController) {
    val context = LocalContext.current
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Welcome Back", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = TextWhite)
        Text("Enter your credentials to access", fontSize = 14.sp, color = TextGray)
        Spacer(modifier = Modifier.height(24.dp))
        
        UltimateTextField(value = email, onValueChange = { email = it }, label = "Email", icon = Icons.Default.Email, keyboardType = KeyboardType.Email)
        Spacer(modifier = Modifier.height(16.dp))
        UltimateTextField(value = password, onValueChange = { password = it }, label = "Password", icon = Icons.Default.Lock, isPassword = true)
        Spacer(modifier = Modifier.height(24.dp))
        
        UltimateButton(text = "Sign In", isLoading = isLoading) {
            if (email.isNotBlank() && password.isNotBlank()) {
                isLoading = true
                Firebase.auth.signInWithEmailAndPassword(email, password)
                    .addOnCompleteListener { task ->
                        isLoading = false
                        if (task.isSuccessful) {
                            // --- INTEGRATION: TRIGGER ALERT EMAIL ---
                            val user = Firebase.auth.currentUser
                            EmailService.sendLoginAlert(user?.email ?: email, user?.displayName ?: "User")
                            // ----------------------------------------
                            navController.navigate("home") { popUpTo("auth") { inclusive = true } }
                        } else {
                            Toast.makeText(context, "Error: ${task.exception?.message}", Toast.LENGTH_LONG).show()
                        }
                    }
            } else {
                Toast.makeText(context, "Fill all fields", Toast.LENGTH_SHORT).show()
            }
        }
        
        AuthFooter(
            text = "New to StreamX?",
            actionText = "Register now",
            onActionClick = onSwitchToSignUp,
            navController = navController
        )
    }
}

@Composable
fun SignUpContent(onSwitchToLogin: () -> Unit, navController: NavController) {
    val context = LocalContext.current
    var username by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Join StreamX", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = TextWhite)
        Text("Unlimited entertainment awaits", fontSize = 14.sp, color = TextGray)
        Spacer(modifier = Modifier.height(24.dp))

        UltimateTextField(value = username, onValueChange = { username = it }, label = "Username", icon = Icons.Default.Person)
        Spacer(modifier = Modifier.height(16.dp))
        UltimateTextField(value = email, onValueChange = { email = it }, label = "Email", icon = Icons.Default.Email, keyboardType = KeyboardType.Email)
        Spacer(modifier = Modifier.height(16.dp))
        UltimateTextField(value = password, onValueChange = { password = it }, label = "Password", icon = Icons.Default.Lock, isPassword = true)
        Spacer(modifier = Modifier.height(24.dp))

        UltimateButton(text = "Create Account", isLoading = isLoading) {
            if (username.isNotBlank() && email.isNotBlank() && password.isNotBlank()) {
                isLoading = true
                Firebase.auth.createUserWithEmailAndPassword(email, password)
                    .addOnCompleteListener { task ->
                        if (task.isSuccessful) {
                            val user = task.result?.user
                            val profileUpdates = UserProfileChangeRequest.Builder().setDisplayName(username).build()
                            user?.updateProfile(profileUpdates)?.addOnCompleteListener {
                                isLoading = false
                                // --- INTEGRATION: TRIGGER WELCOME EMAIL ---
                                EmailService.sendWelcomeEmail(email, username)
                                // ------------------------------------------
                                navController.navigate("home") { popUpTo("auth") { inclusive = true } }
                            }
                        } else {
                            isLoading = false
                            Toast.makeText(context, "Error: ${task.exception?.message}", Toast.LENGTH_LONG).show()
                        }
                    }
            } else {
                Toast.makeText(context, "Fill all fields", Toast.LENGTH_SHORT).show()
            }
        }
        
        AuthFooter(
            text = "Already a member?",
            actionText = "Login here",
            onActionClick = onSwitchToLogin,
            navController = navController
        )
    }
}

// --- Reusable Ultimate UI Components ---

@Composable
fun UltimateTextField(
    value: String, onValueChange: (String) -> Unit, label: String, icon: ImageVector,
    isPassword: Boolean = false, keyboardType: KeyboardType = KeyboardType.Text
) {
    var passwordVisible by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label, color = TextGray) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = StreamXRed) },
        trailingIcon = if (isPassword) {
            {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        imageVector = if (passwordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                        contentDescription = "Toggle Password",
                        tint = TextGray
                    )
                }
            }
        } else null,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = StreamXRed,
            unfocusedBorderColor = Color.DarkGray,
            focusedTextColor = TextWhite,
            unfocusedTextColor = TextWhite,
            cursorColor = StreamXRed,
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent
        ),
        shape = RoundedCornerShape(12.dp),
        singleLine = true,
        visualTransformation = if (isPassword && !passwordVisible) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (isPassword) KeyboardType.Password else keyboardType)
    )
}

@Composable
fun UltimateButton(text: String, isLoading: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp),
        colors = ButtonDefaults.buttonColors(containerColor = StreamXRed),
        shape = RoundedCornerShape(12.dp),
        enabled = !isLoading
    ) {
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), color = TextWhite, strokeWidth = 2.dp)
        } else {
            Text(text, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextWhite)
        }
    }
}

@Composable
fun AuthFooter(text: String, actionText: String, onActionClick: () -> Unit, navController: NavController) {
    val context = LocalContext.current
    val activity = context as? Activity
    
    // Social Login Launchers
    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result -> handleGoogleSignInResult(result, context) { 
        EmailService.sendLoginAlert(Firebase.auth.currentUser?.email ?: "", Firebase.auth.currentUser?.displayName ?: "User")
        navController.navigate("home") { popUpTo("auth") { inclusive = true } } 
    } }
    
    val googleSignInClient = remember {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(context.getString(R.string.default_web_client_id)).requestEmail().build()
        GoogleSignIn.getClient(context, gso)
    }

    val callbackManager = remember { CallbackManager.Factory.create() }
    val facebookLoginLauncher = rememberLauncherForActivityResult(
        contract = LoginManager.getInstance().createLogInActivityResultContract(callbackManager, null)
    ) { /* Handled by callback */ }

    DisposableEffect(Unit) {
        LoginManager.getInstance().registerCallback(callbackManager, object : FacebookCallback<LoginResult> {
            override fun onSuccess(result: LoginResult) {
                handleFacebookAccessToken(result.accessToken) {
                    EmailService.sendLoginAlert(Firebase.auth.currentUser?.email ?: "", Firebase.auth.currentUser?.displayName ?: "User")
                    navController.navigate("home") { popUpTo("auth") { inclusive = true } }
                }
            }
            override fun onCancel() { Log.w("AuthScreen", "Facebook canceled.") }
            override fun onError(error: FacebookException) { Toast.makeText(context, "Facebook Error", Toast.LENGTH_SHORT).show() }
        })
        onDispose { LoginManager.getInstance().unregisterCallback(callbackManager) }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(modifier = Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            HorizontalDivider(modifier = Modifier.weight(1f), color = Color.DarkGray)
            Text("  OR  ", color = TextGray, fontSize = 12.sp)
            HorizontalDivider(modifier = Modifier.weight(1f), color = Color.DarkGray)
        }
        Spacer(modifier = Modifier.height(20.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            SocialLoginButton(iconRes = R.drawable.google_logo, onClick = { googleSignInLauncher.launch(googleSignInClient.signInIntent) })
            Spacer(modifier = Modifier.width(20.dp))
            SocialLoginButton(iconRes = R.drawable.facebook_logo, onClick = { facebookLoginLauncher.launch(listOf("email", "public_profile")) })
            Spacer(modifier = Modifier.width(20.dp))
            SocialLoginButton(iconRes = R.drawable.github_logo, onClick = { if (activity != null) signInWithGitHub(activity, navController) })
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        Row {
            Text(text, color = TextGray)
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = actionText,
                color = StreamXRed,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { onActionClick() }
            )
        }
    }
}

@Composable
fun SocialLoginButton(iconRes: Int, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(50.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF252525))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Image(painter = painterResource(id = iconRes), contentDescription = null, modifier = Modifier.size(24.dp))
    }
}

// --- Helper Functions ---

private fun signInWithGitHub(activity: Activity, navController: NavController) {
    val provider = OAuthProvider.newBuilder("github.com").build()
    Firebase.auth.startActivityForSignInWithProvider(activity, provider)
        .addOnSuccessListener {
            EmailService.sendLoginAlert(Firebase.auth.currentUser?.email ?: "", Firebase.auth.currentUser?.displayName ?: "User")
            navController.navigate("home") { popUpTo("auth") { inclusive = true } }
        }
        .addOnFailureListener {
            Toast.makeText(activity, "GitHub Error", Toast.LENGTH_SHORT).show()
        }
}

private fun handleGoogleSignInResult(result: ActivityResult, context: android.content.Context, onSuccess: () -> Unit) {
    try {
        val account = GoogleSignIn.getSignedInAccountFromIntent(result.data).getResult(ApiException::class.java)!!
        val credential = GoogleAuthProvider.getCredential(account.idToken!!, null)
        Firebase.auth.signInWithCredential(credential).addOnCompleteListener { authTask ->
            if (authTask.isSuccessful) onSuccess()
            else Toast.makeText(context, "Google Auth Failed", Toast.LENGTH_SHORT).show()
        }
    } catch (e: ApiException) {
        Toast.makeText(context, "Google Sign-In Failed", Toast.LENGTH_SHORT).show()
    }
}

private fun handleFacebookAccessToken(token: com.facebook.AccessToken, onSuccess: () -> Unit) {
    val credential = FacebookAuthProvider.getCredential(token.token)
    Firebase.auth.signInWithCredential(credential).addOnCompleteListener { task ->
        if (task.isSuccessful) onSuccess()
    }
}
