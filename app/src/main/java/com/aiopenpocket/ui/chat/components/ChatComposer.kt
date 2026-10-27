package com.aiopenpocket.ui.chat.components

// @trace streaming-llm-chat-6595a1
//
// The chat composer: the normal input row and the full-screen mode. All text handling goes through
// callback parameters; the state lives on the screen.

import android.content.pm.PackageManager
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.aiopenpocket.i18n.tr
import com.aiopenpocket.ui.components.glass.GlassState
import com.aiopenpocket.ui.components.glass.GlassSurface
import com.aiopenpocket.voice.input.VoiceInputState
import kotlinx.coroutines.launch

// internal: see MessageBubble — the preview frame needs it (including ChatInputStatesPreview).
@Composable
internal fun ChatInput(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    isLoading: Boolean,
    glassState: GlassState,
    placeholder: String = tr("Сообщение...", "Message..."),
    onExpand: () -> Unit,
    voiceState: VoiceInputState,
    onVoiceToggle: () -> Unit,
    onPermissionDenied: () -> Unit,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    var pendingStart by remember { mutableStateOf(false) }
    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted && pendingStart) onVoiceToggle()
        if (!granted) onPermissionDenied()
        pendingStart = false
    }

    fun handleMicTap() {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            onVoiceToggle()
        } else {
            pendingStart = true
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // One rounded frame: text on top, the control panel below (as in the mockup). Blur/tint/the highlight
    // border come from GlassSurface; the purple accent border stays on top as a second, inner one.
    // Floating styling like the navbar/header: side paddings + a soft shadow (the rim comes from GlassSurface).
    val inputShape = RoundedCornerShape(24.dp)
    GlassSurface(
        state = glassState,
        shape = inputShape,
        // The input field is opaque, unlike the rest of the chrome. Here showing through hurts not aesthetically
        // but functionally: through a translucent backing the feed's messages were readable and mixed with
        // the text being typed. Plus it is the only surface the user looks at and types into — it ought to
        // be a calm background, not a shop window.
        plateAlpha = 1f,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .shadow(16.dp, inputShape, clip = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                    shape = inputShape,
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 24.dp, max = 160.dp)
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                textStyle = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                maxLines = 6,
                decorationBox = { inner ->
                    if (value.isEmpty()) {
                        // The placeholder is tinted to the accent (like the purple frame) to stand apart from the text.
                        Text(placeholder, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
                    }
                    inner()
                },
            )
            Spacer(Modifier.height(6.dp))
            // The bottom panel inside the frame: expand and the microphone on the left, clear and send on the right.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onExpand, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Default.OpenInFull,
                        contentDescription = tr("Развернуть", "Expand"),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.width(4.dp))
                MicButton(state = voiceState, onClick = ::handleMicTap, modifier = Modifier.size(36.dp))
                Spacer(Modifier.weight(1f))
                // Clear the field — shown only when there is text.
                if (value.isNotBlank()) {
                    IconButton(onClick = { onValueChange("") }, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = tr("Очистить", "Clear"),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                }
                if (isLoading) {
                    // The progress around the "stop" button — lets the user cancel the turn.
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(44.dp)) {
                        IconButton(onClick = onCancel) {
                            Icon(Icons.Default.Stop, contentDescription = tr("Остановить", "Stop"))
                        }
                    }
                } else {
                    // A round button: filled with the accent when there is something to send; dimmed otherwise (as in the mockup).
                    val canSend = value.isNotBlank()
                    IconButton(
                        // Sending hides the keyboard: the field loses focus -> the IME hides.
                        onClick = { focusManager.clearFocus(); onSend() },
                        enabled = canSend,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Default.Send,
                            contentDescription = tr("Отправить", "Send"),
                            tint = if (canSend) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The full-screen typing mode (the "expand" icon in the inline input). It edits the same text as
 * [ChatInput]; "Collapse" hides the mode without losing what was typed, "Clear" empties it, "Send" sends
 * and collapses.
 */
@Composable
internal fun FullScreenComposer(
    value: String,
    onValueChange: (String) -> Unit,
    onClear: () -> Unit,
    onCollapse: () -> Unit,
    onSend: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // The composer is full screen on top of the Scaffold, so it accounts for the status bar, the navbar and
                // the keyboard itself. safeDrawingPadding = max(system bars, IME), without counting the navbar twice.
                .safeDrawingPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onCollapse) {
                    Icon(Icons.Default.CloseFullscreen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(tr("Свернуть", "Collapse"))
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClear, enabled = value.isNotEmpty()) {
                    Text(tr("Очистить", "Clear"))
                }
            }
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text(tr("Напишите сообщение...", "Write a message...")) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                ),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                Button(onClick = { focusManager.clearFocus(); onSend() }, enabled = value.isNotBlank()) {
                    Text(tr("Отправить", "Send"))
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.AutoMirrored.Default.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}
