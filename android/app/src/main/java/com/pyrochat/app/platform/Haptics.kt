package com.pyrochat.app.platform

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Port of `Haptics` (`ios/PyroChat/Core/Haptics.swift:5-12`):
 * - [tap] = `UIImpactFeedbackGenerator(style: .light).impactOccurred()` → `EFFECT_TICK`
 *   (send `[TX]`, image paste, QR link accepted, QR add success);
 * - [warn] = `UINotificationFeedbackGenerator().notificationOccurred(.warning)` →
 *   `EFFECT_DOUBLE_CLICK` (the two-pulse warning pattern: `[burn]`, RM CHANNEL, DELETE FOR BOTH,
 *   wrong-relay QR, QR add failure).
 * Predefined effects (API 29+) let the OEM haptic driver render them, like UIKit's generators.
 * No-op without a vibrator (emulators).
 */
class Haptics(context: Context) {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    val available: Boolean get() = vibrator?.hasVibrator() == true

    fun tap() = play(VibrationEffect.EFFECT_TICK)

    fun warn() = play(VibrationEffect.EFFECT_DOUBLE_CLICK)

    private fun play(effectId: Int) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        val effect = VibrationEffect.createPredefined(effectId)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
        } else {
            v.vibrate(effect)
        }
    }
}
