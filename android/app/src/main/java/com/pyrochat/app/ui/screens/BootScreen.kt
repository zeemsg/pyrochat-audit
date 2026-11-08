package com.pyrochat.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.unit.dp
import com.pyrochat.app.R
import com.pyrochat.app.ui.theme.monoFixed
import kotlinx.coroutines.delay

/** `BootView` — ASCII candle, then the fake POST lines every 110 ms, 450 ms hold, hand-off. */
@Composable
fun BootScreen(onDone: () -> Unit) {
    val lines = stringArrayResource(R.array.boot_lines)
    var shown by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        for (i in lines.indices) {
            delay(110)
            shown = i + 1
        }
        delay(450)
        onDone()
    }

    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        // docs/TEXT-SIZE.md §3: fixed-width art — scales, but stops at `large`. The POST lines are
        // part of it: their dot leaders (`init secure enclave .......... OK`) are one 34-column
        // block with the candle, and past `large` they wrap mid-leader on a narrow phone.
        Text(CANDLE, style = monoFixed(13))
        for (i in 0 until shown) Text(lines[i], style = monoFixed(13))
    }
}

/** The 7-line candle from BootView.swift (leading spaces are part of the art). */
private const val CANDLE = "    )\n   (\n  .-.\n  | |\n  | |\n__|_|__\n'-----'"
