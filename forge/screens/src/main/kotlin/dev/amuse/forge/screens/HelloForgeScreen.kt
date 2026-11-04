package dev.amuse.forge.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ketoy.annotations.KetoyComposable
import dev.ketoy.annotations.KetoyEntryPoint
import dev.ketoy.runtime.compose.themeColor

/**
 * The template screen. It is what the app ships as its debug sample (`assets/ketoy/hello.ktx`)
 * and what the coding agent reads before writing a screen of its own: one `@KetoyEntryPoint`
 * function, local state through `remember`, Material 3 widgets from the Ketoy catalog, nothing
 * from `android.*`. Every `Text` carries an explicit `color`: Ketoy 0.4.20 encodes the default
 * (`Color.Unspecified`) as transparent, so a text without one is laid out but never drawn. The colors
 * are the app's, by name (`themeColor("text")`), so the screen reads in the light and the dark theme.
 */
@KetoyEntryPoint
@KetoyComposable
@Composable
fun HelloForgeScreen() {
    var taps by remember { mutableIntStateOf(0) }
    Column(
        modifier = Modifier.fillMaxWidth().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "Forged in the cloud", fontSize = 22.sp, color = themeColor("text"))
        Text(text = "This screen arrived as a Ketoy bundle, not as an app update.", color = themeColor("textMuted"))
        Spacer(modifier = Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { taps += 1 }) {
                Text(text = "Tap me", color = themeColor("onAccent"))
            }
            Button(onClick = { taps = 0 }) {
                Text(text = "Reset", color = themeColor("onAccent"))
            }
        }
        Text(text = if (taps == 0) "No taps yet" else "Taps: $taps", color = themeColor("accent"))
    }
}
