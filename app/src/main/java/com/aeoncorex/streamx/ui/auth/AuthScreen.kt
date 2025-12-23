package com.aeoncorex.streamx.ui.auth

import android.app.Activity
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import com.aeoncorex.streamx.ui.home.FuturisticBackground
import com.facebook.CallbackManager
import com.facebook.FacebookCallback
import com.facebook.FacebookException
import com.facebook.login.LoginManager
import com.facebook.login.LoginResult
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FacebookAuthProvider
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.OAuthProvider
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase

enum class AuthScreenState { LOGIN, SIGN_UP }

@Composable
fun AuthScreen(navController: NavController) {
    var authState by rememberSaveable { mutableStateOf(AuthScreenState.LOGIN) }

    Box(modifier = Modifier.fillMaxSize()) {
        // ফিউচারিস্টিক ব্যাকগ্রাউন্ড যোগ করা হয়েছে
        FuturisticBackground()
        
        // মূল কন্টেন্ট একটি গ্লাস কার্ডের মধ্যে থাকবে
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .padding(16.dp),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.2f) // গ্লাস ইফেক্ট
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(24.dp)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // অ্যাপ লোগো
                    Image(
                        painter = painterResource(id = R.mipmap.ic_launcher), // সঠিক রিসোর্স ব্যবহার করুন
                        contentDescription = "App Logo",
                        modifier = Modifier
                            .size(90.dp)
                            .clip(CircleShape)
                            .border(2.dp, Color.Cyan.copy(alpha = 0.5f), CircleShape)
                    )
                    
                    Spacer(modifier = Modifier.height(24.dp))

                    Crossfade(
                        targetState = authState, 
                        label = "auth_crossfade",
                        animationSpec = tween(500)
                    ) { state ->
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
}

@Composable
fun LoginContent(onSwitchToSignUp: () -> Unit, navController: NavController) {
    val context = LocalContext.current
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Welcome Back", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text("Login to continue", fontSize = 14.sp, color = Color.White.copy(alpha = 0.7f))
        
        Spacer(modifier = Modifier.height(32.dp))
        
        AuthTextField(value = email, onValueChange = { email = it }, label = "Email", icon = Icons.Default.Email, keyboardType = KeyboardType.Email)
        Spacer(modifier = Modifier.height(16.dp))
        AuthTextField(value = password, onValueChange = { password = it }, label = "Password", icon = Icons.Default.Lock, isPassword = true)
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Button(
            onClick = {
                if (email.isNotBlank() && password.isNotBlank()) {
                    isLoading = true
                    Firebase.auth.signInWithEmailAndPassword(email, password)
                        .addOnCompleteListener { task ->
                            isLoading = false
                            if (task.isSuccessful) {
                                navController.navigate("home") { popUpTo("auth") { inclusive = true } }
                            } else {
                                Toast.makeText(context, "Error: ${task.exception?.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                } else {
                    Toast.makeText(context, "Please fill all fields", Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Cyan.copy(alpha = 0.8f),
                contentColor = Color.Black
            ),
            enabled = !isLoading
        ) {
            if (isLoading) CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.Black)
            else Text("LOGIN", fontWeight = FontWeight.Bold)
        }
        
        AuthFooter(
            text = "Don't have an account?",
            actionText = "Sign Up",
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

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Create Account", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text("Join the ultimate streaming experience", fontSize = 14.sp, color = Color.White.copy(alpha = 0.7f))
        
        Spacer(modifier = Modifier.height(32.dp))

        AuthTextField(value = username, onValueChange = { username = it }, label = "Username", icon = Icons.Default.Person)
        Spacer(modifier = Modifier.height(16.dp))
        AuthTextField(value = email, onValueChange = { email = it }, label = "Email", icon = Icons.Default.Email, keyboardType = KeyboardType.Email)
        Spacer(modifier = Modifier.height(16.dp))
        AuthTextField(value = password, onValueChange = { password = it }, label = "Password", icon = Icons.Default.Lock, isPassword = true)
        
        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                if (username.isNotBlank() && email.isNotBlank() && password.isNotBlank()) {
                    isLoading = true
                    Firebase.auth.createUserWithEmailAndPassword(email, password)
                        .addOnCompleteListener { task ->
                            if (task.isSuccessful) {
                                val user = task.result?.user
                                val profileUpdates = UserProfileChangeRequest.Builder().setDisplayName(username).build()
                                user?.updateProfile(profileUpdates)?.addOnCompleteListener {
                                    isLoading = false
                                    navController.navigate("home") { popUpTo("auth") { inclusive = true } }
                                }
                            } else {
                                isLoading = false
                                Toast.makeText(context, "Error: ${task.exception?.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                } else {
                    Toast.makeText(context, "Please fill all fields", Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Cyan.copy(alpha = 0.8f),
                contentColor = Color.Black
            ),
            enabled = !isLoading
        ) {
            if (isLoading) CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.Black)
            else Text("SIGN UP", fontWeight = FontWeight.Bold)
        }
        
        AuthFooter(
            text = "Already have an account?",
            actionText = "Login",
            onActionClick = onSwitchToLogin,
            navController = navController
        )
    }
}

@Composable
fun AuthFooter(text: String, actionText: String, onActionClick: () -> Unit, navController: NavController) {
    val context = LocalContext.current
    val activity = context as? Activity
    
    // Google Setup
    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result -> handleGoogleSignInResult(result, context) { navController.navigate("home") { popUpTo("auth") { inclusive = true } } } }
    
    val googleSignInClient = remember {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(context.getString(R.string.default_web_client_id))
            .requestEmail()
            .build()
        GoogleSignIn.getClient(context, gso)
    }

    // Facebook Setup
    val callbackManager = remember { CallbackManager.Factory.create() }
    val facebookLoginLauncher = rememberLauncherForActivityResult(
        contract = LoginManager.getInstance().createLogInActivityResultContract(callbackManager, null)
    ) { /* Handled by callback */ }

    DisposableEffect(Unit) {
        LoginManager.getInstance().registerCallback(callbackManager, object : FacebookCallback<LoginResult> {
            override fun onSuccess(result: LoginResult) {
                handleFacebookAccessToken(result.accessToken) { navController.navigate("home") { popUpTo("auth") { inclusive = true } } }
            }
            override fun onCancel() {}
            override fun onError(error: FacebookException) { Toast.makeText(context, "FB Login Failed", Toast.LENGTH_SHORT).show() }
        })
        onDispose { LoginManager.getInstance().unregisterCallback(callbackManager) }
    }

    Spacer(modifier = Modifier.height(24.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(modifier = Modifier.weight(1f), color = Color.White.copy(0.2f))
        Text("  OR  ", color = Color.White.copy(0.5f), fontSize = 12.sp)
        HorizontalDivider(modifier = Modifier.weight(1f), color = Color.White.copy(0.2f))
    }
    Spacer(modifier = Modifier.height(24.dp))
    
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        SocialLoginButton(iconRes = R.drawable.google_logo, onClick = { googleSignInLauncher.launch(googleSignInClient.signInIntent) })
        SocialLoginButton(iconRes = R.drawable.facebook_logo, onClick = { facebookLoginLauncher.launch(listOf("email", "public_profile")) })
        SocialLoginButton(iconRes = R.drawable.github_logo, onClick = { if (activity != null) signInWithGitHub(activity, navController) })
    }
    
    Spacer(modifier = Modifier.height(24.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = Color.White.copy(0.7f), fontSize = 14.sp)
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = actionText,
            color = Color.Cyan,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            modifier = Modifier.clickable { onActionClick() }
        )
    }
}

@Composable
fun AuthTextField(
    value: String, onValueChange: (String) -> Unit, label: String, icon: ImageVector,
    isPassword: Boolean = false, keyboardType: KeyboardType = KeyboardType.Text
) {
    var passwordVisible by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label, color = Color.White.copy(0.7f)) },
        leadingIcon = { Icon(icon, null, tint = Color.Cyan) },
        trailingIcon = if (isPassword) {
            {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff, null, tint = Color.White.copy(0.7f))
                }
            }
        } else null,
        shape = RoundedCornerShape(12.dp),
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Color.Cyan,
            unfocusedBorderColor = Color.White.copy(0.3f),
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            cursorColor = Color.Cyan
        ),
        visualTransformation = if (isPassword && !passwordVisible) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (isPassword) KeyboardType.Password else keyboardType)
    )
}

@Composable
fun SocialLoginButton(iconRes: Int, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(Color.White.copy(0.1f))
            .border(1.dp, Color.White.copy(0.2f), CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Image(painter = painterResource(id = iconRes), contentDescription = null, modifier = Modifier.size(28.dp))
    }
}

// --- Helper Functions ---

private fun signInWithGitHub(activity: Activity, navController: NavController) {
    val provider = OAuthProvider.newBuilder("github.com").build()
    Firebase.auth.startActivityForSignInWithProvider(activity, provider)
        .addOnSuccessListener { navController.navigate("home") { popUpTo("auth") { inclusive = true } } }
        .addOnFailureListener { Toast.makeText(activity, "GitHub Failed: ${it.message}", Toast.LENGTH_SHORT).show() }
}

private fun handleGoogleSignInResult(result: ActivityResult, context: android.content.Context, onSuccess: () -> Unit) {
    try {
        val account = GoogleSignIn.getSignedInAccountFromIntent(result.data).getResult(ApiException::class.java)!!
        val credential = GoogleAuthProvider.getCredential(account.idToken!!, null)
        Firebase.auth.signInWithCredential(credential).addOnCompleteListener { 
            if (it.isSuccessful) onSuccess() else Toast.makeText(context, "Google Auth Failed", Toast.LENGTH_SHORT).show() 
        }
    } catch (e: ApiException) { Toast.makeText(context, "Google Sign-in Error", Toast.LENGTH_SHORT).show() }
}

private fun handleFacebookAccessToken(token: com.facebook.AccessToken, onSuccess: () -> Unit) {
    val credential = FacebookAuthProvider.getCredential(token.token)
    Firebase.auth.signInWithCredential(credential).addOnCompleteListener { 
        if (it.isSuccessful) onSuccess() else Log.e("Auth", "FB Auth Failed") 
    }
}