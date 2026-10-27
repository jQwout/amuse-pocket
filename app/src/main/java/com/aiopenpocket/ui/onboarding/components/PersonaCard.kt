package com.aiopenpocket.ui.onboarding.components

// @trace swappable-persona-e5e65e

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import com.aiopenpocket.agent.core.persona.PersonaInfo
import com.aiopenpocket.agent.core.persona.PersonaQuote
import com.aiopenpocket.i18n.tr
import com.aiopenpocket.agent.core.persona.PersonaStats
import com.aiopenpocket.ui.components.glass.GlassCard
import com.aiopenpocket.ui.theme.MyApplicationTheme
import com.aiopenpocket.ui.theme.ThemeMode

@Composable
fun PersonaCard(
  persona: PersonaInfo,
  isSelected: Boolean,
  onSelect: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val shape = RoundedCornerShape(12.dp)
  // GlassCard's own blick border carries the resting look; the selected card layers a 1.5dp
  // accent border on top of it (same technique as LanguageSelectionStep).
  val borderModifier = if (isSelected) {
    Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, shape)
  } else {
    Modifier
  }

  Box(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 6.dp),
  ) {
    GlassCard(
      onClick = onSelect,
      shape = shape,
      modifier = Modifier
        .fillMaxWidth()
        .then(borderModifier)
        .testTag("persona_card_${persona.id}"),
    ) {
      Column(modifier = Modifier.padding(16.dp)) {
        Text(
          text = persona.displayName,
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
          text = persona.tone,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 4.dp),
        )

        Spacer(modifier = Modifier.height(14.dp))
        StatsBlock(persona.stats)

        Spacer(modifier = Modifier.height(14.dp))
        PerksRow(persona.perks)

        Spacer(modifier = Modifier.height(12.dp))
        QuotesBlock(persona.quotes)
      }
    }

    if (isSelected) {
      Box(
        modifier = Modifier
          .align(Alignment.TopEnd)
          .padding(12.dp)
          .clip(CircleShape)
          .background(MaterialTheme.colorScheme.primary)
          .padding(4.dp),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = Icons.Default.Check,
          contentDescription = tr("Выбрано", "Selected"),
          tint = MaterialTheme.colorScheme.onPrimary,
          modifier = Modifier.padding(2.dp),
        )
      }
    }
  }
}

@Composable
private fun StatsBlock(stats: PersonaStats) {
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    StatRow(tr("Болтливость", "Talkativeness"), stats.talkativeness)
    StatRow(tr("Скептицизм", "Skepticism"), stats.skepticism)
    StatRow(tr("Критика", "Criticism"), stats.criticism)
    StatRow(tr("Проактивность", "Proactivity"), stats.proactivity)
    StatRow(tr("Юмор", "Humor"), stats.humor)
    StatRow(tr("Эмпатия", "Empathy"), stats.empathy)
  }
}

@Composable
private fun StatRow(label: String, value: Int) {
  val clamped = value.coerceIn(0, 99)
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.width(110.dp),
    )
    Box(
      modifier = Modifier
        .weight(1f)
        .height(6.dp)
        .clip(RoundedCornerShape(3.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
      Box(
        modifier = Modifier
          .fillMaxWidth(clamped / 99f)
          .height(6.dp)
          .clip(RoundedCornerShape(3.dp))
          .background(statColor(clamped)),
      )
    }
    Spacer(modifier = Modifier.width(8.dp))
    Text(
      text = clamped.toString(),
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurface,
      fontWeight = FontWeight.SemiBold,
      modifier = Modifier.width(24.dp),
    )
  }
}

private fun statColor(value: Int): Color = when {
  value >= 75 -> Color(0xFF5DD39E)
  value >= 50 -> Color(0xFFE6C36A)
  value >= 25 -> Color(0xFFE69A6A)
  else -> Color(0xFFB76A6A)
}

@Composable
private fun PerksRow(perks: List<String>) {
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    perks.forEach { perk ->
      Box(
        modifier = Modifier
          .clip(RoundedCornerShape(6.dp))
          .background(MaterialTheme.colorScheme.surfaceVariant)
          .border(0.5.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
          .padding(horizontal = 10.dp, vertical = 5.dp),
      ) {
        Text(
          text = perk,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurface,
        )
      }
    }
  }
}

@Composable
private fun QuotesBlock(quotes: List<PersonaQuote>) {
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    quotes.forEach { quote -> QuoteRow(quote) }
  }
}

@Composable
private fun QuoteRow(quote: PersonaQuote) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(8.dp))
      .background(MaterialTheme.colorScheme.surfaceVariant)
      .padding(horizontal = 12.dp, vertical = 8.dp),
  ) {
    Text(
      text = "${tr("Ты", "You")}: ${quote.situation}",
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.size(2.dp))
    Text(
      text = quote.line,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurface,
    )
  }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F, heightDp = 540)
@Composable
private fun PersonaCardPreview() {
  MyApplicationTheme(mode = ThemeMode.DARK) {
    PersonaCard(persona = PersonaCatalog.all.first(), isSelected = true, onSelect = {})
  }
}
