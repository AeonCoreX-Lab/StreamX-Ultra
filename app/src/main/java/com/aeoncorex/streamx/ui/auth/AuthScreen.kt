package com.aeoncorex.streamx.ui.auth

import android.app.Activity
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation // --- FIXED: Added Import ---
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.aeoncorex.streamx.R
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

enum class AuthScreenState { LOGIN, SIGN_UP }

@Composable
fun AuthScreen(navController: NavController) {
    var authState by rememberSaveable { mutableStateOf(AuthScreenState.LOGIN) }
    
    Surface(modifier = Modifier.fillMaxSize(), color = Color.White) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            
            Image(
                painter = painterResource(id = R.mipmap.ic_launcher),
                contentDescription = "App Logo",
                modifier = Modifier.size(80.dp)
            )
            Spacer(modifier = Modifier.height(24.dp))
            
            Crossfade(targetState = authState, label = "auth_crossfade") { state ->
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

@Composable
fun LoginContent(onSwitchToSignUp: () -> Unit, navController: NavController) {
    val context = LocalContext.current
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Welcome back!", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.Black)
        Text("Login to your account", fontSize = 16.sp, color = Color.Gray)
        Spacer(modifier = Modifier.height(32.dp))
        
        AuthTextField(value = email, onValueChange = { email = it }, label = "Email", icon = Icons.Default.Email, keyboardType = KeyboardType.Email)
        Spacer(modifier = Modifier.height(16.dp))
        AuthTextField(value = password, onValueChange = { password = it }, label = "Password", icon = Icons.Default.Lock, isPassword = true)
        Spacer(modifier = Modifier.height(32.dp))
        
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
                                Toast.makeText(context, "Login Failed: ${task.exception?.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                } else {
                    Toast.makeText(context, "Please fill all fields", Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(12.dp),
            enabled = !isLoading
        ) {
            if (isLoading) CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
            else Text("Sign In", fontSize = 16.sp)
        }
        
        AuthFooter(
            text = "Don't have an account?",
            actionText = "Sign up here",
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
        Text("Welcome!", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.Black)
        Text("Create your account", fontSize = 16.sp, color = Color.Gray)
        Spacer(modifier = Modifier.height(32.dp))

        AuthTextField(value = username, onValueChange = { username = it }, label = "Username", icon = Icons.Default.Person)
        Spacer(modifier = Modifier.height(16.dp))
        AuthTextField(value = email, onValueChange = { email = it }, label = "Email", icon = Icons.Default.Email, keyboardType = KeyboardType.Email)
        Spacer(modifier = Modifier.height(16.dp))
        AuthTextField(value = password, onValueChange = { password = it }, label = "Password", icon = Icons.Default.Lock, isPassword = true)
        Spacer(modifier = Modifier.height(32.dp))

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
                                Toast.makeText(context, "Sign Up Failed: ${task.exception?.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                } else {
                    Toast.makeText(context, "Please fill all fields", Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(12.dp),
            enabled = !isLoading
        ) {
            if (isLoading) CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
            else Text("Sign Up", fontSize = 16.sp)
        }
        
        AuthFooter(
            text = "Already have an account?",
            actionText = "Sign in here",
            onActionClick = onSwitchToLogin,
            navController = navController
        )
    }
}

@Composable
fun AuthFooter(text: String, actionText: String, onActionClick: () -> Unit, navController: NavController) {
    val context = LocalContext.current
    val activity = context as? Activity
    
    // Google Sign-In Logic
    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result -> handleGoogleSignInResult(result, context) { navController.navigate("home") { popUpTo("auth") { inclusive = true } } } }
    
    val googleSignInClient = remember {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(context.getString(R.string.default_web_client_id)).requestEmail().build()
        GoogleSignIn.getClient(context, gso)
    }

    // Facebook Sign-In Logic
    val callbackManager = remember { CallbackManager.Factory.create() }
    val facebookLoginLauncher = rememberLauncherForActivityResult(
        contract = LoginManager.getInstance().createLogInActivityResultContract(callbackManager, null)
    ) { /* Result is handled by the callback */ }

    DisposableEffect(Unit) {
        LoginManager.getInstance().registerCallback(callbackManager, object : FacebookCallback<LoginResult> {
            override fun onSuccess(result: LoginResult) {
                handleFacebookAccessToken(result.accessToken) {
                    navController.navigate("home") { popUpTo("auth") { inclusive = true } }
                }
            }
            override fun onCancel() { Log.w("AuthScreen", "Facebook login canceled.") }
            override fun onError(error: FacebookException) {
                Toast.makeText(context, "Facebook Login Failed", Toast.LENGTH_SHORT).show()
                Log.e("AuthScreen", "Facebook login error.", error)
            }
        })
        onDispose { LoginManager.getInstance().unregisterCallback(callbackManager) }
    }

    Spacer(modifier = Modifier.height(32.dp))
    Text("— Or continue with —", color = Color.Gray)
    Spacer(modifier = Modifier.height(16.dp))
    
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        SocialLoginButton(iconRes = R.drawable.google_logo, onClick = { googleSignInLauncher.launch(googleSignInClient.signInIntent) })
        Spacer(modifier = Modifier.width(24.dp))
        SocialLoginButton(iconRes = R.drawable.facebook_logo, onClick = { facebookLoginLauncher.launch(listOf("email", "public_profile")) })
        Spacer(modifier = Modifier.width(24.dp))
        SocialLoginButton(iconRes = R.drawable.github_logo, onClick = { if (activity != null) signInWithGitHub(activity, navController) })
    }
    
    Spacer(modifier = Modifier.height(32.dp))
    Row {
        Text(text, color = Color.Gray)
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = actionText,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable { onActionClick() }
        )
    }
}

@Composable
fun AuthTextField(
    value: String, onValueChange: (String) -> Unit, label: String, icon: ImageVector,
    isPassword: Boolean = false, keyboardType: KeyboardType = KeyboardType.Text
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        shape = RoundedCornerShape(12.dp),
        singleLine = true,
        // --- FIXED: VisualTransformation.None instead of PasswordVisualTransformation.None ---
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (isPassword) KeyboardType.Password else keyboardType)
    )
}

@Composable
fun SocialLoginButton(iconRes: Int, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(50.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, Color.LightGray, RoundedCornerShape(12.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Image(painter = painterResource(id = iconRes), contentDescription = null, modifier = Modifier.size(24.dp))
    }
}

// --- Helper Functions for Authentication ---

private fun signInWithGitHub(activity: Activity, navController: NavController) {
    val provider = OAuthProvider.newBuilder("github.com").build()
    Firebase.auth.startActivityForSignInWithProvider(activity, provider)
        .addOnSuccessListener {
            Log.d("AuthScreen", "GitHub sign-in successful.")
            navController.navigate("home") { popUpTo("auth") { inclusive = true } }
        }
        .addOnFailureListener { e ->
            Log.w("AuthScreen", "GitHub sign-in failed.", e)
            Toast.makeText(activity, "GitHub Sign-in Failed", Toast.LENGTH_SHORT).show()
        }
}

private fun handleGoogleSignInResult(result: ActivityResult, context: android.content.Context, onSuccess: () -> Unit) {
    try {
        val account = GoogleSignIn.getSignedInAccountFromIntent(result.data).getResult(ApiException::class.java)!!
        val credential = GoogleAuthProvider.getCredential(account.idToken!!, null)
        Firebase.auth.signInWithCredential(credential).addOnCompleteListener { authTask ->
            if (authTask.isSuccessful) {
                Log.d("AuthScreen", "Google sign-in to Firebase successful.")
                onSuccess()
            } else {
                Log.w("AuthScreen", "Firebase auth failed.", authTask.exception)
                Toast.makeText(context, "Authentication Failed.", Toast.LENGTH_SHORT).show()
            }
        }
    } catch (e: ApiException) {
        Log.w("AuthScreen", "Google sign-in failed.", e)
        Toast.makeText(context, "Google Sign-in Failed.", Toast.LENGTH_SHORT).show()
    }
}

private fun handleFacebookAccessToken(token: com.facebook.AccessToken, onSuccess: () -> Unit) {
    val credential = FacebookAuthProvider.getCredential(token.token)
    Firebase.auth.signInWithCredential(credential).addOnCompleteListener { task ->
        if (task.isSuccessful) {
            Log.d("AuthScreen", "Facebook sign-in to Firebase successful.")
            onSuccess()
        } else {
            Log.w("AuthScreen", "Firebase auth with Facebook failed.", task.exception)
        }
    }
}