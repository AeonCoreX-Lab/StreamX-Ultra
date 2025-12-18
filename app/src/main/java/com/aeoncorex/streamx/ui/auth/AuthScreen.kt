package com.aeoncorex.streamx.ui.auth

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.aeoncorex.streamx.R
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthScreen(navController: NavController) {
    val context = LocalContext.current
    val auth = Firebase.auth
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var selectedTabIndex by remember { mutableStateOf(0) }
    val tabs = listOf("Login", "Register")

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

    // --- গুগল সাইন-ইন লজিক ---
    val gso = remember {
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(context.getString(R.string.default_web_client_id))
            .requestEmail()
            .build()
    }
    val googleSignInClient = remember { GoogleSignIn.getClient(context, gso) }

    val googleAuthLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
        onResult = { result ->
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(ApiException::class.java)!!
                val credential = GoogleAuthProvider.getCredential(account.idToken!!, null)
                isLoading = true
                auth.signInWithCredential(credential).addOnCompleteListener { taskResult ->
                    if (taskResult.isSuccessful) {
                        navController.navigate("home") { popUpTo("auth") { inclusive = true } }
                    } else {
                        scope.launch { snackbarHostState.showSnackbar("Google Sign-In failed.") }
                    }
                    isLoading = false
                }
            } catch (e: ApiException) {
                scope.launch { snackbarHostState.showSnackbar("Google Sign-In error: ${e.message}") }
            }
        }
    )

    // --- UI ---
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // আপনার কোম্পানির লোগো এখানে যোগ করতে পারেন
            // Image(painter = painterResource(id = R.drawable.aeoncorex_logo), ...)

            Text(
                text = "Welcome to StreamX Ultra",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(32.dp))

            TabRow(selectedTabIndex = selectedTabIndex) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTabIndex == index,
                        onClick = { selectedTabIndex = index },
                        text = { Text(title) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Email Address") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Password") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(16.dp))

            // শুধুমাত্র রেজিস্ট্রেশন ট্যাবে "Confirm Password" ফিল্ড দেখাবে
            AnimatedVisibility(visible = selectedTabIndex == 1) {
                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = { confirmPassword = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Confirm Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true
                )
            }
            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    if (email.isNotBlank() && password.isNotBlank()) {
                        isLoading = true
                        if (selectedTabIndex == 0) { // Login
                            auth.signInWithEmailAndPassword(email, password)
                                .addOnSuccessListener {
                                    navController.navigate("home") { popUpTo("auth") { inclusive = true } }
                                }
                                .addOnFailureListener { e ->
                                    scope.launch { snackbarHostState.showSnackbar(e.message ?: "Login failed.") }
                                }
                                .addOnCompleteListener { isLoading = false }
                        } else { // Register
                            if (password == confirmPassword) {
                                auth.createUserWithEmailAndPassword(email, password)
                                    .addOnSuccessListener {
                                        navController.navigate("home") { popUpTo("auth") { inclusive = true } }
                                    }
                                    .addOnFailureListener { e ->
                                        scope.launch { snackbarHostState.showSnackbar(e.message ?: "Registration failed.") }
                                    }
                                    .addOnCompleteListener { isLoading = false }
                            } else {
                                scope.launch { snackbarHostState.showSnackbar("Passwords do not match.") }
                                isLoading = false
                            }
                        }
                    } else {
                        scope.launch { snackbarHostState.showSnackbar("Please fill all fields.") }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                enabled = !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White)
                } else {
                    Text(if (selectedTabIndex == 0) "LOGIN" else "CREATE ACCOUNT")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text("OR")
            Spacer(modifier = Modifier.height(16.dp))

            OutlinedButton(
                onClick = { googleAuthLauncher.launch(googleSignInClient.signInIntent) },
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                // এখানে গুগলের লোগো যোগ করতে পারেন
                Text("CONTINUE WITH GOOGLE")
            }
        }
    }
}