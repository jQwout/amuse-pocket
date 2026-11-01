package com.aiopenpocket.ui.settings

// @trace voice-assistant-mode-a81a99
//
// The "Voice assistant" section of the settings screen: whether the app is the phone's assistant (with a
// way to the system screen where that is chosen) and whether replies to spoken questions are read aloud.

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aiopenpocket.i18n.tr
import com.aiopenpocket.ui.components.glass.glassCardSurface
import com.aiopenpocket.voice.isDefaultAssistant
import com.aiopenpocket.voice.openAssistantSettings

@Composable
internal fun VoiceAssistantSection(
  speakReplies: Boolean,
  onSpeakRepliesChange: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current

  // The choice is made on a system screen, so the status is read again on every return (ON_RESUME).
  var refreshTick by remember { mutableIntStateOf(0) }
  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) refreshTick++
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }
  val isAssistant = remember(refreshTick) { isDefaultAssistant(context) }

  Column(
    modifier = modifier,
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    IntegrationRow(
      icon = Icons.Default.Mic,
      title = tr("Помощник телефона", "Phone assistant"),
      subtitle = if (isAssistant) {
        tr(
          "Кнопка помощника открывает чат, который сразу слушает.",
          "The assistant button opens a chat that is already listening.",
        )
      } else {
        tr(
          "Выбери AI Open Pocket в системном списке помощников: само приложение этого сделать не может.",
          "Pick AI Open Pocket in the system list of assistants: the app can't do it by itself.",
        )
      },
      granted = isAssistant,
      grantedLabel = tr("Включено", "On"),
      requestLabel = tr("Выбрать", "Choose"),
      onRequest = { openAssistantSettings(context) },
      modifier = Modifier.testTag("settings_voice_assistant"),
    )
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .glassCardSurface(SettingsCardShape)
        .padding(horizontal = 16.dp, vertical = 14.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
        Text(
          text = tr("Читать ответы вслух", "Read replies aloud"),
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
          text = tr(
            "Когда спрашиваешь голосом. Читает синтезатор речи телефона: у Meta API нет озвучки.",
            "When you ask by voice. The phone's speech engine reads it: the Meta API has no speech output.",
          ),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Switch(
        checked = speakReplies,
        onCheckedChange = onSpeakRepliesChange,
        modifier = Modifier.testTag("settings_speak_replies"),
      )
    }
  }
}
