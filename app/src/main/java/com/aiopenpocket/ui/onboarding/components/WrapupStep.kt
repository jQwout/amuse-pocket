package com.aiopenpocket.ui.onboarding.components

// @trace ai-academy-onboarding-5d51bb

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.aiopenpocket.agent.core.persona.PersonaCatalog
import com.aiopenpocket.ui.theme.MyApplicationTheme

/**
 * Closing onboarding step: explains, in a chat-like form, how the chat works —
 * what a prompt is and what the pre-prompt (persona + user profile) is.
 */
@Composable
fun WrapupStep(
  personaId: String,
  name: String,
  preferredAddress: String,
  modifier: Modifier = Modifier,
) {
  val personaName = PersonaCatalog.findById(personaId)?.displayName ?: "ассистент"
  val who = preferredAddress.ifBlank { name }.ifBlank { "тебя" }

  Column(
    modifier = modifier
      .fillMaxWidth()
      .verticalScroll(rememberScrollState())
      .padding(24.dp),
  ) {
    Text(
      text = "Как работает чат",
      style = MaterialTheme.typography.headlineSmall,
      // An explicit color, not the ambient LocalContentColor: without it the heading inherits near-black outside
      // the root Surface (including in the screenshot test) and drowns.
      color = MaterialTheme.colorScheme.onBackground,
    )
    Text(
      text = "Прежде чем начать — пара слов о том, что происходит «под капотом».",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
    )

    AssistantBubble(
      "Когда ты пишешь сообщение — это **промпт**. Именно он уходит модели, и на него она отвечает.",
    )
    AssistantBubble(
      "Но перед каждым твоим сообщением я незаметно получаю **препромпт** — скрытую инструкцию. Она и делает меня «мной».",
    )
    AssistantBubble(
      "Препромпт состоит из двух частей:\n\n" +
        "• **Личность ассистента** — ты выбрал «$personaName». От неё зависит мой тон.\n" +
        "• **Информация о тебе** — то, как обращаться к $who, и твой профиль. Поэтому я отвечаю лично тебе.",
    )
    AssistantBubble(
      "Промпт ты видишь, препромпт — нет. Но именно их сумма уходит модели каждый раз. Готов? Поехали 🚀",
    )
  }
}

@Composable
private fun AssistantBubble(text: String) {
  // A theme-dependent accent: lime on dark, olive on light — readable on both backgrounds.
  val accent = MaterialTheme.colorScheme.primary
  val shape = RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 16.dp)
  Box(
    modifier = Modifier
      .padding(bottom = 10.dp)
      .clip(shape)
      .background(MaterialTheme.colorScheme.surfaceVariant)
      // A thin lime edge separates the card from the aurora — without it dark on dark merges.
      .border(1.dp, accent.copy(alpha = 0.22f), shape)
      .padding(14.dp),
  ) {
    Text(
      text = renderChips(text, accent),
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.onSurface,
    )
  }
}

// Keywords (**...**) become lime chips: the accent color + a light fill of the same tone (a highlight).
private fun renderChips(raw: String, accent: Color) = androidx.compose.ui.text.buildAnnotatedString {
  var i = 0
  while (i < raw.length) {
    val start = raw.indexOf("**", i)
    if (start < 0) {
      append(raw.substring(i))
      break
    }
    append(raw.substring(i, start))
    val end = raw.indexOf("**", start + 2)
    if (end < 0) {
      append(raw.substring(start))
      break
    }
    pushStyle(
      androidx.compose.ui.text.SpanStyle(
        color = accent,
        background = accent.copy(alpha = 0.15f),
        fontWeight = FontWeight.Medium,
      ),
    )
    append(raw.substring(start + 2, end))
    pop()
    i = end + 2
  }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F, heightDp = 700)
@Composable
private fun WrapupStepPreview() {
  MyApplicationTheme {
    WrapupStep(personaId = "sharp_partner", name = "Саша", preferredAddress = "ты")
  }
}
