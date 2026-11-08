package com.pyrochat.app.ui.qr

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.pyrochat.app.BuildConfig
import com.pyrochat.app.R
import com.pyrochat.app.ui.components.Glyph
import com.pyrochat.app.ui.components.TerminalTextField
import com.pyrochat.app.ui.theme.LocalTerminal
import com.pyrochat.app.ui.theme.mono

/**
 * Port of iOS `QRScanner` (`ios/PyroChat/UI/LinkDesktopView.swift:155-229`): an AVFoundation
 * metadata output restricted to `.qr` becomes a CameraX preview with an ML Kit barcode analyzer
 * restricted to `FORMAT_QR_CODE` (bundled model — decodes offline, no Play Services download).
 *
 * Semantics kept 1:1:
 * - `requestAccess(for: .video)` → the runtime CAMERA prompt on first composition; a refusal (or
 *   an earlier "don't ask again") calls [onDenied] and the host shows its denied pane.
 * - every decoded string goes to [accept]; TRUE stops the camera for good (one-shot latch —
 *   rebuild the composable with `key(generation)` for a fresh one, iOS `.id(scanGeneration)`);
 *   FALSE keeps scanning. A rejected code is re-offered only after [ScanLatch.DEBOUNCE_NANOS].
 * - no torch, no zoom, no tap-to-focus: iOS has none.
 * - the preview fills its box edge to edge (`videoGravity = .resizeAspectFill`).
 */
@Composable
fun QrScanner(accept: (String) -> Boolean, onDenied: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val currentDenied by rememberUpdatedState(onDenied)
    var granted by remember { mutableStateOf(hasCameraPermission(context)) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) granted = true else currentDenied()
    }
    LaunchedEffect(Unit) { if (!granted) request.launch(Manifest.permission.CAMERA) }
    Box(modifier.clipToBounds()) { // the aspect-fill preview must not spill over the host's chrome
        if (granted) CameraPane(accept)
    }
}

fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

/** `UIApplication.openSettingsURLString` — this app's details page, where the camera permission lives. */
fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

@Composable
private fun CameraPane(accept: (String) -> Boolean) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentAccept by rememberUpdatedState(accept)
    val latch = remember { ScanLatch() }
    val scanner = remember {
        BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
    }
    val controller = remember { LifecycleCameraController(context) }

    DisposableEffect(controller, lifecycleOwner) {
        val executor = ContextCompat.getMainExecutor(context)
        controller.cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        controller.setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
        // 720p analysis frames: dense link codes (~110 chars) decode at arm's length; the
        // default 640×480 is marginal for those
        controller.imageAnalysisResolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
            .build()
        controller.imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
        controller.setImageAnalysisAnalyzer(
            executor,
            MlKitAnalyzer(listOf(scanner), ImageAnalysis.COORDINATE_SYSTEM_ORIGINAL, executor) { result ->
                if (latch.fired) return@MlKitAnalyzer
                val codes = result.getValue(scanner) ?: return@MlKitAnalyzer
                // `objects.first(where: { $0.type == .qr })?.stringValue`
                val value = codes.firstOrNull { it.format == Barcode.FORMAT_QR_CODE }?.rawValue ?: return@MlKitAnalyzer
                if (!latch.offer(value, System.nanoTime())) return@MlKitAnalyzer
                if (currentAccept(value)) {
                    latch.fire()
                    controller.clearImageAnalysisAnalyzer()
                    controller.unbind() // `session.stopRunning()`
                }
            },
        )
        controller.bindToLifecycle(lifecycleOwner)
        onDispose {
            controller.clearImageAnalysisAnalyzer()
            controller.unbind()
            scanner.close()
        }
    }

    AndroidView(
        factory = { ctx ->
            PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                this.controller = controller
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

/**
 * DEBUG builds only — the iOS simulator's paste field (`LinkDesktopView.pasteField`) so the
 * emulator can drive the same `handle` path without a camera. Never compiled into a release:
 * [BuildConfig.DEBUG] is a constant there.
 */
@Composable
fun DebugPasteField(hint: String, placeholder: String, button: String? = null, onSubmit: (String) -> Unit) {
    if (!BuildConfig.DEBUG) return
    PasteField(hint, placeholder, button, onSubmit)
}

/**
 * The [qr] screen's paste field, on EVERY build (docs/FIRST-RUN.md §2): the recipient of a
 * `[share]` text pastes the `pyro-add:` code and is added + verified exactly like a scan; an
 * access key in either form lands in the ACCESS KEY dialog. Submit = the keyboard's done action
 * or the [button] (`[ USE ]` by default) — iOS `AddByQRView.pasteField`, same accessibility labels.
 */
@Composable
fun PasteField(hint: String, placeholder: String, button: String? = null, onSubmit: (String) -> Unit) {
    val t = LocalTerminal.current
    var pasted by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(hint, style = mono(11), color = t.fgDim)
        Row(
            Modifier.fillMaxWidth().background(t.panel).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TerminalTextField(
                pasted, { pasted = it }, Modifier.weight(1f),
                size = 12, placeholder = placeholder, imeAction = ImeAction.Done,
                onDone = { onSubmit(pasted) }, contentDescription = "Paste a code",
            )
            Glyph(button ?: stringResource(R.string.btn_use), 12, weight = FontWeight.Bold, label = "Use pasted code") { onSubmit(pasted) }
        }
    }
}
