package com.aiopenpocket.ui.onboarding.components

// @trace ai-academy-onboarding-5d51bb

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.aiopenpocket.agent.core.persona.PersonaCatalog
import com.aiopenpocket.ui.theme.GlassTokens
import com.aiopenpocket.ui.theme.MyApplicationTheme

@Composable
fun AboutMeStep(
  personaId: String,
  text: String,
  isExtracting: Boolean,
  extractionError: String?,
  onTextChange: (String) -> Unit,
  modifier: Modifier = Modifier,
  /**
   * Отступы под плавающие стеклянные острова онбординга (высоты меряются в [OnboardingContent]).
   * Применяются ПОСЛЕ [verticalScroll] — тогда контент прокручивается ПОД стеклом и его есть чему
   * размывать. Если инсетить вьюпорт снаружи (через [modifier]), полотно под острова не заезжает
   * и блюр сэмплит одну аврору — плоская плашка (см. ChatScreen.kt:396-400 и спеку
   * docs/superpowers/specs/2026-07-28-onboarding-liquid-glass-design.md).
   */
  contentPadding: PaddingValues = PaddingValues(),
) {
  val persona = PersonaCatalog.findById(personaId)
  val askLine = personaAsk(personaId)

  Column(
    modifier = modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(contentPadding)
      .padding(horizontal = 20.dp, vertical = 16.dp),
  ) {
    Text(
      text = "Расскажи о себе",
      style = MaterialTheme.typography.headlineSmall,
      // Явный цвет, а не ambient LocalContentColor: без него заголовок наследует near-black
      // вне корневого Surface (в т.ч. в скриншот-тесте) и тонет.
      color = MaterialTheme.colorScheme.onBackground,
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

    // Стеклянный вид: свой clip/tint/blick-бордер на обёртке, а у самого поля контейнер и
    // рамка сделаны прозрачными (colors ниже) — иначе под стеклом просвечивал бы ещё и
    // непрозрачный дефолтный outline M3.
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .clip(GlassTokens.CardShape)
        .background(GlassTokens.LightTint)
        .border(1.dp, GlassTokens.BorderBrush, GlassTokens.CardShape),
    ) {
      OutlinedTextField(
        value = text,
        // Потолок на вставку огромного текста — иначе весь кусок уходит одним user-сообщением в LLM
        // при извлечении профиля. Обрезаем, а не отбрасываем целиком: иначе вставка текста длиннее
        // лимита молча не давала бы вообще никакого эффекта.
        onValueChange = { onTextChange(it.take(ABOUT_ME_MAX_LENGTH)) },
        placeholder = { Text("Например: Я Саша, мне 27, разработчик, увлекаюсь альпинизмом. Обращайся на ты.") },
        modifier = Modifier
          .fillMaxWidth()
          .testTag("about_me_input"),
        minLines = 5,
        maxLines = 10,
        enabled = !isExtracting,
        colors = OutlinedTextFieldDefaults.colors(
          focusedContainerColor = Color.Transparent,
          unfocusedContainerColor = Color.Transparent,
          disabledContainerColor = Color.Transparent,
          focusedBorderColor = Color.Transparent,
          unfocusedBorderColor = Color.Transparent,
          disabledBorderColor = Color.Transparent,
        ),
      )
    }

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

private const val ABOUT_ME_MAX_LENGTH = 2000

@Composable
private fun PersonaAskBubble(personaName: String, line: String) {
  // Тема-зависимый акцент: лайм на тёмной, оливка на светлой — читается на обеих подложках.
  val accent = MaterialTheme.colorScheme.primary
  val shape = RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 16.dp)
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(shape)
      .background(MaterialTheme.colorScheme.surfaceVariant)
      // Тонкая лаймовая грань отделяет карточку от ауроры — без неё тёмное на тёмном сливается.
      .border(1.dp, accent.copy(alpha = 0.22f), shape)
      .padding(14.dp),
  ) {
    Text(
      text = personaName,
      style = MaterialTheme.typography.labelMedium,
      fontWeight = FontWeight.SemiBold,
      color = accent,
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

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun AboutMeStepPreview() {
  MyApplicationTheme {
    AboutMeStep(
      personaId = "sharp_partner",
      text = "Я Саша, 27, разработчик. На «ты».",
      isExtracting = false,
      extractionError = null,
      onTextChange = {},
    )
  }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun AboutMeStepExtractingPreview() {
  MyApplicationTheme {
    AboutMeStep(
      personaId = "sharp_partner",
      text = "Саша, 27.",
      isExtracting = true,
      extractionError = null,
      onTextChange = {},
    )
  }
}
