package com.aeoncorex.streamx.ui.auth

import android.app.Activity
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.aeoncorex.streamx.R
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.firebase.auth.*
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

// ── Brand palette ─────────────────────────────────────────────
val Void        = Color(0xFF020409)
val DeepBlue    = Color(0xFF040D1A)
val GlassCard   = Color(0x1A0097A7)
val GlassBorder = Color(0x3300D4E8)
val Teal        = Color(0xFF00D4E8)
val TealDim     = Color(0xFF0097A7)
val TealGlow    = Color(0x2200D4E8)
val Purple      = Color(0xFF6C3FFE)
val PurpleGlow  = Color(0x226C3FFE)
val TextWhite   = Color(0xFFF0F4FF)
val TextGray    = Color(0xFF7A8BA0)
val GridLine    = Color(0x0D00D4E8)

// ── Keep legacy names for compatibility ───────────────────────
val DarkBackground = Void
val CardBackground = Color(0xFF040D1A)
val NeonCyan   = Teal
val NeonPurple = Purple

enum class AuthScreenState { LOGIN, SIGN_UP }

// ─────────────────────────────────────────────────────────────
@Composable
fun AuthScreen(navController: NavController) {
    var authState by rememberSaveable { mutableStateOf(AuthScreenState.LOGIN) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun onAuthSuccess(msg: String) = scope.launch {
        snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Short)
        delay(900)
        navController.navigate("home") { popUpTo("auth") { inclusive = true } }
    }
    fun onAuthError(msg: String) = scope.launch {
        snackbarHostState.showSnackbar("⚠  $msg")
    }

    // ── Ambient infinite animations ───────────────────────────
    val infiniteTransition = rememberInfiniteTransition(label = "auth_ambient")

    val scanY by infiniteTransition.animateFloat(0f, 1f,
        infiniteRepeatable(tween(4000, easing = LinearEasing), RepeatMode.Restart), label = "scan")

    val glowAlpha by infiniteTransition.animateFloat(0.3f, 0.7f,
        infiniteRepeatable(tween(2500, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "glow")

    val gridOffset by infiniteTransition.animateFloat(0f, 48f,
        infiniteRepeatable(tween(7000, easing = LinearEasing), RepeatMode.Restart), label = "grid")

    // ── Enter transition ──────────────────────────────────────
    var entered by remember { mutableStateOf(false) }
    val contentAlpha by animateFloatAsState(if (entered) 1f else 0f, tween(800), label = "ea")
    val contentY     by animateFloatAsState(if (entered) 0f else 32f, tween(800, easing = FastOutSlowInEasing), label = "ey")
    LaunchedEffect(Unit) { delay(80); entered = true }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.radialGradient(listOf(DeepBlue, Void), radius = 1400f))
    ) {
        // ── Grid canvas ───────────────────────────────────────
        Canvas(Modifier.fillMaxSize()) {
            val step = 48f
            val col  = GridLine
            var x = -(gridOffset % step)
            while (x < size.width) { drawLine(col, Offset(x,0f), Offset(x,size.height), 0.5f); x += step }
            var y = -(gridOffset % step)
            while (y < size.height) { drawLine(col, Offset(0f,y), Offset(size.width,y), 0.5f); y += step }
        }

        // ── Background glow blobs ─────────────────────────────
        Canvas(Modifier.fillMaxSize().blur(80.dp)) {
            val cx = size.width / 2f
            val cy = size.height * 0.35f
            drawCircle(
                Brush.radialGradient(listOf(TealGlow, Color.Transparent),
                    center = Offset(cx, cy), radius = 300f),
                center = Offset(cx, cy), radius = 300f
            )
            drawCircle(
                Brush.radialGradient(listOf(PurpleGlow, Color.Transparent),
                    center = Offset(cx * 0.3f, cy * 1.5f), radius = 200f),
                center = Offset(cx * 0.3f, cy * 1.5f), radius = 200f
            )
        }

        // ── Scan line ──────────────────────────────────────────
        Canvas(Modifier.fillMaxSize()) {
            val y = scanY * size.height
            drawLine(
                Brush.horizontalGradient(listOf(
                    Color.Transparent, Teal.copy(alpha = 0.25f),
                    Teal.copy(alpha = 0.5f), Teal.copy(alpha = 0.25f), Color.Transparent
                )),
                Offset(0f, y), Offset(size.width, y), 1.5f
            )
            drawLine(
                Brush.horizontalGradient(listOf(Color.Transparent, Teal.copy(alpha = 0.1f), Color.Transparent)),
                Offset(0f, y), Offset(size.width, y), 20f
            )
        }

        // ── Main content ──────────────────────────────────────
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .offset(y = contentY.dp)
                .alpha(contentAlpha),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Logo
            Image(
                painterResource(R.drawable.streamx_ultra_logo),
                "StreamX",
                modifier = Modifier.height(88.dp),
                contentScale = ContentScale.Fit
            )
            Spacer(Modifier.height(8.dp))

            // Teal divider under logo
            Canvas(Modifier.width(32.dp).height(2.dp)) {
                drawLine(
                    Brush.horizontalGradient(listOf(Color.Transparent, Teal, Color.Transparent)),
                    Offset(0f, 0f), Offset(size.width, 0f), 1.5f
                )
            }
            Spacer(Modifier.height(32.dp))

            // ── Glass card ───────────────────────────────────
            GlassCard {
                // ── Tab switcher ──────────────────────────────
                AuthTabBar(
                    selected = authState,
                    onSelect = { authState = it }
                )
                Spacer(Modifier.height(28.dp))

                // ── Form crossfade ────────────────────────────
                Crossfade(authState, tween(400), label = "form") { state ->
                    when (state) {
                        AuthScreenState.LOGIN -> LoginContent(
                            onSwitchToSignUp = { authState = AuthScreenState.SIGN_UP },
                            onSuccess = { onAuthSuccess("Welcome back!") },
                            onError   = { onAuthError(it) }
                        )
                        AuthScreenState.SIGN_UP -> SignUpContent(
                            onSwitchToLogin = { authState = AuthScreenState.LOGIN },
                            onSuccess = { onAuthSuccess("Account created!") },
                            onError   = { onAuthError(it) }
                        )
                    }
                }
            }
        }

        // ── HUD corners ───────────────────────────────────────
        Canvas(Modifier.fillMaxSize().alpha(contentAlpha)) {
            val p = 20.dp.toPx(); val l = 16.dp.toPx(); val col = Teal.copy(0.3f); val sw = 1.5f
            drawLine(col, Offset(p,p), Offset(p+l,p), sw); drawLine(col, Offset(p,p), Offset(p,p+l), sw)
            drawLine(col, Offset(size.width-p,p), Offset(size.width-p-l,p), sw); drawLine(col, Offset(size.width-p,p), Offset(size.width-p,p+l), sw)
            drawLine(col, Offset(p,size.height-p), Offset(p+l,size.height-p), sw); drawLine(col, Offset(p,size.height-p), Offset(p,size.height-p-l), sw)
            drawLine(col, Offset(size.width-p,size.height-p), Offset(size.width-p-l,size.height-p), sw); drawLine(col, Offset(size.width-p,size.height-p), Offset(size.width-p,size.height-p-l), sw)
        }

        // ── Snackbar ──────────────────────────────────────────
        SnackbarHost(
            snackbarHostState,
            Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp)
        ) { data ->
            Snackbar(
                data,
                containerColor = Color(0xFF0D2030),
                contentColor   = Teal,
                shape          = RoundedCornerShape(12.dp)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
@Composable
private fun GlassCard(content: @Composable ColumnScope.() -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                // Outer glow
                drawRoundRect(
                    TealGlow,
                    topLeft = Offset(-4f, -4f),
                    size = Size(size.width + 8f, size.height + 8f),
                    cornerRadius = CornerRadius(28f),
                    style = Stroke(6f)
                )
            }
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.verticalGradient(
                    listOf(Color(0x1A00D4E8), Color(0x0D040D1A))
                )
            )
            .border(
                1.dp,
                Brush.linearGradient(listOf(Teal.copy(0.5f), Purple.copy(0.25f), Color.Transparent)),
                RoundedCornerShape(24.dp)
            )
            .padding(horizontal = 24.dp, vertical = 28.dp)
    ) {
        Column(content = content)
    }
}

// ─────────────────────────────────────────────────────────────
@Composable
private fun AuthTabBar(selected: AuthScreenState, onSelect: (AuthScreenState) -> Unit) {
    val tabs = listOf(AuthScreenState.LOGIN to "SIGN IN", AuthScreenState.SIGN_UP to "CREATE")

    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0x1A000000))
            .border(1.dp, GlassBorder, RoundedCornerShape(12.dp))
    ) {
        Row(Modifier.fillMaxWidth()) {
            tabs.forEach { (state, label) ->
                val isSelected = selected == state
                val tabAlpha by animateFloatAsState(if (isSelected) 1f else 0.45f, tween(300), label = "tab")
                Box(
                    Modifier
                        .weight(1f)
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { onSelect(state) }
                        .background(
                            if (isSelected)
                                Brush.horizontalGradient(listOf(Teal.copy(0.15f), Purple.copy(0.1f)))
                            else
                                Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
                        )
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            label,
                            color = if (isSelected) Teal else TextGray,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            letterSpacing = 2.sp,
                            modifier = Modifier.alpha(tabAlpha)
                        )
                        if (isSelected) {
                            Spacer(Modifier.height(4.dp))
                            Canvas(Modifier.width(24.dp).height(2.dp)) {
                                drawLine(
                                    Brush.horizontalGradient(listOf(Color.Transparent, Teal, Color.Transparent)),
                                    Offset(0f,0f), Offset(size.width,0f), 2f
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
@Composable
fun LoginContent(onSwitchToSignUp: () -> Unit, onSuccess: () -> Unit, onError: (String) -> Unit) {
    var email    by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var loading  by remember { mutableStateOf(false) }
    val scope    = rememberCoroutineScope()

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        AuthHeading("Welcome back", "Sign in to continue your stream")
        Spacer(Modifier.height(24.dp))

        HoloTextField(email,    { email = it },    "Email Address", Icons.Default.Email,   keyboardType = KeyboardType.Email)
        Spacer(Modifier.height(14.dp))
        HoloTextField(password, { password = it }, "Password",      Icons.Default.Lock,    isPassword = true)
        Spacer(Modifier.height(28.dp))

        HoloPrimaryButton("SIGN IN", loading) {
            if (email.isNotBlank() && password.isNotBlank()) {
                loading = true
                scope.launch {
                    try { Firebase.auth.signInWithEmailAndPassword(email, password).await(); onSuccess() }
                    catch (e: Exception) { onError(e.message ?: "Login failed"); loading = false }
                }
            } else onError("Please fill all fields")
        }

        AuthFooter("New here? ", "Create account", onSwitchToSignUp, onSuccess, onError)
    }
}

// ─────────────────────────────────────────────────────────────
@Composable
fun SignUpContent(onSwitchToLogin: () -> Unit, onSuccess: () -> Unit, onError: (String) -> Unit) {
    var username by rememberSaveable { mutableStateOf("") }
    var email    by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var loading  by remember { mutableStateOf(false) }
    val scope    = rememberCoroutineScope()

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        AuthHeading("Join StreamX", "Create your account to get started")
        Spacer(Modifier.height(24.dp))

        HoloTextField(username, { username = it }, "Username",      Icons.Default.Person)
        Spacer(Modifier.height(14.dp))
        HoloTextField(email,    { email = it },    "Email Address", Icons.Default.Email, keyboardType = KeyboardType.Email)
        Spacer(Modifier.height(14.dp))
        HoloTextField(password, { password = it }, "Password",      Icons.Default.Lock, isPassword = true)
        Spacer(Modifier.height(28.dp))

        HoloPrimaryButton("CREATE ACCOUNT", loading) {
            if (username.isNotBlank() && email.isNotBlank() && password.isNotBlank()) {
                loading = true
                scope.launch {
                    try {
                        val res = Firebase.auth.createUserWithEmailAndPassword(email, password).await()
                        res.user?.updateProfile(UserProfileChangeRequest.Builder().setDisplayName(username).build())?.await()
                        onSuccess()
                    } catch (e: Exception) { onError(e.message ?: "Sign up failed"); loading = false }
                }
            } else onError("Please fill all fields")
        }

        AuthFooter("Already a member? ", "Sign in", onSwitchToLogin, onSuccess, onError)
    }
}

// ─────────────────────────────────────────────────────────────
@Composable
fun AuthFooter(promptText: String, actionText: String, onActionClick: () -> Unit, onSuccess: () -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()

    val googleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res -> scope.launch { handleGoogleSignInResult(res, onSuccess, onError) } }

    val googleClient = remember {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(context.getString(R.string.default_web_client_id))
            .requestEmail().build()
        GoogleSignIn.getClient(context, gso)
    }

    Spacer(Modifier.height(24.dp))

    // Divider
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Canvas(Modifier.weight(1f).height(1.dp)) {
            drawLine(Brush.horizontalGradient(listOf(Color.Transparent, GlassBorder)), Offset(0f,0f), Offset(size.width,0f), 1f)
        }
        Text("  OR  ", color = TextGray.copy(0.6f), fontSize = 10.sp, letterSpacing = 2.sp)
        Canvas(Modifier.weight(1f).height(1.dp)) {
            drawLine(Brush.horizontalGradient(listOf(GlassBorder, Color.Transparent)), Offset(0f,0f), Offset(size.width,0f), 1f)
        }
    }

    Spacer(Modifier.height(20.dp))

    // Social buttons row
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        HoloSocialButton(R.drawable.google_logo) { googleLauncher.launch(googleClient.signInIntent) }
        Spacer(Modifier.width(20.dp))
        HoloSocialButton(R.drawable.github_logo) { if (activity != null) signInWithGitHub(activity, onSuccess, onError) }
    }

    Spacer(Modifier.height(24.dp))

    // Switch prompt
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = TextGray, fontSize = 13.sp)) { append(promptText) }
            withStyle(SpanStyle(color = Teal, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)) { append(actionText) }
        },
        modifier = Modifier.clickable(
            indication = null,
            interactionSource = remember { MutableInteractionSource() }
        ) { onActionClick() }
    )
}

// ─────────────────────────────────────────────────────────────
//  Reusable UI components
// ─────────────────────────────────────────────────────────────

@Composable
private fun AuthHeading(title: String, subtitle: String) {
    Text(title, color = TextWhite, fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
    Spacer(Modifier.height(4.dp))
    Text(subtitle, color = TextGray, fontSize = 13.sp, letterSpacing = 0.3.sp)
}

@Composable
fun HoloTextField(
    value: String, onValueChange: (String) -> Unit,
    label: String, icon: ImageVector,
    isPassword: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text
) {
    var visible by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label, fontSize = 13.sp) },
        leadingIcon = {
            Icon(icon, null, tint = Teal.copy(0.8f), modifier = Modifier.size(18.dp))
        },
        trailingIcon = if (isPassword) ({
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                    null, tint = TextGray.copy(0.6f), modifier = Modifier.size(18.dp)
                )
            }
        }) else null,
        shape = RoundedCornerShape(14.dp),
        singleLine = true,
        visualTransformation = if (isPassword && !visible) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (isPassword) KeyboardType.Password else keyboardType),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor   = Color(0x0D00D4E8),
            unfocusedContainerColor = Color(0x06000000),
            focusedBorderColor      = Teal.copy(0.8f),
            unfocusedBorderColor    = TextGray.copy(0.2f),
            focusedTextColor        = TextWhite,
            unfocusedTextColor      = TextWhite.copy(0.9f),
            focusedLabelColor       = Teal,
            unfocusedLabelColor     = TextGray.copy(0.6f),
            cursorColor             = Teal
        )
    )
}

@Composable
fun HoloPrimaryButton(text: String, isLoading: Boolean, onClick: () -> Unit) {
    val shimmerOffset by rememberInfiniteTransition(label = "btn_shimmer")
        .animateFloat(-1f, 2f,
            infiniteRepeatable(tween(2000, delayMillis = 600, easing = LinearEasing)), label = "sh")

    Box(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                Brush.horizontalGradient(listOf(
                    Color(0xFF007B8A),
                    Color(0xFF00D4E8),
                    Color(0xFF6C3FFE)
                ))
            )
            // Shimmer overlay
            .drawBehind {
                val w = size.width
                drawRect(
                    Brush.linearGradient(
                        0f   to Color.Transparent,
                        0.4f to Color.White.copy(0.12f),
                        0.5f to Color.White.copy(0.22f),
                        0.6f to Color.White.copy(0.12f),
                        1f   to Color.Transparent,
                        start = Offset(shimmerOffset * w, 0f),
                        end   = Offset(shimmerOffset * w + w * 0.4f, size.height)
                    )
                )
            }
            .clickable(
                enabled = !isLoading,
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        AnimatedVisibility(isLoading,  enter = fadeIn(), exit = fadeOut()) {
            CircularProgressIndicator(Modifier.size(22.dp), color = TextWhite, strokeWidth = 2.dp)
        }
        AnimatedVisibility(!isLoading, enter = fadeIn(), exit = fadeOut()) {
            Text(text, color = TextWhite, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        }
    }
}

@Composable
fun HoloSocialButton(iconRes: Int, onClick: () -> Unit) {
    Box(
        Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0x12000000))
            .border(
                1.dp,
                Brush.linearGradient(listOf(GlassBorder, Purple.copy(0.25f))),
                RoundedCornerShape(14.dp)
            )
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Image(painterResource(iconRes), null, Modifier.size(22.dp))
    }
}

// ── Keep legacy names so other files that import them still compile ──
@Composable
fun FuturisticTextField(
    value: String, onValueChange: (String) -> Unit,
    label: String, icon: ImageVector,
    isPassword: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text
) = HoloTextField(value, onValueChange, label, icon, isPassword, keyboardType)

@Composable
fun FuturisticButton(text: String, isLoading: Boolean, onClick: () -> Unit) =
    HoloPrimaryButton(text, isLoading, onClick)

@Composable
fun SocialLoginButton(iconRes: Int, onClick: () -> Unit) = HoloSocialButton(iconRes, onClick)

// ─────────────────────────────────────────────────────────────
//  Auth helpers (unchanged)
// ─────────────────────────────────────────────────────────────

private fun signInWithGitHub(activity: Activity, onSuccess: () -> Unit, onError: (String) -> Unit) {
    val provider = OAuthProvider.newBuilder("github.com").build()
    Firebase.auth.startActivityForSignInWithProvider(activity, provider)
        .addOnSuccessListener { Log.d("Auth","GitHub OK"); onSuccess() }
        .addOnFailureListener { Log.w("Auth","GitHub fail",it); onError(it.message ?: "GitHub Sign-in Failed") }
}

private suspend fun handleGoogleSignInResult(result: ActivityResult, onSuccess: () -> Unit, onError: (String) -> Unit) {
    try {
        val account    = GoogleSignIn.getSignedInAccountFromIntent(result.data).await()
        val credential = GoogleAuthProvider.getCredential(account.idToken!!, null)
        Firebase.auth.signInWithCredential(credential).await()
        Log.d("Auth","Google OK"); onSuccess()
    } catch (e: Exception) {
        Log.w("Auth","Google fail",e); onError(e.message ?: "Google Sign-in Failed")
    }
}
