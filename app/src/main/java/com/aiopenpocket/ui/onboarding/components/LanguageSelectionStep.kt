package com.aiopenpocket.ui.onboarding.components

// @trace ai-academy-onboarding-5d51bb

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.aiopenpocket.i18n.tr
import com.aiopenpocket.ui.components.glass.GlassCard
import com.aiopenpocket.ui.theme.GlassTokens
import com.aiopenpocket.ui.theme.MyApplicationTheme
import com.aiopenpocket.ui.theme.ThemeMode

/**
 * One selectable language. Only [enabled] languages can be picked. English is enabled: the demo path
 * (home, agents, builder, chat, Meta lessons) is translated, the rest of the app follows; the choice
 * switches the interface right away (see [com.aiopenpocket.i18n.AppLanguage]).
 */
private data class LanguageOption(
  val code: String,
  val flag: String,
  val title: String,
  val enabled: Boolean,
)

private val LANGUAGES = listOf(
  LanguageOption("ru", "🇷🇺", "Русский", enabled = true),
  LanguageOption("en", "🇬🇧", "English", enabled = true),
)

@Composable
fun LanguageSelectionStep(
  selectedLanguage: String,
  onSelectLanguage: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier
      .fillMaxWidth()
      .padding(24.dp),
  ) {
    Text(
      text = tr("Выбери язык", "Choose your language"),
      style = MaterialTheme.typography.headlineSmall,
    )
    Text(
      text = tr("Choose your language", "Выбери язык"),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
    )

    LANGUAGES.forEach { lang ->
      val isSelected = lang.code == selectedLanguage
      val shape = RoundedCornerShape(14.dp)
      // GlassCard's own blick border is enough for the resting look; the selected row layers a
      // 2dp primary accent border on top of it (same technique as PersonaCard).
      val selectedBorderModifier = if (isSelected) {
        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape)
      } else {
        Modifier
      }
      // Disabled languages ("Coming soon") stay inert — no onClick at all, same as the old
      // clickable(enabled = lang.enabled).
      val onLanguageClick: (() -> Unit)? = if (lang.enabled) {
        { onSelectLanguage(lang.code) }
      } else {
        null
      }
      GlassCard(
        onClick = onLanguageClick,
        shape = shape,
        tint = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else GlassTokens.LightTint,
        modifier = Modifier
          .fillMaxWidth()
          .padding(vertical = 6.dp)
          .then(selectedBorderModifier),
      ) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(text = lang.flag, style = MaterialTheme.typography.headlineSmall)
          Spacer(Modifier.size(12.dp))
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = lang.title,
              style = MaterialTheme.typography.titleMedium,
              fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
              color = if (lang.enabled) MaterialTheme.colorScheme.onSurface
              else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            )
            if (!lang.enabled) {
              Text(
                text = tr("Скоро", "Soon"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
              )
            }
          }
          when {
            isSelected -> Icon(
              Icons.Default.Check,
              contentDescription = tr("Выбрано", "Selected"),
              tint = MaterialTheme.colorScheme.primary,
            )
            !lang.enabled -> Icon(
              Icons.Default.Lock,
              contentDescription = "Недоступно",
              tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
              modifier = Modifier.size(18.dp),
            )
          }
        }
      }
    }
  }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun LanguageSelectionStepPreview() {
  MyApplicationTheme(mode = ThemeMode.DARK) {
    LanguageSelectionStep(selectedLanguage = "ru", onSelectLanguage = {})
  }
}
