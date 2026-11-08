package com.pyrochat.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.contentDataType
import androidx.compose.ui.autofill.ContentDataType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pyrochat.app.ui.theme.LocalTerminal
import com.pyrochat.app.ui.theme.mono

/** A bare text button the way the iOS terminal glyph buttons render (`[cfg]`, `[TX]`…). */
@Composable
fun Glyph(
    text: String,
    size: Int,
    modifier: Modifier = Modifier,
    weight: FontWeight = FontWeight.Normal,
    color: Color = LocalTerminal.current.fg,
    enabled: Boolean = true,
    /** docs/TEXT-SIZE.md: keep a bracket label on one line — a `]` orphaned on a second reads as a bug. */
    singleLine: Boolean = false,
    label: String? = null,
    onClick: () -> Unit,
) {
    Text(
        text,
        style = mono(size, weight),
        color = color,
        maxLines = if (singleLine) 1 else Int.MAX_VALUE,
        softWrap = !singleLine,
        modifier = modifier
            .clickable(enabled = enabled, interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier)
            .padding(horizontal = 4.dp, vertical = 6.dp),
    )
}

/** AuthView's boxed buttons: `[ SIGN IN ]` — 1-pt stroke, padding v10 h14. */
@Composable
fun BoxedButton(
    text: String,
    modifier: Modifier = Modifier,
    size: Int = 14,
    color: Color = LocalTerminal.current.fg,
    stroke: Color = color,
    vertical: Dp = 10.dp,
    horizontal: Dp = 14.dp,
    enabled: Boolean = true,
    label: String? = null,
    onClick: () -> Unit,
) {
    Text(
        text,
        style = mono(size, FontWeight.Bold),
        color = color,
        modifier = modifier
            .clickable(enabled = enabled, interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier)
            .border(1.dp, stroke)
            .padding(vertical = vertical, horizontal = horizontal),
    )
}

/** AuthView `field(_:text:)` / `secure(_:text:)`: label + field on a panel strip, padding 8. */
@Composable
fun LabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    secure: Boolean = false,
    size: Int = 13,
    placeholder: String = "",
    imeAction: ImeAction = ImeAction.Next,
    onDone: (() -> Unit)? = null,
) {
    val t = LocalTerminal.current
    Row(
        modifier.fillMaxWidth().background(t.panel).padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = mono(size))
        TerminalTextField(
            value, onValueChange,
            Modifier.weight(1f),
            size = size, secure = secure, placeholder = placeholder, imeAction = imeAction, onDone = onDone,
        )
    }
}

/** Bare mono text input, no autocapitalization / autocorrect, terminal cursor. */
@Composable
fun TerminalTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    size: Int = 13,
    secure: Boolean = false,
    placeholder: String = "",
    singleLine: Boolean = true,
    maxLines: Int = 1,
    capitalize: Boolean = false,
    imeAction: ImeAction = ImeAction.Default,
    onDone: (() -> Unit)? = null,
    contentDescription: String? = null,
) {
    val t = LocalTerminal.current
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            // Autofill: EVERY field in this app is excluded, not just the secret ones.
            //
            // Compose hosts each field as a virtual view and decides it is autofillable from its
            // semantics, so `View.importantForAutofill` on the host does nothing — the framework's
            // virtual-view path never consults it. `ContentDataType.None` is the lever that does.
            //
            // Without it the framework was handed each keystroke (`notifyValueChanged`) and a
            // `commit()` on submit, which is exactly the cue a provider uses to offer "save
            // password?". Google Password Manager accepting that would put a cloud copy of the one
            // secret protecting this device somewhere that survives every wipe path in
            // docs/SCRUB-AND-WIPE.md, the duress PIN included, and would make "the password never
            // leaves your device" false.
            //
            // The username is excluded too: on its own it is not a secret, but a provider pairs it
            // with the password field to make the credential it offers to save.
            .semantics { contentDataType = ContentDataType.Companion.None }
            .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier),
        textStyle = mono(size).copy(color = t.fg),
        cursorBrush = SolidColor(t.fg),
        singleLine = singleLine,
        maxLines = maxLines,
        visualTransformation = if (secure) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            capitalization = if (capitalize) KeyboardCapitalization.Characters else KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = if (secure) KeyboardType.Password else KeyboardType.Text,
            imeAction = imeAction,
        ),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }, onGo = { onDone?.invoke() }),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    Text(placeholder, style = mono(size), color = t.fgDim.copy(alpha = 0.4f))
                }
                inner()
            }
        },
    )
}

/** iOS navigation bar stand-in: leading / principal / trailing slots on the bg ground. */
@Composable
fun TerminalTopBar(
    leading: @Composable RowScope.() -> Unit = {},
    title: @Composable () -> Unit = {},
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val t = LocalTerminal.current
    // docs/TEXT-SIZE.md §3, the toolbar: one 44-dp line holding a back button, a title and up to
    // five bracket labels (`[cfg] [txt]` … `[share] [qr] [+]`, or a chat's `[/] [block] [burn]
    // [ttl]`). Same treatment as the art — it scales, capped at `large`; past that the last label
    // wraps its `]` onto a second line and the title is squeezed out of the row altogether.
    CompositionLocalProvider(LocalTerminal provides t.copy(textScale = t.fixedWidthScale)) {
        // UINavigationBar centers the principal item in the space left between the
        // button groups, so a wide trailing group never overlaps the title.
        Row(Modifier.fillMaxWidth().height(44.dp).background(t.bg).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) { leading() }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { title() }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) { trailing() }
        }
    }
}

/** The system back chevron + previous title, as SwiftUI draws it (tinted fg). */
@Composable
fun BackButton(previousTitle: String, onBack: () -> Unit) {
    Glyph("‹ $previousTitle", 13, color = LocalTerminal.current.fg, label = "Back", onClick = onBack)
}

/** `Divider().background(Theme.fgDim)` */
@Composable
fun TerminalDivider(alpha: Float = 1f, modifier: Modifier = Modifier) {
    HorizontalDivider(modifier, thickness = 1.dp, color = LocalTerminal.current.fgDim.copy(alpha = 0.55f * alpha))
}

// ---------------------------------------------------------------------------
// Dialogs — SwiftUI `.alert` (centered card) and `.confirmationDialog`
// (bottom action sheet) with the same titles, messages and button words.
// ---------------------------------------------------------------------------

data class DialogButton(
    val text: String,
    val destructive: Boolean = false,
    val cancel: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * `.alert(title, message, fields, buttons)` — 270-dp card, title bold,
 * message dim, optional text fields, buttons side-by-side when two (cancel
 * left) and stacked otherwise. Tapping outside is NOT a dismiss (iOS alerts
 * only close via a button).
 */
@Composable
fun TerminalAlert(
    title: String,
    message: String?,
    buttons: List<DialogButton>,
    onDismiss: () -> Unit,
    fields: @Composable ColumnScopeFields.() -> Unit = {},
) {
    val t = LocalTerminal.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Column(
            Modifier.width(290.dp).background(t.panel).border(1.dp, t.fgDim.copy(alpha = 0.5f)),
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, style = mono(15, FontWeight.Bold), textAlign = TextAlign.Center)
                if (message != null) Text(message, style = mono(12), color = t.fgDim, textAlign = TextAlign.Center)
                ColumnScopeFields.fields()
            }
            TerminalDivider(0.6f)
            val ordered = buttons.sortedBy { if (it.cancel) 0 else 1 }
            if (ordered.size == 2) {
                // each cell fills the row's height so the labels centre on one line
                // (a weight-only Box wraps its text and the Row pins it to the top)
                Row(Modifier.fillMaxWidth().height(46.dp), verticalAlignment = Alignment.CenterVertically) {
                    AlertButton(ordered[0], Modifier.weight(1f).fillMaxHeight()) { onDismiss() }
                    Box(Modifier.width(1.dp).fillMaxHeight().background(t.fgDim.copy(alpha = 0.33f)))
                    AlertButton(ordered[1], Modifier.weight(1f).fillMaxHeight()) { onDismiss() }
                }
            } else {
                ordered.reversed().forEachIndexed { i, b ->
                    if (i > 0) TerminalDivider(0.6f)
                    AlertButton(b, Modifier.fillMaxWidth().height(46.dp)) { onDismiss() }
                }
            }
        }
    }
}

/** Marker receiver so callers write fields with `LabeledField` etc. inside an alert. */
object ColumnScopeFields

@Composable
private fun AlertButton(b: DialogButton, modifier: Modifier, after: () -> Unit) {
    val t = LocalTerminal.current
    Box(
        modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { b.onClick(); after() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            b.text,
            style = mono(14, if (b.cancel) FontWeight.Bold else FontWeight.Normal),
            color = if (b.destructive) t.alert else t.fg,
        )
    }
}

/** Alert-style text field on the panel (placeholder = the iOS field prompt). */
@Composable
fun AlertField(value: String, onValueChange: (String) -> Unit, placeholder: String, secure: Boolean = false, capitalize: Boolean = false) {
    val t = LocalTerminal.current
    Box(Modifier.fillMaxWidth().background(t.bg).border(1.dp, t.fgDim.copy(alpha = 0.4f)).padding(8.dp)) {
        TerminalTextField(value, onValueChange, Modifier.fillMaxWidth(), size = 13, secure = secure, placeholder = placeholder, capitalize = capitalize)
    }
}

/**
 * `.confirmationDialog(title, titleVisibility: .visible)` — bottom sheet:
 * title + message dim centered, stacked full-width actions, cancel in its
 * own group below.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun TerminalConfirmSheet(
    title: String,
    message: String?,
    buttons: List<DialogButton>,
    onDismiss: () -> Unit,
) {
    val t = LocalTerminal.current
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = t.bg,
        contentColor = t.fg,
        dragHandle = null,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.fillMaxWidth().background(t.panel)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(title, style = mono(13, FontWeight.Bold), color = t.fgDim, textAlign = TextAlign.Center)
                    if (message != null) Text(message, style = mono(12), color = t.fgDim, textAlign = TextAlign.Center)
                }
                buttons.filterNot { it.cancel }.forEach { b ->
                    TerminalDivider(0.6f)
                    SheetButton(b) { onDismiss() }
                }
            }
            buttons.filter { it.cancel }.forEach { b ->
                Column(Modifier.fillMaxWidth().background(t.panel)) { SheetButton(b) { onDismiss() } }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SheetButton(b: DialogButton, after: () -> Unit) {
    val t = LocalTerminal.current
    Box(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { b.onClick(); after() },
        contentAlignment = Alignment.Center,
    ) {
        Text(b.text, style = mono(15, if (b.cancel) FontWeight.Bold else FontWeight.Normal), color = if (b.destructive) t.alert else t.fg)
    }
}

/** A dim mono "menu" item row for DropdownMenu content (`[ttl]`, context menus, pickers). */
@Composable
fun MenuRow(text: String, checked: Boolean = false, destructive: Boolean = false, onClick: () -> Unit) {
    val t = LocalTerminal.current
    Row(
        Modifier.fillMaxWidth().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(PaddingValues(horizontal = 14.dp, vertical = 10.dp)),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = mono(13), color = if (destructive) t.alert else t.fg)
        if (checked) Text("✓", style = mono(13, FontWeight.Bold), modifier = Modifier.padding(start = 16.dp))
    }
}
