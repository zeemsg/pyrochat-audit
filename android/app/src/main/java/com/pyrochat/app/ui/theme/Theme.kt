package com.pyrochat.app.ui.theme

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pyrochat.app.R
import com.pyrochat.app.ui.AppSettings

/** `Theme` in UI/Theme.swift — phosphor green (or amber) on near-black. */
object Palette {
    val bg = Color(0.02f, 0.03f, 0.02f)
    val panel = Color(0.045f, 0.06f, 0.045f)
    val ember = Color(0xFF40FF73)
    val amber = Color(0xFFFFB333)

    /**
     * Phosphor themes — one foreground colour each, every dim tone derived from it. The same
     * ten names and hex values as iOS `Theme.themes`; unknown names fall back to ember so two
     * builds never disagree on how to render a store.
     */
    val themes: List<Pair<String, Color>> = listOf(
        "ember" to ember,                    // the classic phosphor green
        "amber" to amber,                    // 1970s amber monitor
        "ice" to Color(0xFF7EE8FA),          // cold cyan
        "plasma" to Color(0xFFFF79C6),       // magenta
        "ultraviolet" to Color(0xFFBD93F9),  // violet
        "solar" to Color(0xFFF1FA8C),        // pale yellow
        "mint" to Color(0xFFA8FFC8),         // soft green-white
        "cobalt" to Color(0xFF5FA8FF),       // blue
        "paper" to Color(0xFFE6E6E6),        // white-on-black teletype
        "ghost" to Color(0xFF9AA5B1),        // dim grey-blue
    )
    val themeNames: List<String> get() = themes.map { it.first }
    fun forName(name: String): Color = themes.firstOrNull { it.first == name }?.second ?: ember
    val alert = Color(1.0f, 0.35f, 0.25f)
    /** candle-flame orange — identity tags `<name>`, the PYROCHAT wordmark */
    val flame = Color(1.0f, 0.69f, 0.20f)
}

/**
 * docs/TEXT-SIZE.md §1 — the five names in `[cfg]` order and their multipliers, plus the §2 clamp.
 * Pure and JVM-tested (`TextSizeTest`); identical to iOS `Theme.TextSize`.
 */
object TextSize {
    const val DEFAULT = "normal"

    /** `[cfg]` order (§1). */
    val names: List<String> = listOf("small", "normal", "large", "larger", "largest")

    private val multipliers: Map<String, Float> = mapOf(
        "small" to 0.85f, "normal" to 1.0f, "large" to 1.15f, "larger" to 1.3f, "largest" to 1.5f,
    )

    /** §2: the COMBINED scale — ours times the platform's — is never more than this. */
    const val MAX_COMBINED = 2.0f

    /** §3: fixed-width art scales, but no further than this name. */
    const val FIXED_WIDTH_CAP = "large"

    /** The stored word as it renders: unknown or missing is `normal`, the rule `theme` already uses. */
    fun name(stored: String?): String = names.firstOrNull { it == stored } ?: DEFAULT

    /** §1 — the multiplier for a stored word (unknown or missing ⇒ `normal` ⇒ 1.0). */
    fun multiplier(stored: String?): Float = multipliers.getValue(name(stored))

    /**
     * The multiplier actually handed to [mono]. It multiplies the platform's own scaling rather
     * than replacing it, so [systemFontScale] (`Density.fontScale`, what `sp` applies on top) is
     * only used to hold the product under [MAX_COMBINED]: a system setting already at 2× leaves
     * this at 1.0 and `largest` cannot push the layout past twice its designed size.
     */
    fun scale(stored: String?, systemFontScale: Float = 1f): Float {
        val system = if (systemFontScale.isFinite() && systemFontScale > 0f) systemFontScale else 1f
        return minOf(multiplier(stored), MAX_COMBINED / system)
    }

    /** §3 — the same scale for the boot candle and the safety number, stopped at [FIXED_WIDTH_CAP]. */
    fun fixedWidthScale(stored: String?, systemFontScale: Float = 1f): Float =
        scale(if (multiplier(stored) > multiplier(FIXED_WIDTH_CAP)) FIXED_WIDTH_CAP else name(stored), systemFontScale)
}

/** The per-settings colors and type scale every screen reads (`Theme.fg(settings)` / `Theme.fgDim(settings)`). */
@Immutable
data class Terminal(
    val fg: Color,
    val scanlines: Boolean,
    /** docs/TEXT-SIZE.md — `[cfg]` → `text size`, already clamped against the system scale ([TextSize.scale]). */
    val textScale: Float = 1f,
    /** §3 — the same, stopped at `large`, for the fixed-width art ([monoFixed]). */
    val fixedWidthScale: Float = 1f,
) {
    val fgDim: Color get() = fg.copy(alpha = 0.55f)
    val bg: Color get() = Palette.bg
    val panel: Color get() = Palette.panel
    val alert: Color get() = Palette.alert
    val flame: Color get() = Palette.flame
}

val LocalTerminal = staticCompositionLocalOf { Terminal(Palette.ember, true) }

/** IBM Plex Mono stands in for the system monospaced face (SF Mono can't ship). */
val PlexMono = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_mono_semibold, FontWeight.SemiBold),
    Font(R.font.ibm_plex_mono_semibold, FontWeight.Bold),
    Font(R.font.ibm_plex_mono_italic, FontWeight.Normal, FontStyle.Italic),
)

/**
 * `Theme.mono(size, weight)` — the point size is used as sp, which scales
 * with the system font size the way UIFontMetrics(.body) does on iOS.
 *
 * docs/TEXT-SIZE.md §2: the ONE place the `[cfg]` → `text size` multiplier is applied. It rides in
 * on [LocalTerminal], so every one of the ~180 call sites keeps its designed point size and the
 * whole app redraws the moment the setting changes — nothing scales at a call site.
 */
@Composable
@ReadOnlyComposable
fun mono(size: Int, weight: FontWeight = FontWeight.Normal): TextStyle =
    monoStyle(size, weight, LocalTerminal.current.textScale)

/**
 * docs/TEXT-SIZE.md §3 — for what assumes a physical width (the boot screen, the safety number,
 * the toolbar strip): scales like everything else, but stops at `large` so it never overflows the
 * narrowest phone.
 */
@Composable
@ReadOnlyComposable
fun monoFixed(size: Int, weight: FontWeight = FontWeight.Normal): TextStyle =
    monoStyle(size, weight, LocalTerminal.current.fixedWidthScale)

/** [mono] at an explicit scale — the one door for code that runs outside a composition. */
fun monoStyle(size: Int, weight: FontWeight = FontWeight.Normal, scale: Float = 1f): TextStyle {
    val points = size * scale
    return TextStyle(
        fontFamily = PlexMono,
        fontSize = points.sp,
        fontWeight = weight,
        lineHeight = (points * 1.28f).sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    )
}

/** Terminal chrome for the whole tree: dark Material scheme, mono default text, fg content color. */
@Composable
fun PyroTheme(settings: AppSettings, content: @Composable () -> Unit) {
    val fg = Palette.forName(settings.theme)
    // `sp` already multiplies by this; it is read here only so §2's ceiling can be enforced
    val systemFontScale = LocalDensity.current.fontScale
    val terminal = Terminal(
        fg = fg,
        scanlines = settings.scanlines,
        textScale = TextSize.scale(settings.textSize, systemFontScale),
        fixedWidthScale = TextSize.fixedWidthScale(settings.textSize, systemFontScale),
    )
    val scheme = darkColorScheme(
        primary = fg,
        onPrimary = Palette.bg,
        secondary = fg,
        background = Palette.bg,
        onBackground = fg,
        surface = Palette.bg,
        onSurface = fg,
        surfaceVariant = Palette.panel,
        onSurfaceVariant = terminal.fgDim,
        outline = terminal.fgDim,
        error = Palette.alert,
        surfaceContainer = Palette.panel,
        surfaceContainerHigh = Palette.panel,
        surfaceContainerLow = Palette.panel,
        surfaceContainerHighest = Palette.panel,
        surfaceContainerLowest = Palette.bg,
    )
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(
            LocalTerminal provides terminal,
            LocalContentColor provides fg,
            LocalTextStyle provides monoStyle(13, scale = terminal.textScale),
            LocalTextSelectionColors provides TextSelectionColors(handleColor = fg, backgroundColor = fg.copy(alpha = 0.3f)),
            content = content,
        )
    }
}

/**
 * `Scanlines` — 1-dp black rows at 16 % every 3 dp, drawn OVER the content
 * (no hit-testing impact since it is pure drawing).
 */
fun Modifier.scanlines(enabled: Boolean): Modifier = if (!enabled) this else drawWithContent {
    drawContent()
    val step = 3.dp.toPx()
    val h = 1.dp.toPx()
    var y = 0f
    while (y < size.height) {
        drawRect(Color.Black.copy(alpha = 0.16f), topLeft = Offset(0f, y), size = Size(size.width, h))
        y += step
    }
}

/**
 * Screens that iOS draws on the bare `Theme.bg` instead of `.terminalScreen` (AddByQRView, so the
 * contact QR stays cleanly scannable) hold this while shown — see [SuppressScanlines].
 */
class ScanlineGate {
    var suppressed by mutableStateOf(false)
}

val LocalScanlineGate = staticCompositionLocalOf { ScanlineGate() }

/** `.background(Theme.bg.ignoresSafeArea())` without scanlines for as long as the caller is composed. */
@Composable
fun SuppressScanlines() {
    val gate = LocalScanlineGate.current
    DisposableEffect(gate) {
        gate.suppressed = true
        onDispose { gate.suppressed = false }
    }
}

/** `.terminalScreen(settings)` — bg ground + scanlines overlay around [content]. */
@Composable
fun TerminalScreen(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val t = LocalTerminal.current
    val gate = remember { ScanlineGate() }
    Box(modifier.fillMaxSize().background(t.bg).scanlines(t.scanlines && !gate.suppressed)) {
        CompositionLocalProvider(LocalScanlineGate provides gate, content = content)
    }
}

/** `BlockCursor` — 9×17 block blinking every 0.55 s; it is a character, so it takes the type scale. */
@Composable
fun BlockCursor(color: Color = LocalTerminal.current.fg, modifier: Modifier = Modifier) {
    val scale = LocalTerminal.current.textScale
    val transition = rememberInfiniteTransition(label = "cursor")
    val on by transition.animateFloat(
        initialValue = 1f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(550), RepeatMode.Reverse), label = "blink",
    )
    Box(modifier.size((9 * scale).dp, (17 * scale).dp).alpha(on).background(color))
}
