package com.aiopenpocket.ui.onboarding.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.aiopenpocket.ui.theme.MyApplicationTheme

/** One selectable language. Only [enabled] languages can be picked (RU only for now). */
private data class LanguageOption(
  val code: String,
  val flag: String,
  val title: String,
  val enabled: Boolean,
)

private val LANGUAGES = listOf(
  LanguageOption("ru", "🇷🇺", "Русский", enabled = true),
  LanguageOption("en", "🇬🇧", "English", enabled = false),
  LanguageOption("de", "🇩🇪", "Deutsch", enabled = false),
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
      text = "Выбери язык",
      style = MaterialTheme.typography.headlineSmall,
    )
    Text(
      text = "Choose your language · Sprache wählen",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
    )

    LANGUAGES.forEach { lang ->
      val isSelected = lang.code == selectedLanguage
      val border = if (isSelected) {
        BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
      } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
      }
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(vertical = 6.dp)
          .clip(RoundedCornerShape(14.dp))
          .border(border, RoundedCornerShape(14.dp))
          .background(
            if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
            else MaterialTheme.colorScheme.surface,
          )
          .clickable(enabled = lang.enabled) { onSelectLanguage(lang.code) }
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
              text = "Скоро",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
          }
        }
        when {
          isSelected -> Icon(
            Icons.Default.Check,
            contentDescription = "Выбрано",
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

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun LanguageSelectionStepPreview() {
  MyApplicationTheme {
    LanguageSelectionStep(selectedLanguage = "ru", onSelectLanguage = {})
  }
}
