package com.pyrochat.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pyrochat.app.R
import com.pyrochat.app.platform.VoiceChanger
import com.pyrochat.app.platform.VoiceEffect
import com.pyrochat.app.ui.theme.LocalTerminal
import com.pyrochat.app.ui.theme.mono

/**
 * docs/VOICE-CHANGER.md: the voice a note will go out in, shown above the composer while one is
 * being recorded:
 *
 *     voice [OFF] [DEEP] [HIGH] [ROBOT]
 *
 * The one in use ([chosen]) is drawn bold in the foreground colour, the others dim. A tap chooses
 * ([onChoose]); the caller keeps the choice, so whoever relies on a changed voice does not have
 * to remember it for every note. iOS `VoicePicker`.
 */
@Composable
fun VoiceRow(chosen: VoiceEffect, onChoose: (VoiceEffect) -> Unit) {
    val t = LocalTerminal.current
    Row(
        Modifier.fillMaxWidth().background(t.panel).padding(horizontal = 12.dp).padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("voice", style = mono(11), color = t.fgDim)
        for (voice in VoiceEffect.entries) {
            val inUse = voice == chosen
            Glyph(
                VoiceChanger.label(voice), 11, weight = if (inUse) FontWeight.Bold else FontWeight.Normal, singleLine = true,
                color = if (inUse) t.fg else t.fgDim,
                label = (if (voice == VoiceEffect.OFF) "Your own voice" else "${voice.wire} voice") + if (inUse) ", selected" else "",
            ) { onChoose(voice) }
        }
    }
}

/**
 * What the composer says between `[SEND]` and the note's row, while the note is being changed
 * into its voice. A short note is changed before this is seen; a long one takes a moment. iOS
 * `VoiceBeingChanged`.
 */
@Composable
fun VoiceBeingChanged() {
    val t = LocalTerminal.current
    Text(
        stringResource(R.string.chat_voice_changing), style = mono(11), color = t.fgDim,
        modifier = Modifier.fillMaxWidth().background(t.panel).padding(horizontal = 12.dp).padding(top = 8.dp)
            .semantics { contentDescription = "Changing the voice of your note" },
    )
}
