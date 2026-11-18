package com.mindfilter.app.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Verrouillage de MindFilter par code maître (4 à 6 chiffres) et/ou empreinte.
 * Tout est local : le code n'est jamais stocké, seule son empreinte PBKDF2 salée l'est.
 */
object LockManager {

    data class LockState(
        /** Un code maître est défini : l'appli est verrouillée hors session. */
        val enabled: Boolean = false,
        /** Déverrouillage par empreinte / visage autorisé. */
        val biometric: Boolean = false,
        /** Protection anti-désinstallation / anti-désactivation. */
        val antiTamper: Boolean = false,
        /** Session de modification ouverte (code saisi récemment). */
        val unlocked: Boolean = false,
        /** Trop d'essais : saisie bloquée jusqu'à cet instant (elapsedRealtime). */
        val blockedUntil: Long = 0L,
    ) {
        val locked: Boolean get() = enabled && !unlocked
        val guarding: Boolean get() = enabled && antiTamper && !unlocked
    }

    sealed class Check {
        object Ok : Check()
        data class Wrong(val remaining: Int) : Check()
        data class Blocked(val seconds: Int) : Check()
    }

    const val MIN_LENGTH = 4
    const val MAX_LENGTH = 6
    /** Durée d'une session de modification après saisie du code. */
    const val SESSION_MS = 5L * 60 * 1000
    private const val FREE_TRIES = 5
    private const val ITERATIONS = 20_000
    private const val FILE = "mindfilter_lock"

    private lateinit var prefs: SharedPreferences
    private val main = Handler(Looper.getMainLooper())
    private val relock = Runnable { lock() }
    private val _state = MutableStateFlow(LockState())
    val state: StateFlow<LockState> = _state.asStateFlow()

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        _state.value = LockState(
            enabled = prefs.contains("hash"),
            biometric = prefs.getBoolean("biometric", false),
            antiTamper = prefs.getBoolean("antiTamper", false),
            blockedUntil = currentBlock(),
        )
    }

    /** Les réglages peuvent-ils être modifiés maintenant ? */
    fun canEdit(): Boolean = !::prefs.isInitialized || !_state.value.locked

    fun isGuarding(): Boolean = ::prefs.isInitialized && _state.value.guarding

    fun isValidPin(pin: String): Boolean = pin.length in MIN_LENGTH..MAX_LENGTH && pin.all { it in '0'..'9' }

    /** Définit ou change le code maître (seulement hors verrouillage). */
    fun setPin(pin: String): Boolean {
        if (!isValidPin(pin) || !canEdit()) return false
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs.edit()
            .putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("hash", Base64.encodeToString(derive(pin, salt), Base64.NO_WRAP))
            .putInt("fails", 0)
            .putLong("blockedUntil", 0L)
            .apply()
        _state.value = _state.value.copy(enabled = true, blockedUntil = 0L)
        unlock()
        return true
    }

    /** Vérifie le code ; en cas de succès, ouvre une session de modification. */
    fun verify(pin: String): Check {
        if (!::prefs.isInitialized) return Check.Wrong(0)
        val block = currentBlock()
        if (block > 0L) return Check.Blocked(((block - SystemClock.elapsedRealtime()) / 1000L).toInt().coerceAtLeast(1))
        val salt = prefs.getString("salt", null)?.let { Base64.decode(it, Base64.NO_WRAP) }
        val hash = prefs.getString("hash", null)?.let { Base64.decode(it, Base64.NO_WRAP) }
        if (salt == null || hash == null) return Check.Wrong(0)
        if (MessageDigest.isEqual(derive(pin, salt), hash)) {
            prefs.edit().putInt("fails", 0).putLong("blockedUntil", 0L).apply()
            unlock()
            return Check.Ok
        }
        val fails = prefs.getInt("fails", 0) + 1
        val edit = prefs.edit().putInt("fails", fails)
        if (fails >= FREE_TRIES) {
            // 30 s, puis 1 min, 2 min… jusqu'à 30 min.
            val wait = (30_000L shl (fails - FREE_TRIES).coerceAtMost(6)).coerceAtMost(30L * 60 * 1000)
            val until = SystemClock.elapsedRealtime() + wait
            edit.putLong("blockedUntil", until).putLong("blockedBoot", bootId())
            _state.value = _state.value.copy(blockedUntil = until)
            edit.apply()
            return Check.Blocked((wait / 1000L).toInt())
        }
        edit.apply()
        return Check.Wrong(FREE_TRIES - fails)
    }

    /** Déverrouillage biométrique réussi. */
    fun unlockWithBiometric() {
        if (_state.value.enabled && _state.value.biometric) {
            prefs.edit().putInt("fails", 0).apply()
            unlock()
        }
    }

    fun lock() {
        main.removeCallbacks(relock)
        _state.value = _state.value.copy(unlocked = false)
    }

    fun setBiometric(on: Boolean) {
        if (!canEdit()) return
        prefs.edit().putBoolean("biometric", on).apply()
        _state.value = _state.value.copy(biometric = on)
    }

    fun setAntiTamper(on: Boolean) {
        if (!canEdit()) return
        prefs.edit().putBoolean("antiTamper", on).apply()
        _state.value = _state.value.copy(antiTamper = on)
    }

    /** Supprime le code maître (session ouverte obligatoire). */
    fun disable() {
        if (!canEdit()) return
        prefs.edit().clear().apply()
        main.removeCallbacks(relock)
        _state.value = LockState()
    }

    private fun unlock() {
        main.removeCallbacks(relock)
        main.postDelayed(relock, SESSION_MS)
        _state.value = _state.value.copy(unlocked = true)
    }

    private fun currentBlock(): Long {
        val until = prefs.getLong("blockedUntil", 0L)
        // Après un redémarrage, l'horloge repart de zéro : le blocage est reconduit en entier.
        if (until > 0L && kotlin.math.abs(prefs.getLong("blockedBoot", 0L) - bootId()) > 120_000L) {
            val fresh = SystemClock.elapsedRealtime() + 60_000L
            prefs.edit().putLong("blockedUntil", fresh).putLong("blockedBoot", bootId()).apply()
            return fresh
        }
        return if (until > SystemClock.elapsedRealtime()) until else 0L
    }

    /** Heure de démarrage du téléphone (stable à quelques secondes près pendant une session). */
    private fun bootId(): Long = System.currentTimeMillis() - SystemClock.elapsedRealtime()

    private fun derive(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
