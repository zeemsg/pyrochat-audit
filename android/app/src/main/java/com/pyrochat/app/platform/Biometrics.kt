package com.pyrochat.app.platform

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import javax.crypto.Cipher
import kotlin.coroutines.resume

/** The outcome of one [BiometricPrompt] run. */
sealed interface BiometricAuth {
    /** Authenticated; [cipher] is the one the Keystore released. */
    data class Ok(val cipher: Cipher) : BiometricAuth

    /** The user dismissed the sheet (§3: nothing is shown, they simply type the password). */
    data object Cancelled : BiometricAuth

    /** A terminal no-match — lockout or a timeout after repeated failures. */
    data object NotRecognised : BiometricAuth

    /** The OS refused the key: enrolment changed under us (§4). */
    data object Invalidated : BiometricAuth

    /** Anything else the framework reported; shown verbatim. */
    data class Failed(val message: String) : BiometricAuth
}

/**
 * docs/BIOMETRIC-UNLOCK.md §1 — one BiometricPrompt run: `BIOMETRIC_STRONG` (Class 3) only, with
 * NO device-credential fallback, over a `CryptoObject` so a successful prompt is what releases the
 * Keystore key.
 *
 * The UI builds one (it owns the activity and the words); the backend only hands it a cipher, so
 * nothing user-visible leaks into [com.pyrochat.app.ui.Backend].
 */
fun interface BiometricPrompter {
    /**
     * [onNoMatch] fires on each non-match while the sheet is up — §4 counts these, never
     * cancellations — and returns false to give up, which cancels the sheet.
     */
    suspend fun authenticate(cipher: Cipher, onNoMatch: () -> Boolean): BiometricAuth
}

/** Hardware + enrolment questions, and the prompt itself. */
object Biometrics {
    /**
     * §2: which words the row wears, or null when the row is hidden entirely — the device has no
     * biometric hardware, nothing is enrolled, or no Class 3 sensor is usable right now.
     */
    fun kind(context: Context): BiometricKind? {
        if (BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG) != BiometricManager.BIOMETRIC_SUCCESS) return null
        val pm = context.packageManager
        return BiometricKind.of(
            fingerprint = pm.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT),
            face = pm.hasSystemFeature(PackageManager.FEATURE_FACE),
            iris = pm.hasSystemFeature(PackageManager.FEATURE_IRIS),
        )
    }

    /**
     * §5 last clause: biometry is GONE from this device — no hardware at all, or the user removed
     * every enrolment. Only then is the stored key destroyed; a sensor that is merely busy or
     * updating is not a reason to throw the key away.
     */
    fun gone(context: Context): Boolean = when (BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG)) {
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED,
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
        -> true
        else -> false
    }

    /** The nearest [FragmentActivity] — BiometricPrompt hosts its sheet in one. */
    fun activityOf(context: Context): FragmentActivity? {
        var c: Context? = context
        while (c is ContextWrapper) {
            if (c is FragmentActivity) return c
            c = c.baseContext
        }
        return null
    }

    /** Runs the sheet. Must be called on the main thread (the caller hops for us). */
    suspend fun prompt(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        negative: String,
        cipher: Cipher,
        onNoMatch: () -> Boolean,
    ): BiometricAuth = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { cont ->
            var sheet: BiometricPrompt? = null
            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (!cont.isActive) return
                    val released = result.cryptoObject?.cipher
                    cont.resume(if (released != null) BiometricAuth.Ok(released) else BiometricAuth.Failed("no key released"))
                }

                /** A non-match. The sheet stays up and the user may try again — §4 counts this. */
                override fun onAuthenticationFailed() {
                    if (!onNoMatch()) sheet?.cancelAuthentication()
                }

                override fun onAuthenticationError(code: Int, message: CharSequence) {
                    if (!cont.isActive) return
                    cont.resume(
                        when (code) {
                            BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                            BiometricPrompt.ERROR_USER_CANCELED,
                            BiometricPrompt.ERROR_CANCELED,
                            BiometricPrompt.ERROR_TIMEOUT,
                            -> BiometricAuth.Cancelled
                            BiometricPrompt.ERROR_LOCKOUT,
                            BiometricPrompt.ERROR_LOCKOUT_PERMANENT,
                            -> BiometricAuth.NotRecognised
                            BiometricPrompt.ERROR_NO_BIOMETRICS,
                            BiometricPrompt.ERROR_HW_NOT_PRESENT,
                            -> BiometricAuth.Invalidated
                            else -> BiometricAuth.Failed(message.toString())
                        },
                    )
                }
            }
            val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback)
            sheet = prompt
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                // the ONLY way out of the sheet other than a match: never a PIN or a pattern (§1)
                .setNegativeButtonText(negative)
                .setAllowedAuthenticators(BIOMETRIC_STRONG)
                .setConfirmationRequired(false)
                .build()
            cont.invokeOnCancellation { prompt.cancelAuthentication() }
            runCatching { prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher)) }
                .onFailure { if (cont.isActive) cont.resume(BiometricAuth.Failed(it.message ?: "prompt unavailable")) }
        }
    }
}

/** A [BiometricPrompter] bound to this composition's activity and these words. */
@Composable
fun rememberBiometricPrompter(title: String, subtitle: String, negative: String): BiometricPrompter {
    val context = LocalContext.current
    return remember(context, title, subtitle, negative) {
        val activity = Biometrics.activityOf(context)
        BiometricPrompter { cipher, onNoMatch ->
            if (activity == null) {
                BiometricAuth.Failed("no biometric host")
            } else {
                Biometrics.prompt(activity, title, subtitle, negative, cipher, onNoMatch)
            }
        }
    }
}
