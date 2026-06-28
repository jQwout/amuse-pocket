package com.aiopenpocket.ui.onboarding.components

// @trace swappable-persona-e5e65e

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.aiopenpocket.agent.core.persona.PersonaCatalog
import com.aiopenpocket.ui.theme.MyApplicationTheme
import com.aiopenpocket.ui.theme.PurpleAccent
import com.aiopenpocket.ui.theme.PurpleAccentDark

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonaSelectionStep(
  selectedPersonaId: String,
  isSaving: Boolean,
  error: String?,
  onSelectPersona: (String) -> Unit,
  onBack: () -> Unit,
  onApply: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Scaffold(
    modifier = modifier,
    containerColor = MaterialTheme.colorScheme.background,
    topBar = {
      TopAppBar(
        title = {
          Text(
            text = "AI Academy",
            color = PurpleAccent,
            style = MaterialTheme.typography.titleLarge,
          )
        },
        navigationIcon = {
          IconButton(
            onClick = onBack,
            modifier = Modifier.testTag("persona_back"),
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = "Назад",
            )
          }
        },
        actions = {
          IconButton(onClick = { }) {
            Icon(
              imageVector = Icons.Default.Settings,
              contentDescription = "Настройки",
            )
          }
        },
        colors = TopAppBarDefaults.topAppBarColors(
          containerColor = MaterialTheme.colorScheme.background,
        ),
      )
    },
    bottomBar = {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 16.dp),
      ) {
        if (error != null) {
          Text(
            text = error,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(bottom = 8.dp),
          )
        }
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          TextButton(
            onClick = onBack,
            modifier = Modifier.testTag("onboarding_back"),
          ) {
            Text("Назад")
          }
          Button(
            onClick = onApply,
            enabled = selectedPersonaId.isNotBlank() && !isSaving,
            shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(
              containerColor = PurpleAccent,
              contentColor = PurpleAccentDark,
            ),
            modifier = Modifier.testTag("onboarding_next"),
          ) {
            if (isSaving) {
              CircularProgressIndicator(
                modifier = Modifier.padding(end = 8.dp),
                strokeWidth = 2.dp,
              )
            }
            Text("Применить")
          }
        }
      }
    },
  ) { padding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(padding),
    ) {
      Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
          text = "Выберите характер помощника",
          style = MaterialTheme.typography.headlineSmall,
        )
        Text(
          text = "Настройте стиль общения вашего ИИ для комфортного обучения.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 8.dp),
        )
      }
      LazyColumn(modifier = Modifier.weight(1f)) {
        items(PersonaCatalog.all, key = { it.id }) { persona ->
          PersonaCard(
            persona = persona,
            isSelected = persona.id == selectedPersonaId,
            onSelect = { onSelectPersona(persona.id) },
          )
        }
      }
    }
  }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun PersonaSelectionStepPreview() {
  MyApplicationTheme {
    PersonaSelectionStep(
      selectedPersonaId = "sharp_partner",
      isSaving = false,
      error = null,
      onSelectPersona = {},
      onBack = {},
      onApply = {},
    )
  }
}
