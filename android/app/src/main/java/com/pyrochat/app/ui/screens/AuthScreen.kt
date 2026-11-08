package com.pyrochat.app.ui.screens

import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pyrochat.app.BuildConfig
import com.pyrochat.app.R
import com.pyrochat.app.platform.UnlockFailurePolicy
import com.pyrochat.app.platform.rememberBiometricPrompter
import com.pyrochat.app.ui.Backend
import com.pyrochat.app.ui.BiometricGate
import com.pyrochat.app.ui.BiometricOutcome
import com.pyrochat.app.ui.BiometricWords
import com.pyrochat.app.ui.components.BoxedButton
import com.pyrochat.app.ui.components.TerminalAlert
import com.pyrochat.app.ui.components.DialogButton
import com.pyrochat.app.ui.components.Glyph
import com.pyrochat.app.ui.components.LabeledField
import com.pyrochat.app.ui.theme.LocalTerminal
import com.pyrochat.app.ui.theme.mono
import kotlinx.coroutines.launch

private enum class Pane { Choose, Create, SignIn, Restore }

/**
 * `AuthView` — sign-in / chooser / create / restore. Every launch opens on the
 * password form; the chooser is one tap behind it, for the launch that is not
 * a sign-in. Nothing here reads the filesystem to decide which to show.
 * [notice] (docs/FIRST-RUN.md §3): what became of an access key that arrived
 * while locked — `! create your identity first, then scan the key again` / `! not an access key`.
 */
@Composable
fun AuthScreen(backend: Backend, notice: String? = null) {
    val t = LocalTerminal.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // docs/SCRUB-AND-WIPE.md §A2 — read before anything is decrypted; 0 on a device nobody guessed at
    val unlockFailures by backend.unlockFailures.collectAsStateWithLifecycle()

    // Start on the PASSWORD FORM, unconditionally.
    //
    // docs/DECOY-ACCOUNT.md §5 removed the auto-routing that used to read the filesystem, because
    // showing a different first screen depending on whether an identity existed answered the exact
    // question this design exists to leave unanswered. Rooting everyone on the chooser satisfied
    // that and charged every returning user an extra tap on every single launch — which is what a
    // tester reported, fairly, as a bug.
    //
    // Defaulting to the form gives nothing back: it is the SAME first screen on a phone that has
    // never held an account, on one holding two, and on one wiped ten minutes ago. The leak was
    // ever only in BRANCHING on what is there, and nothing below branches. Creating and restoring
    // stay one tap away, which is the right way round — signing in happens daily, creating an
    // identity happens once.
    var pane by remember { mutableStateOf(Pane.SignIn) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var backupBytes by remember { mutableStateOf<ByteArray?>(null) }
    var backupName by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    /** §1 "Creating an identity from cold" — unconditional, so its appearance says nothing. */
    var confirmSlot by remember { mutableStateOf(false) }

    // docs/BIOMETRIC-UNLOCK.md §3 — asked before anything is decrypted, so it can never drift from
    // the encrypted blob. `note` is the one row §3's table allows; null shows nothing at all.
    var gate by remember { mutableStateOf<BiometricGate>(BiometricGate.Off) }
    var bioNote by remember { mutableStateOf<Int?>(null) }
    var bioBusy by remember { mutableStateOf(false) }
    val prompter = rememberBiometricPrompter(
        title = stringResource(R.string.bio_prompt_title),
        subtitle = stringResource(R.string.bio_prompt_unlock),
        negative = stringResource(R.string.bio_prompt_negative),
    )

    suspend fun attemptBiometry() {
        if (bioBusy) return
        bioBusy = true
        try {
            when (backend.unlockWithBiometrics(prompter)) {
                BiometricOutcome.Ok -> Unit                       // the stage moved; this screen is gone
                BiometricOutcome.Cancelled -> bioNote = null      // §3: silently fall back to the field
                BiometricOutcome.NotRecognised -> bioNote = R.string.auth_bio_not_recognised
                BiometricOutcome.Invalidated -> {
                    gate = BiometricGate.Off
                    bioNote = R.string.auth_bio_changed
                }
                BiometricOutcome.PasswordRequired -> {
                    gate = BiometricGate.PasswordRequired
                    bioNote = null
                }
                is BiometricOutcome.Failed -> {
                    gate = BiometricGate.Off
                    bioNote = null
                }
            }
        } finally {
            bioBusy = false
        }
    }

    // §3: attempt biometry once automatically when the screen appears — never again on its own
    LaunchedEffect(pane) {
        if (pane != Pane.SignIn) return@LaunchedEffect
        bioNote = null
        gate = backend.biometricGate()
        if (gate is BiometricGate.Ready) attemptBiometry()
    }

    // duress wipe / burn: the device state changed under us — re-root to the password form, which
    // is what a launch shows too (§5), so a wipe and a fresh install leave the same screen
    LaunchedEffect(Unit) {
        pane = Pane.SignIn
        error = null; password = ""; confirm = ""
    }

    // The chooser is a sub-screen now, so system back has somewhere to go. It follows the header
    // glyph exactly — create/restore step back to the chooser they were opened from, the chooser
    // steps back to the root form. At the root it stays disabled and back leaves the app, as before.
    BackHandler(enabled = pane != Pane.SignIn) {
        pane = if (pane == Pane.Choose) Pane.SignIn else Pane.Choose
        error = null
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
        if (bytes == null) { error = context.getString(R.string.auth_err_could_not_read_file); return@rememberLauncherForActivityResult }
        var name = uri.lastPathSegment ?: "backup"
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) name = c.getString(0) ?: name
        }
        backupBytes = bytes; backupName = name; error = null
    }

    fun submit(action: suspend () -> Unit) = scope.launch {
        error = null; busy = true
        try { action() } finally { busy = false }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // header
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (pane != Pane.Choose) {
                // On the root form this reaches the chooser rather than going back to anywhere, so
                // it says what it is for. Elsewhere it is an ordinary back.
                val label = if (pane == Pane.SignIn) "Other options: create an identity or restore a backup" else "Back"
                Glyph("[<]", 14, weight = FontWeight.Bold, color = t.fgDim, label = label) { pane = Pane.Choose; error = null }
            }
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = t.flame)) { append("PYROCHAT") }
                    append(stringResource(R.string.auth_header_suffix))
                },
                style = mono(15, FontWeight.Bold),
            )
        }
        if (BuildConfig.ACCESS_KEY_UI) notice?.let { Text(it, style = mono(12), color = t.alert, modifier = Modifier.semantics { contentDescription = "Access key notice" }) }

        @Composable
        fun errorLine() {
            error?.let { Text("! $it", style = mono(12), color = t.alert) }
        }

        @Composable
        fun submitButton(label: String, working: String, action: suspend () -> Unit) {
            BoxedButton(if (busy) "$working …]" else label, enabled = !busy) {
                submit { action() }
            }
        }

        when (pane) {
            Pane.Choose -> Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // §5: neither "no identity on this device yet" nor "an identity lives on this
                // device" — both are claims the app is not in a position to make.
                Text(stringResource(R.string.auth_choose_neutral), style = mono(12), color = t.fgDim)
                BoxedButton(stringResource(R.string.auth_btn_sign_in), label = "Sign in") { pane = Pane.SignIn; error = null }
                BoxedButton(stringResource(R.string.auth_btn_create_identity), label = "Create identity") { pane = Pane.Create; error = null }
                BoxedButton(stringResource(R.string.auth_btn_restore_backup), color = t.fgDim, stroke = t.fgDim, label = "Restore from backup") { pane = Pane.Restore; error = null }
                Text(stringResource(R.string.auth_choose_footnote), style = mono(11), color = t.fgDim.copy(alpha = t.fgDim.alpha * 0.8f))
            }

            Pane.SignIn -> run {
                Text(stringResource(R.string.auth_signin_prompt), style = mono(12), color = t.fgDim)
                // BIOMETRIC-UNLOCK.md §3: the button above the password field — which stays visible
                // and usable at all times, so the duress pin is always reachable.
                (gate as? BiometricGate.Ready)?.let { ready ->
                    BoxedButton(
                        stringResource(BiometricWords.button(ready.kind)),
                        size = 13,
                        enabled = !bioBusy && !busy,
                        label = "Unlock with biometrics",
                    ) { scope.launch { attemptBiometry() } }
                }
                if (gate is BiometricGate.PasswordRequired) {
                    Text(stringResource(R.string.auth_bio_password_required), style = mono(11), color = t.fgDim)
                }
                bioNote?.let { Text(stringResource(it), style = mono(12), color = if (it == R.string.auth_bio_not_recognised) t.alert else t.fgDim) }
                // docs/SCRUB-AND-WIPE.md §A2 — above the password field, in the alert colour, from
                // the seventh failure. The screen decides nothing: the count comes out of the plain
                // preferences file and `UnlockFailurePolicy` turns it into words or into silence.
                (UnlockFailurePolicy.decide(unlockFailures, System.currentTimeMillis()) as? UnlockFailurePolicy.Decision.Warn)?.let { warn ->
                    Text(
                        stringResource(R.string.auth_wipe_warning, warn.attemptsLeft),
                        style = mono(12), color = t.alert,
                        modifier = Modifier.semantics { contentDescription = "Wipe warning" },
                    )
                }
                LabeledField(stringResource(R.string.auth_field_password), password, { password = it }, secure = true, imeAction = ImeAction.Go)
                errorLine()
                submitButton(stringResource(R.string.auth_btn_unlock), "[ UNLOCK") {
                    if (password.isEmpty()) { error = context.getString(R.string.auth_err_enter_password); return@submitButton }
                    error = backend.unlock(password)
                }
                // The way out of the form, for the launch that is not a sign-in. A question about
                // the PERSON, never a claim about the DEVICE — so it reads identically whatever this
                // phone holds, and the screen still asserts nothing.
                Text(
                    stringResource(R.string.auth_signin_other),
                    style = mono(11), color = t.fgDim,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Glyph(
                    stringResource(R.string.auth_btn_other_options),
                    12, weight = FontWeight.Bold, color = t.fgDim,
                    label = "Create an identity or restore a backup",
                ) { pane = Pane.Choose; error = null }
            }

            Pane.Create -> run {
                Text(stringResource(R.string.auth_create_prompt), style = mono(12), color = t.fgDim)
                LabeledField(stringResource(R.string.auth_field_username), username, { username = it })
                LabeledField(stringResource(R.string.auth_field_password), password, { password = it }, secure = true)
                LabeledField(stringResource(R.string.auth_field_confirm), confirm, { confirm = it }, secure = true, imeAction = ImeAction.Go)
                errorLine()
                submitButton(stringResource(R.string.auth_btn_ignite), "[ IGNITE") {
                    if (username.length < 3 || !username.all { it.isLetterOrDigit() || it == '_' }) { error = context.getString(R.string.auth_err_username); return@submitButton }
                    if (password.length < 8) { error = context.getString(R.string.auth_err_password); return@submitButton }
                    if (password != confirm) { error = context.getString(R.string.auth_err_mismatch); return@submitButton }
                    // docs/DECOY-ACCOUNT.md §1: the slot confirmation, EVERY time and whatever the
                    // app knows. Conditioning it on anything would answer the question this design
                    // exists to leave unanswered; always showing it carries no information at all.
                    confirmSlot = true
                }
                Text(stringResource(R.string.auth_create_footnote), style = mono(10), color = t.fgDim.copy(alpha = t.fgDim.alpha * 0.8f))
            }

            Pane.Restore -> run {
                Text(stringResource(R.string.auth_restore_prompt), style = mono(12), color = t.fgDim)
                BoxedButton(
                    if (backupBytes == null) stringResource(R.string.auth_btn_choose_backup) else "[ $backupName ✓ ]",
                    size = 13, vertical = 8.dp, horizontal = 12.dp,
                ) { filePicker.launch(arrayOf("*/*")) }
                LabeledField(stringResource(R.string.auth_field_backup_passphrase), passphrase, { passphrase = it }, secure = true)
                Text(stringResource(R.string.auth_restore_then), style = mono(11), color = t.fgDim)
                LabeledField(stringResource(R.string.auth_field_new_password), password, { password = it }, secure = true)
                LabeledField(stringResource(R.string.auth_field_confirm_wide), confirm, { confirm = it }, secure = true, imeAction = ImeAction.Go)
                errorLine()
                submitButton(stringResource(R.string.auth_btn_restore), "[ RESTORING") {
                    val bytes = backupBytes
                    if (bytes == null) { error = context.getString(R.string.auth_err_choose_backup); return@submitButton }
                    if (passphrase.isEmpty()) { error = context.getString(R.string.auth_err_enter_passphrase); return@submitButton }
                    if (password.length < 8) { error = context.getString(R.string.auth_err_new_password); return@submitButton }
                    if (password != confirm) { error = context.getString(R.string.auth_err_mismatch); return@submitButton }
                    error = backend.restoreBackup(bytes, passphrase, password)
                }
                Text(stringResource(R.string.auth_restore_footnote), style = mono(10), color = t.fgDim.copy(alpha = t.fgDim.alpha * 0.8f))
            }
        }
    }
    if (confirmSlot) {
        TerminalAlert(
            title = stringResource(R.string.dlg_create_slot),
            message = stringResource(R.string.dlg_create_slot_msg),
            buttons = listOf(
                DialogButton(stringResource(R.string.btn_create_anyway), destructive = true) {
                    submit { error = backend.createAccount(username, password, relay = "") } // empty = built-in default
                },
                DialogButton(stringResource(R.string.btn_abort), cancel = true) {},
            ),
            onDismiss = { confirmSlot = false },
        )
    }

}
