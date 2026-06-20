package com.aiopenpocket.ui.onboarding.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.aiopenpocket.agent.core.persona.PersonaInfo
import com.aiopenpocket.ui.theme.PersonaCardBg
import com.aiopenpocket.ui.theme.PersonaQuoteBg
import com.aiopenpocket.ui.theme.PurpleAccent

@Composable
fun PersonaCard(
  persona: PersonaInfo,
  isSelected: Boolean,
  onSelect: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val shape = RoundedCornerShape(12.dp)
  val borderModifier = if (isSelected) {
    Modifier.border(1.5.dp, PurpleAccent, shape)
  } else {
    Modifier
  }

  Box(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 6.dp)
      .clip(shape)
      .then(borderModifier)
      .background(PersonaCardBg, shape)
      .clickable(onClick = onSelect)
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
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .padding(top = 12.dp)
          .clip(RoundedCornerShape(8.dp))
          .background(PersonaQuoteBg)
          .padding(horizontal = 12.dp, vertical = 10.dp),
      ) {
        Text(
          text = persona.exampleQuote,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }

    if (isSelected) {
      Box(
        modifier = Modifier
          .align(Alignment.TopEnd)
          .padding(12.dp)
          .clip(CircleShape)
          .background(PurpleAccent)
          .padding(4.dp),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = Icons.Default.Check,
          contentDescription = "Выбрано",
          tint = MaterialTheme.colorScheme.onPrimary,
          modifier = Modifier.padding(2.dp),
        )
      }
    }
  }
}
