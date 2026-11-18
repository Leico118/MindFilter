package com.mindfilter.app.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mindfilter.app.data.LockManager
import com.mindfilter.app.ui.theme.LocalGlass
import kotlinx.coroutines.delay

// ---------------------------------------------------------------------------------------------
// Biométrie
// ---------------------------------------------------------------------------------------------

private const val AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_WEAK

fun biometricAvailable(context: Context): Boolean = try {
    BiometricManager.from(context).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS
} catch (_: Exception) {
    false
}

private tailrec fun Context.findActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

fun launchBiometric(context: Context, onSuccess: () -> Unit) {
    val activity = context.findActivity() ?: return
    if (!biometricAvailable(activity)) return
    try {
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Déverrouiller MindFilter")
            .setSubtitle("Modification des réglages protégés")
            .setNegativeButtonText("Code maître")
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()
        prompt.authenticate(info)
    } catch (_: Exception) {
    }
}

// ---------------------------------------------------------------------------------------------
// Pavé numérique
// ---------------------------------------------------------------------------------------------

@Composable
private fun PinDots(length: Int, error: Boolean) {
    val g = LocalGlass.current
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        for (i in 0 until LockManager.MAX_LENGTH) {
            val filled = i < length
            Box(
                Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            error -> g.danger
                            filled -> g.accent
                            else -> Color.Transparent
                        },
                    )
                    .border(1.5.dp, if (error) g.danger else if (i < LockManager.MIN_LENGTH || filled) g.accent else g.textSecondary.copy(alpha = 0.4f), CircleShape),
            )
        }
    }
}

@Composable
private fun Key(modifier: Modifier, label: String? = null, icon: ImageVector? = null, enabled: Boolean = true, onClick: () -> Unit) {
    val g = LocalGlass.current
    Box(
        modifier
            .size(74.dp)
            .clip(CircleShape)
            .background(if (label != null) g.chip else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (label != null) Text(label, color = g.text, fontSize = 28.sp, fontWeight = FontWeight.Medium)
        if (icon != null) Icon(icon, contentDescription = null, tint = if (enabled) g.text else g.textSecondary.copy(alpha = 0.3f), modifier = Modifier.size(28.dp))
    }
}

/**
 * Saisie d'un code de 4 à 6 chiffres. À 6 chiffres, validation automatique ; sinon bouton
 * « Valider ». [onSubmit] renvoie un message d'erreur (null = accepté).
 */
@Composable
fun PinPad(
    title: String,
    subtitle: String?,
    notice: String? = null,
    onBiometric: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
    onSubmit: (String) -> String?,
) {
    val g = LocalGlass.current
    var pin by remember(title) { mutableStateOf("") }
    var error by remember(title) { mutableStateOf<String?>(null) }

    fun submit() {
        if (pin.length < LockManager.MIN_LENGTH) return
        val e = onSubmit(pin)
        error = e
        pin = ""
    }

    fun type(d: Char) {
        if (pin.length >= LockManager.MAX_LENGTH) return
        error = null
        pin += d
        if (pin.length == LockManager.MAX_LENGTH) submit()
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        IconBubble(Icons.Filled.Lock, g.accent, 56.dp)
        Spacer(Modifier.height(16.dp))
        Text(title, color = g.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        if (subtitle != null) {
            Spacer(Modifier.height(6.dp))
            Text(subtitle, color = g.textSecondary, fontSize = 14.sp, textAlign = TextAlign.Center)
        }
        if (notice != null) {
            Spacer(Modifier.height(10.dp))
            Text(notice, color = g.warning, fontSize = 14.sp, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(24.dp))
        PinDots(pin.length, error != null)
        Spacer(Modifier.height(10.dp))
        Text(error ?: " ", color = g.danger, fontSize = 13.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(18.dp))
        val rows = listOf("123", "456", "789")
        for (r in rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                for (c in r) Key(Modifier, label = c.toString()) { type(c) }
            }
            Spacer(Modifier.height(14.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBiometric != null) {
                Key(Modifier, icon = Icons.Filled.Fingerprint) { onBiometric() }
            } else {
                Spacer(Modifier.width(74.dp))
            }
            Key(Modifier, label = "0") { type('0') }
            Key(Modifier, icon = Icons.AutoMirrored.Filled.Backspace, enabled = pin.isNotEmpty()) {
                pin = pin.dropLast(1)
                error = null
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (onCancel != null) PillButton("Annuler", Modifier.weight(1f), filled = false) { onCancel() }
            PillButton("Valider", Modifier.weight(1f), filled = pin.length >= LockManager.MIN_LENGTH) { submit() }
        }
        Spacer(Modifier.weight(1f))
    }
}

// ---------------------------------------------------------------------------------------------
// Écran de verrouillage (toute l'appli)
// ---------------------------------------------------------------------------------------------

@Composable
fun LockGate(notice: String?) {
    val ctx = LocalContext.current
    val lock by LockManager.state.collectAsStateWithLifecycle()
    val bio = lock.biometric && remember { biometricAvailable(ctx) }
    // Blocage après trop d'essais : compte à rebours affiché.
    var now by remember { mutableStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(lock.blockedUntil) {
        while (lock.blockedUntil > android.os.SystemClock.elapsedRealtime()) {
            now = android.os.SystemClock.elapsedRealtime()
            delay(1000)
        }
        now = android.os.SystemClock.elapsedRealtime()
    }
    LaunchedEffect(Unit) {
        if (bio) launchBiometric(ctx) { LockManager.unlockWithBiometric() }
    }
    val waitS = ((lock.blockedUntil - now) / 1000L).coerceAtLeast(0L)
    Box(Modifier.fillMaxSize().background(LocalGlass.current.bgTop)) {
        GlassBackground {
            PinPad(
                title = "MindFilter est verrouillé",
                subtitle = if (waitS > 0) "Trop d'essais. Réessayez dans $waitS s." else "Saisissez le code maître pour modifier les réglages.",
                notice = notice,
                onBiometric = if (bio) ({ launchBiometric(ctx) { LockManager.unlockWithBiometric() } }) else null,
            ) { pin ->
                when (val r = LockManager.verify(pin)) {
                    LockManager.Check.Ok -> null
                    is LockManager.Check.Wrong -> if (r.remaining > 0) "Code incorrect · encore ${r.remaining} essai(s)" else "Code incorrect"
                    is LockManager.Check.Blocked -> "Trop d'essais. Réessayez dans ${r.seconds} s."
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Création / changement du code maître
// ---------------------------------------------------------------------------------------------

@Composable
fun PinSetupScreen(onDone: () -> Unit) {
    var first by rememberSaveable { mutableStateOf<String?>(null) }
    var mismatch by rememberSaveable { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(LocalGlass.current.bgTop)) {
        GlassBackground {
            if (first == null) {
                PinPad(
                    title = "Nouveau code maître",
                    subtitle = "De ${LockManager.MIN_LENGTH} à ${LockManager.MAX_LENGTH} chiffres",
                    notice = if (mismatch) "Les deux codes étaient différents : recommencez." else null,
                    onCancel = onDone,
                ) { pin ->
                    mismatch = false
                    first = pin
                    null
                }
            } else {
                PinPad(
                    title = "Confirmez le code",
                    subtitle = "Saisissez-le une deuxième fois",
                    onCancel = { first = null },
                ) { pin ->
                    if (pin != first) {
                        mismatch = true
                        first = null
                        null
                    } else {
                        LockManager.setPin(pin)
                        onDone()
                        null
                    }
                }
            }
        }
    }
}
