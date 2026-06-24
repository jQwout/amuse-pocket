package com.aiopenpocket.ui.onboarding.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aiopenpocket.agent.core.persona.PersonaCatalog

@Composable
fun AboutMeStep(
  personaId: String,
  text: String,
  isExtracting: Boolean,
  extractionError: String?,
  onTextChange: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val persona = PersonaCatalog.findById(personaId)
  val askLine = personaAsk(personaId)

  Column(
    modifier = modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 20.dp, vertical = 16.dp),
  ) {
    Text(
      text = "Расскажи о себе",
      style = MaterialTheme.typography.headlineSmall,
    )
    Text(
      text = "Один свободный ответ — я разберу его и заполню профиль. " +
        "Имя, как обращаться, возраст, чем живёшь — что хочешь сказать.",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(top = 6.dp, bottom = 20.dp),
    )

    PersonaAskBubble(
      personaName = persona?.displayName ?: "Ассистент",
      line = askLine,
    )

    Spacer(modifier = Modifier.size(16.dp))

    OutlinedTextField(
      value = text,
      onValueChange = onTextChange,
      placeholder = { Text("Например: Я Саша, мне 27, разработчик, увлекаюсь альпинизмом. Обращайся на ты.") },
      modifier = Modifier
        .fillMaxWidth()
        .testTag("about_me_input"),
      minLines = 5,
      maxLines = 10,
      enabled = !isExtracting,
    )

    if (isExtracting) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 12.dp),
      ) {
        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.size(10.dp))
        Text(
          text = "Разбираю твой ответ…",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }

    if (extractionError != null) {
      Text(
        text = extractionError,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(top = 12.dp),
      )
    }

    Spacer(modifier = Modifier.height(16.dp))
  }
}

@Composable
private fun PersonaAskBubble(personaName: String, line: String) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 16.dp))
      .background(MaterialTheme.colorScheme.surfaceVariant)
      .padding(14.dp),
  ) {
    Text(
      text = personaName,
      style = MaterialTheme.typography.labelMedium,
      fontWeight = FontWeight.SemiBold,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Box(modifier = Modifier.padding(top = 4.dp)) {
      Text(
        text = line,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
      )
    }
  }
}

private fun personaAsk(personaId: String): String = when (personaId) {
  "silent_butler" -> "Слушаю."
  "attentive_secretary" ->
    "Расскажите о себе — это поможет мне быть точнее. Имя, как к вам обращаться, возраст, чем занимаетесь?"
  "sharp_partner" ->
    "Окей. Кто ты — имя, как обращаться, возраст, чем занят? Без воды."
  "egalitarian_partner" ->
    "Прежде чем начнём — расскажи о себе так, как тебе комфортно. Что мне знать о тебе?"
  "friendly_companion" ->
    "Расскажи мне о себе 🙂 Как зовут, как к тебе обращаться, чем живёшь — что хочется."
  "ironic_assistant" ->
    "Анкета без анкеты. Имя, обращение, возраст, увлечения — в свободной форме."
  "strict_coach" ->
    "Имя. Возраст. Чем занимаешься. Поехали."
  else -> "Расскажи о себе — имя, как обращаться, возраст и чем живёшь."
}
