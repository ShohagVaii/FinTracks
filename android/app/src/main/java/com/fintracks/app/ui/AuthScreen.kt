package com.fintracks.app.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import com.fintracks.app.BuildConfig
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

// Colours taken from the approved Sign-in / Sign-up / Welcome references (Drive folder "Sign-up / Login").
private val auSkyA = Color(0xFF38BDF8)
private val auBlue = Color(0xFF2563EB)
private val auCardFill = Color(0xFF0F1A2E)
private val auCardBorder = Color(0xFF1E2B45)
private val auFieldFill = Color(0xFF0A1220)
private val auFieldBorder = Color(0xFF1F2B40)
private val auMuted = Color(0xFF94A3B8)
private val auBgTop = Color(0xFF041833)
private val auBgBottom = Color(0xFF01030A)
private val auWarm = Color(0xFFFFE9A8)
private val auOk = Color(0xFF34D399)
private val auErr = Color(0xFFF87171)

private const val AU_WELCOME = 0
private const val AU_SIGN_IN = 1
private const val AU_SIGN_UP = 2

private fun auBrush() = Brush.horizontalGradient(listOf(auSkyA, auBlue))
private fun auBg() = Brush.verticalGradient(listOf(auBgTop, Color(0xFF020617), auBgBottom))
private fun auValidEmail(e: String): Boolean = android.util.Patterns.EMAIL_ADDRESS.matcher(e.trim()).matches()
private fun auLerp(a: Float, b: Float, f: Float): Float = a + (b - a) * f
private fun auEaseOut(x: Float): Float = 1f - (1f - x) * (1f - x)

/** Reads the system "Remove animations" setting (animator duration scale 0). */
private fun auReduceMotion(context: Context): Boolean = try {
    android.provider.Settings.Global.getFloat(
        context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f
    ) == 0f
} catch (e: Exception) {
    false
}

@Composable
fun AuthScreen(
    state: UiState,
    onSignIn: (String, String) -> Unit,
    onSignUp: (String, String, String) -> Unit,
    onGoogle: (String) -> Unit,
    onReset: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val reduce = remember { auReduceMotion(context) }
    var stage by rememberSaveable { mutableStateOf(AU_WELCOME) }
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    var keepSignedIn by rememberSaveable { mutableStateOf(true) }
    var localError by remember { mutableStateOf<String?>(null) }
    var flipping by remember { mutableStateOf(false) }
    val flip = remember { Animatable(0f) }
    val shake = remember { Animatable(0f) }
    val stamp = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()

    val shownError = localError ?: state.message

    BackHandler(enabled = stage != AU_WELCOME) {
        stage = AU_WELCOME
        localError = null
    }

    // Failure: the card shakes (skipped when Reduce Motion is on).
    LaunchedEffect(shownError) {
        if (shownError != null && !reduce) {
            for (v in listOf(-12f, 12f, -9f, 9f, -5f, 5f, 0f)) shake.animateTo(v, tween(50))
        }
    }
    // Sign-up confirmation stamp pops in.
    LaunchedEffect(state.notice) {
        if (state.notice != null && !reduce) {
            stamp.snapTo(0.6f)
            stamp.animateTo(1f, tween(300))
        }
    }

    fun flipTo(target: Int) {
        if (flipping || target == stage) return
        flipping = true
        localError = null
        scope.launch {
            if (!reduce) flip.animateTo(90f, tween(200))
            stage = target
            if (!reduce) flip.animateTo(0f, tween(200))
            flip.snapTo(0f)
            flipping = false
        }
    }

    fun submitSignIn() {
        localError = null
        onSignIn(email.trim(), password)
    }

    fun submitSignUp() {
        localError = null
        onSignUp(name.trim(), email.trim(), password)
    }

    val googleMissing = tr("Google লগইন এখনো কনফিগার করা হয়নি", "Google login is not set up yet")
    val googleFailed = tr("Google লগইন ব্যর্থ হয়েছে", "Google login failed")
    fun googleSignIn() {
        if (BuildConfig.GOOGLE_WEB_CLIENT_ID.isBlank()) {
            localError = googleMissing
            return
        }
        scope.launch {
            try {
                onGoogle(googleIdToken(context))
            } catch (e: Exception) {
                localError = googleFailed
            }
        }
    }

    Crossfade(targetState = stage == AU_WELCOME, label = "authStage") { welcome ->
        if (welcome) {
            WelcomeScene(
                reduce = reduce,
                onSignIn = { stage = AU_SIGN_IN; localError = null },
                onSignUp = { stage = AU_SIGN_UP; localError = null }
            )
        } else {
            AuthCardScene(
                isSignUp = stage == AU_SIGN_UP,
                busy = state.busy,
                shownError = shownError,
                notice = state.notice,
                flipAngle = flip.value,
                shakeX = shake.value,
                stampScale = stamp.value,
                name = name, onName = { name = it },
                email = email, onEmail = { email = it },
                password = password, onPassword = { password = it },
                confirm = confirm, onConfirm = { confirm = it },
                keepSignedIn = keepSignedIn, onKeepSignedIn = { keepSignedIn = it },
                onSubmit = { if (stage == AU_SIGN_UP) submitSignUp() else submitSignIn() },
                onGoogle = { googleSignIn() },
                onReset = {
                    if (!auValidEmail(email)) {
                        localError = tr("পাসওয়ার্ড রিসেট করতে আগে ইমেইল লিখুন", "Enter your email first to reset the password")
                    } else {
                        localError = null
                        onReset(email.trim())
                    }
                },
                onFlip = { flipTo(if (stage == AU_SIGN_UP) AU_SIGN_IN else AU_SIGN_UP) }
            )
        }
    }
}

// ---------- Welcome scene ----------

@Composable
private fun WelcomeScene(reduce: Boolean, onSignIn: () -> Unit, onSignUp: () -> Unit) {
    // One cycle = 4.2 s: walk in, stop at the door, door opens, figure goes in, greeting, then fade back to start.
    val t = remember { Animatable(0f) }
    LaunchedEffect(reduce) {
        if (reduce) {
            t.snapTo(0.9f) // final frame: open door, greeting visible, figure inside
        } else {
            while (true) {
                t.snapTo(0f)
                t.animateTo(1f, tween(4200, easing = LinearEasing))
                delay(800)
            }
        }
    }
    val idle = rememberInfiniteTransition(label = "auIdle")
    val bob by idle.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "auBob"
    )
    val blink by idle.animateFloat(
        0f, 1f,
        infiniteRepeatable(
            keyframes {
                durationMillis = 3600
                0f at 0
                0f at 3300
                1f at 3360
                0f at 3420
            },
            RepeatMode.Restart
        ),
        label = "auBlink"
    )

    Column(
        Modifier
            .fillMaxSize()
            .background(auBg())
            .systemBarsPadding()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(36.dp))
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = auSkyA)) { append("Fin") }
                withStyle(SpanStyle(color = Color.White)) { append("Track") }
            },
            fontSize = 40.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            "PREMIUM FINANCE",
            color = auMuted,
            fontSize = 12.sp,
            letterSpacing = 4.sp
        )

        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            val tt = t.value
            val w = size.width
            val hgt = size.height
            val ground = hgt * 0.94f
            val doorW = w * 0.26f
            val doorH = hgt * 0.74f
            val doorX = w * 0.66f
            val doorTop = ground - doorH
            val closing = ((1f - tt) / 0.06f).coerceIn(0f, 1f)
            val open = ((tt - 0.45f) / 0.15f).coerceIn(0f, 1f) * closing

            // Faint stars
            val stars = listOf(0.10f to 0.10f, 0.22f to 0.30f, 0.14f to 0.55f, 0.36f to 0.18f, 0.44f to 0.46f, 0.08f to 0.80f)
            for ((sx, sy) in stars) drawCircle(Color(0x663B82F6), 1.5f.dp.toPx(), Offset(w * sx, hgt * sy))

            drawLine(Color(0xFF1E293B), Offset(0f, ground), Offset(w, ground), strokeWidth = 2.dp.toPx())

            if (open > 0f) {
                val cx = doorX + doorW / 2f
                val cy = doorTop + doorH / 2f
                drawCircle(
                    Brush.radialGradient(
                        listOf(auWarm.copy(alpha = 0.35f * open), Color.Transparent),
                        center = Offset(cx, cy),
                        radius = doorH * 0.9f
                    ),
                    radius = doorH * 0.9f,
                    center = Offset(cx, cy)
                )
            }
            // Door interior: dark when closed, warm light when open.
            drawRect(lerp(Color(0xFF0B1222), auWarm, open), Offset(doorX, doorTop), Size(doorW, doorH))

            // Figure: walks in from the left, stops, then walks into the door.
            val walking = tt < 0.35f || (tt >= 0.6f && tt <= 0.8f)
            val fx = when {
                tt < 0.35f -> auLerp(-0.12f, 0.56f, auEaseOut(tt / 0.35f))
                tt < 0.6f -> 0.56f
                else -> auLerp(0.56f, 0.74f, ((tt - 0.6f) / 0.2f).coerceIn(0f, 1f))
            } * w
            val alphaF = 1f - ((tt - 0.7f) / 0.1f).coerceIn(0f, 1f)
            val swing = if (walking) sin(tt * 2f * PI.toFloat() * 9f) else 0f
            val lift = if (walking) -abs(swing) * 3.dp.toPx() else -bob * 3.dp.toPx()
            if (alphaF > 0f) {
                drawFigure(
                    cx = fx,
                    feet = ground,
                    h = hgt * 0.5f,
                    swing = swing,
                    lift = lift,
                    eyeScale = if (reduce) 1f else (1f - blink).coerceIn(0.08f, 1f),
                    alpha = alphaF
                )
            }

            // Door leaf swings open toward the viewer's left.
            val leafW = doorW * (1f - 0.78f * open)
            val inset = doorH * 0.05f * open
            val leaf = Path().apply {
                moveTo(doorX, doorTop)
                lineTo(doorX + leafW, doorTop + inset)
                lineTo(doorX + leafW, ground - inset)
                lineTo(doorX, ground)
                close()
            }
            drawPath(leaf, Color(0xFF1565C0))
            drawCircle(Color.White, 3.dp.toPx(), Offset(doorX + leafW * 0.86f, doorTop + doorH * 0.52f))
            drawRoundRect(
                Color(0xFF7BA7D9),
                Offset(doorX, doorTop),
                Size(doorW, doorH),
                CornerRadius(4.dp.toPx()),
                style = Stroke(2.dp.toPx())
            )
        }

        Text(
            tr("স্বাগতম!", "Welcome!"),
            color = Color.White,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    val v = t.value
                    alpha = (((v - 0.8f) / 0.1f).coerceIn(0f, 1f)) * ((1f - v) / 0.06f).coerceIn(0f, 1f)
                }
        )
        Spacer(Modifier.height(18.dp))
        AuGradientButton(text = "SIGN IN", enabled = true, busy = false, onClick = onSignIn)
        Spacer(Modifier.height(12.dp))
        AuOutlineButton(text = "SIGN UP", onClick = onSignUp)
        Spacer(Modifier.height(32.dp))
    }
}

/** Flat-illustration figure drawn with shapes: sky shirt, dark trousers, shoulder bag, blinking eyes. */
private fun DrawScope.drawFigure(
    cx: Float,
    feet: Float,
    h: Float,
    swing: Float,
    lift: Float,
    eyeScale: Float,
    alpha: Float
) {
    val u = h / 100f
    val top = feet - h + lift
    val skin = Color(0xFFF2C79B).copy(alpha = alpha)
    val shirt = auSkyA.copy(alpha = alpha)
    val pants = Color(0xFF1E293B).copy(alpha = alpha)
    val hair = Color(0xFF111827).copy(alpha = alpha)
    val bag = Color(0xFF8B5E34).copy(alpha = alpha)
    val ink = Color(0xFF0B1222).copy(alpha = alpha)
    val headY = top + 12f * u
    val hipY = top + 62f * u

    // Shoulder bag strap and bag
    drawLine(bag, Offset(cx - 12f * u, top + 26f * u), Offset(cx + 12f * u, top + 64f * u), strokeWidth = 3f * u)
    drawRoundRect(bag, Offset(cx + 6f * u, top + 58f * u), Size(20f * u, 16f * u), CornerRadius(4f * u))
    // Legs
    drawLine(pants, Offset(cx - 6f * u, hipY), Offset(cx - swing * 16f * u, feet), strokeWidth = 12f * u, cap = StrokeCap.Round)
    drawLine(pants, Offset(cx + 6f * u, hipY), Offset(cx + swing * 16f * u, feet), strokeWidth = 12f * u, cap = StrokeCap.Round)
    // Torso
    drawRoundRect(shirt, Offset(cx - 14f * u, top + 24f * u), Size(28f * u, 40f * u), CornerRadius(12f * u))
    // Arms
    drawLine(shirt, Offset(cx - 12f * u, top + 28f * u), Offset(cx + swing * 14f * u, top + 60f * u), strokeWidth = 8f * u, cap = StrokeCap.Round)
    drawLine(shirt, Offset(cx + 12f * u, top + 28f * u), Offset(cx - swing * 14f * u, top + 60f * u), strokeWidth = 8f * u, cap = StrokeCap.Round)
    // Head with hair cap
    drawCircle(hair, 11.5f * u, Offset(cx, headY - 1.5f * u))
    drawCircle(skin, 10f * u, Offset(cx, headY + 1f * u))
    // Eyes (blink = vertical squash)
    val ey = headY + 0.5f * u
    for (ox in listOf(-3.5f * u, 3.5f * u)) {
        drawOval(
            ink,
            Offset(cx + ox - 1.4f * u, ey - 1.6f * u * eyeScale),
            Size(2.8f * u, 3.2f * u * eyeScale)
        )
    }
}

// ---------- Card scene (sign-in / sign-up share one card that flips) ----------

@Composable
private fun AuthCardScene(
    isSignUp: Boolean,
    busy: Boolean,
    shownError: String?,
    notice: String?,
    flipAngle: Float,
    shakeX: Float,
    stampScale: Float,
    name: String, onName: (String) -> Unit,
    email: String, onEmail: (String) -> Unit,
    password: String, onPassword: (String) -> Unit,
    confirm: String, onConfirm: (String) -> Unit,
    keepSignedIn: Boolean, onKeepSignedIn: (Boolean) -> Unit,
    onSubmit: () -> Unit,
    onGoogle: () -> Unit,
    onReset: () -> Unit,
    onFlip: () -> Unit
) {
    var showPwd by rememberSaveable { mutableStateOf(false) }
    var showConfirm by rememberSaveable { mutableStateOf(false) }
    val emailOk = auValidEmail(email)
    val pwdOk = password.length >= 6
    val confOk = confirm.isNotEmpty() && confirm == password && pwdOk
    val canSubmit = if (isSignUp) {
        name.isNotBlank() && emailOk && pwdOk && confOk && !busy
    } else {
        email.isNotBlank() && pwdOk && !busy
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(auBg())
            .verticalScroll(rememberScrollState())
            .systemBarsPadding()
            .padding(horizontal = 20.dp, vertical = 24.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .offset(x = shakeX.dp)
                .graphicsLayer {
                    rotationY = flipAngle
                    cameraDistance = 14f * density
                }
        ) {
            // Glass card
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 44.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(auCardFill)
                    .border(1.dp, auCardBorder, RoundedCornerShape(28.dp))
                    .padding(start = 22.dp, end = 22.dp, top = 58.dp, bottom = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    if (isSignUp) tr("অ্যাকাউন্ট খুলুন", "Create account") else tr("লগইন করুন", "Log in"),
                    color = Color.White,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Text(
                    if (isSignUp) tr("আজই যাত্রা শুরু করুন", "Start your journey today")
                    else tr("আপনার হিসাবে প্রবেশ করুন", "Enter your account"),
                    color = auMuted,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(18.dp))

                if (isSignUp) {
                    val nameOk = name.isNotBlank()
                    AuFloatField(
                        label = tr("পুরো নাম", "Full name"),
                        value = name,
                        onValueChange = onName,
                        valid = nameOk
                    )
                    Spacer(Modifier.height(12.dp))
                }
                AuFloatField(
                    label = tr("ইমেইল ঠিকানা", "Email address"),
                    value = email,
                    onValueChange = onEmail,
                    keyboardType = KeyboardType.Email,
                    valid = emailOk,
                    error = if (isSignUp && email.isNotEmpty() && !emailOk) tr("সঠিক ইমেইল লিখুন", "Enter a valid email") else null
                )
                Spacer(Modifier.height(12.dp))
                AuFloatField(
                    label = tr("পাসওয়ার্ড", "Password"),
                    value = password,
                    onValueChange = onPassword,
                    password = true,
                    showPassword = showPwd,
                    onTogglePassword = { showPwd = !showPwd },
                    valid = pwdOk,
                    error = if (isSignUp && password.isNotEmpty() && !pwdOk) tr("কমপক্ষে ৬ অক্ষর", "At least 6 characters") else null
                )
                if (isSignUp) {
                    Spacer(Modifier.height(12.dp))
                    AuFloatField(
                        label = tr("পাসওয়ার্ড নিশ্চিত করুন", "Confirm password"),
                        value = confirm,
                        onValueChange = onConfirm,
                        password = true,
                        showPassword = showConfirm,
                        onTogglePassword = { showConfirm = !showConfirm },
                        valid = confOk,
                        error = if (confirm.isNotEmpty() && confirm != password) tr("পাসওয়ার্ড মেলেনি", "Passwords do not match") else null
                    )
                } else {
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = keepSignedIn,
                            onCheckedChange = onKeepSignedIn,
                            colors = CheckboxDefaults.colors(checkedColor = auSkyA, checkmarkColor = Color(0xFF020617))
                        )
                        Text(tr("মনে রাখুন", "Remember me"), color = Color.White, fontSize = 14.sp)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onReset) {
                            Text(tr("পাসওয়ার্ড ভুলে গেছেন?", "Forgot password?"), color = auSkyA, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                AuGradientButton(
                    text = if (isSignUp) tr("অ্যাকাউন্ট খুলুন", "Create account") else tr("লগইন করুন", "Log in"),
                    enabled = canSubmit,
                    busy = busy,
                    onClick = onSubmit
                )

                if (isSignUp && notice != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        tr("নতুন অ্যাকাউন্ট", "New account"),
                        color = auOk,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .border(2.dp, auOk, RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                            .graphicsLayer {
                                scaleX = stampScale
                                scaleY = stampScale
                                rotationZ = -6f
                            }
                    )
                }

                shownError?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, color = auErr, fontSize = 13.sp, textAlign = TextAlign.Center)
                }
                if (notice != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(notice, color = auSkyA, fontSize = 13.sp, textAlign = TextAlign.Center)
                }

                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    HorizontalDivider(Modifier.weight(1f), color = auCardBorder)
                    Text(tr("অথবা", "or"), color = auMuted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp))
                    HorizontalDivider(Modifier.weight(1f), color = auCardBorder)
                }
                Spacer(Modifier.height(16.dp))
                AuGoogleButton(
                    text = if (isSignUp) tr("Google দিয়ে অ্যাকাউন্ট খুলুন", "Sign up with Google")
                    else tr("Google দিয়ে লগইন করুন", "Log in with Google"),
                    enabled = !busy,
                    onClick = onGoogle
                )

                Spacer(Modifier.height(18.dp))
                if (isSignUp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("ইতিমধ্যে অ্যাকাউন্ট আছে?", "Already have an account?"), color = auMuted, fontSize = 14.sp)
                        Spacer(Modifier.width(12.dp))
                        AuRoundButton(icon = Icons.AutoMirrored.Filled.ArrowBack, onClick = onFlip)
                    }
                } else {
                    Text(tr("অ্যাকাউন্ট নেই?", "No account yet?"), color = auMuted, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    AuRoundButton(icon = Icons.Filled.Add, onClick = onFlip)
                }
            }

            // Circular badge with the F-arrow logo, overlapping the top edge of the card.
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .size(92.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF0B1426))
                    .border(1.dp, Color(0xFF3B5B86), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                AuLogoMark(Modifier.size(54.dp))
            }
        }
    }
}

// ---------- Shared pieces ----------

/** Logo drawn with shapes: white F with a blue rising arrow. */
@Composable
private fun AuLogoMark(modifier: Modifier) {
    Canvas(modifier) {
        val s = size.minDimension / 100f
        val white = Color.White
        val arrow = Color(0xFF1E90FF)
        drawRoundRect(white, Offset(28f * s, 30f * s), Size(14f * s, 54f * s), CornerRadius(3f * s))
        drawRoundRect(white, Offset(28f * s, 30f * s), Size(44f * s, 12f * s), CornerRadius(4f * s))
        drawRoundRect(white, Offset(28f * s, 52f * s), Size(30f * s, 10f * s), CornerRadius(4f * s))
        drawLine(arrow, Offset(44f * s, 56f * s), Offset(70f * s, 28f * s), strokeWidth = 9f * s, cap = StrokeCap.Round)
        val head = Path().apply {
            moveTo(84f * s, 14f * s)
            lineTo(56f * s, 18f * s)
            lineTo(80f * s, 42f * s)
            close()
        }
        drawPath(head, arrow)
    }
}

/** Input with a floating label: the label rises above the text once focused or filled. */
@Composable
private fun AuFloatField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    showPassword: Boolean = false,
    onTogglePassword: () -> Unit = {},
    valid: Boolean = false,
    error: String? = null
) {
    var focused by remember { mutableStateOf(false) }
    val floated = focused || value.isNotEmpty()
    val labelSize by animateFloatAsState(if (floated) 12f else 15f, tween(200), label = "auLabelSize")
    val labelY by animateDpAsState(if (floated) 8.dp else 19.dp, tween(200), label = "auLabelY")
    val showCheck = valid && error == null && value.isNotEmpty()
    val endPad = when {
        password && showCheck -> 72.dp
        password -> 44.dp
        showCheck -> 36.dp
        else -> 0.dp
    }

    Column(modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(60.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(auFieldFill)
                .border(
                    1.dp,
                    if (error != null) auErr else if (focused) auSkyA.copy(alpha = 0.6f) else auFieldBorder,
                    RoundedCornerShape(16.dp)
                )
                .padding(start = 16.dp)
        ) {
            Text(
                label,
                color = if (floated) auSkyA else auMuted,
                fontSize = labelSize.sp,
                modifier = Modifier.align(Alignment.TopStart).offset(y = labelY)
            )
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                cursorBrush = SolidColor(auSkyA),
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (password) KeyboardType.Password else keyboardType
                ),
                visualTransformation = if (password && !showPassword) PasswordVisualTransformation() else VisualTransformation.None,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(bottom = 8.dp, end = endPad)
                    .onFocusChanged { focused = it.isFocused }
            )
            Row(
                Modifier.align(Alignment.CenterEnd).padding(end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (showCheck) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = auOk, modifier = Modifier.size(18.dp))
                }
                if (password) {
                    IconButton(onClick = onTogglePassword, modifier = Modifier.size(40.dp)) {
                        Icon(
                            if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = null,
                            tint = auMuted,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
        if (error != null) {
            Text(error, color = auErr, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp, top = 4.dp))
        }
    }
}

@Composable
private fun AuGradientButton(text: String, enabled: Boolean, busy: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, tween(120), label = "auPress")
    Box(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(50))
            .background(
                if (enabled) auBrush()
                else Brush.horizontalGradient(listOf(auSkyA.copy(alpha = 0.35f), auBlue.copy(alpha = 0.35f)))
            )
            .clickable(enabled = enabled, interactionSource = source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
        } else {
            Text(text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        }
    }
}

@Composable
private fun AuOutlineButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(50))
            .border(1.5f.dp, auSkyA.copy(alpha = 0.7f), RoundedCornerShape(50))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = auSkyA, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

@Composable
private fun AuGoogleButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(50))
            .background(Color.White)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("G", color = Color(0xFF4285F4), fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Spacer(Modifier.width(12.dp))
            Text(text, color = Color(0xFF0F172A), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        }
    }
}

@Composable
private fun AuRoundButton(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Box(
        Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(auBrush())
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
    }
}

/** Gets a Google ID token through Credential Manager. Requires a Web client ID from Google Cloud. */
private suspend fun googleIdToken(context: Context): String {
    val option = GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_WEB_CLIENT_ID).build()
    val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
    val result = CredentialManager.create(context).getCredential(context, request)
    return GoogleIdTokenCredential.createFrom(result.credential.data).idToken
}
