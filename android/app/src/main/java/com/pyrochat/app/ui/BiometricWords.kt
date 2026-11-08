package com.pyrochat.app.ui

import androidx.annotation.StringRes
import com.pyrochat.app.R
import com.pyrochat.app.platform.BiometricKind

/**
 * docs/BIOMETRIC-UNLOCK.md §2/§3 — the three Android labels, in the two places that must agree:
 * the `[cfg]` row and the unlock screen's button ("label matches the setting row").
 */
object BiometricWords {
    /** `unlock with fingerprint` / `unlock with face` / `unlock with biometrics`. */
    @StringRes
    fun row(kind: BiometricKind): Int = when (kind) {
        BiometricKind.FINGERPRINT -> R.string.cfg_bio_fingerprint
        BiometricKind.FACE -> R.string.cfg_bio_face
        BiometricKind.GENERIC -> R.string.cfg_bio_generic
    }

    /** The same words, boxed and shouted: `[ UNLOCK WITH FINGERPRINT ]`. */
    @StringRes
    fun button(kind: BiometricKind): Int = when (kind) {
        BiometricKind.FINGERPRINT -> R.string.auth_btn_bio_fingerprint
        BiometricKind.FACE -> R.string.auth_btn_bio_face
        BiometricKind.GENERIC -> R.string.auth_btn_bio_generic
    }
}
