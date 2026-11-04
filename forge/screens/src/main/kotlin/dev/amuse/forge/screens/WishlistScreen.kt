package dev.amuse.forge.screens

// @trace forge-host-capabilities-f648ae

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.amuse.forge.host.PhotoPicker
import dev.amuse.forge.host.loadInt
import dev.amuse.forge.host.loadText
import dev.amuse.forge.host.saveInt
import dev.amuse.forge.host.saveText
import dev.ketoy.annotations.KetoyComposable
import dev.ketoy.annotations.KetoyEntryPoint
import dev.ketoy.runtime.compose.themeColor

/**
 * The example of what the app lends a screen (dev.amuse.forge.host), in the app's palette: a savings
 * counter that survives closing the screen and the app (loadInt / saveInt, read once into state, saved
 * in the same click that changes it), wish names kept as text (loadText / saveText) and a photo slot per
 * wish drawn by the app (PhotoPicker: the gallery, the chosen photo kept under its key).
 * Also the debug build's bundled sample in the app.
 */
@KetoyEntryPoint
@KetoyComposable
@Composable
fun WishlistScreen() {
    var saved by remember { mutableIntStateOf(loadInt("saved", 0)) }
    var first by remember { mutableStateOf(loadText("wish1", "")) }
    var second by remember { mutableStateOf(loadText("wish2", "")) }
    Column(
        modifier = Modifier.fillMaxWidth().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(text = "Wishlist", fontSize = 24.sp, color = themeColor("text"))
        Column(
            modifier = Modifier.fillMaxWidth().background(themeColor("card"), RoundedCornerShape(20.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(text = "Saved so far", color = themeColor("textMuted"))
            Text(text = "$saved", fontSize = 28.sp, color = themeColor("accent"))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { saved += 1000; saveInt("saved", saved) }) {
                    Text(text = "+1000", color = themeColor("onAccent"))
                }
                Button(onClick = { saved += 5000; saveInt("saved", saved) }) {
                    Text(text = "+5000", color = themeColor("onAccent"))
                }
                Button(onClick = { saved = 0; saveInt("saved", 0) }) {
                    Text(text = "0", color = themeColor("onAccent"))
                }
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth().background(themeColor("card"), RoundedCornerShape(20.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(text = "First wish", color = themeColor("textMuted"))
            TextField(
                value = first,
                onValueChange = { first = it; saveText("wish1", it) },
                modifier = Modifier.fillMaxWidth(),
            )
            PhotoPicker(key = "wish1", label = "Add a photo")
        }
        Column(
            modifier = Modifier.fillMaxWidth().background(themeColor("card"), RoundedCornerShape(20.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(text = "Second wish", color = themeColor("textMuted"))
            TextField(
                value = second,
                onValueChange = { second = it; saveText("wish2", it) },
                modifier = Modifier.fillMaxWidth(),
            )
            PhotoPicker(key = "wish2", label = "Add a photo")
        }
    }
}
