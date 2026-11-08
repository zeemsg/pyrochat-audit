package com.pyrochat.app.platform

import android.app.Activity
import android.os.Build
import android.view.WindowManager
import androidx.annotation.RequiresApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import java.util.function.Consumer

/**
 * The capture notifications `ChatView` listens to (`ios/PyroChat/UI/ChatView.swift:164-173`):
 * `userDidTakeScreenshotNotification` → `reportCapture("screenshot")` and
 * `UIScreen.isCaptured` → `reportCapture("screen_record")`.
 *
 * - [screenshots]: API 34+ `Activity.ScreenCaptureCallback` (manifest permission
 *   `DETECT_SCREEN_CAPTURE`, install-time). Fires for hardware-button/gesture screenshots only,
 *   not ADB `screencap` and not screen recording; no image is provided. Registered while the
 *   flow is collected — collect between `onStart`/`onStop` (a `repeatOnLifecycle(STARTED)` fits).
 * - [recording]: API 35+ `WindowManager.addScreenRecordingCallback` (permission
 *   `DETECT_SCREEN_RECORDING`), emitting the initial state then every change.
 *
 * Below those API levels both flows are documented no-ops (`emptyFlow()`): a device on Android
 * 10–13 has no supported detection, and `FLAG_SECURE` (`MainActivity`) already blanks captures.
 */
object ScreenCaptureDetector {
    val screenshotsSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    val recordingSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM

    /** One `Unit` per screenshot taken of [activity] while collected. */
    fun screenshots(activity: Activity): Flow<Unit> =
        if (screenshotsSupported) screenshots34(activity) else emptyFlow()

    /** True while the activity is visible in an ongoing screen recording, false otherwise. */
    fun recording(activity: Activity): Flow<Boolean> =
        if (recordingSupported) recording35(activity) else emptyFlow()

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun screenshots34(activity: Activity): Flow<Unit> = callbackFlow {
        val callback = Activity.ScreenCaptureCallback { trySend(Unit) }
        activity.registerScreenCaptureCallback(activity.mainExecutor, callback)
        awaitClose { activity.unregisterScreenCaptureCallback(callback) }
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun recording35(activity: Activity): Flow<Boolean> = callbackFlow {
        val wm = activity.windowManager
        val callback = Consumer<Int> { state -> trySend(state == WindowManager.SCREEN_RECORDING_STATE_VISIBLE) }
        val initial = wm.addScreenRecordingCallback(activity.mainExecutor, callback)
        trySend(initial == WindowManager.SCREEN_RECORDING_STATE_VISIBLE)
        awaitClose { wm.removeScreenRecordingCallback(callback) }
    }
}
