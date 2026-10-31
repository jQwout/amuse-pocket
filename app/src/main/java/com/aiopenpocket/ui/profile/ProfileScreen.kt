package com.aiopenpocket.ui.profile

// @trace profile-screen-7354a7

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.aiopenpocket.agent.core.memory.model.Fact
import com.aiopenpocket.i18n.tr
import com.aiopenpocket.ui.components.AcademyCollapsingScaffold
import com.aiopenpocket.ui.components.glass.GlassCard
import com.aiopenpocket.ui.components.glass.rememberGlassState
import com.aiopenpocket.ui.settings.LlmUsageCard
import com.aiopenpocket.ui.settings.SettingsSectionHeader
import com.aiopenpocket.ui.theme.GlassTokens
import dev.zacsweers.metrox.viewmodel.metroViewModel

private val FieldShape = RoundedCornerShape(12.dp)

/**
 * The Profile screen, opened by the round avatar on Home: the person's own data first (editable in place),
 * then what the assistant remembers about them, then the week's spend. The key, model, theme and language
 * stay in Settings, one tap away on the gear.
 */
@Composable
fun ProfileScreen(
  onNavigateBack: () -> Unit,
  onOpenSettings: () -> Unit,
  onOpenMemoryGraph: () -> Unit,
) {
  val viewModel: ProfileViewModel = metroViewModel()
  val state by viewModel.state.collectAsState()
  ProfileContent(
    state = state,
    onNavigateBack = onNavigateBack,
    onOpenSettings = onOpenSettings,
    onOpenMemoryGraph = onOpenMemoryGraph,
    onFieldChange = viewModel::onFieldChange,
    onSave = viewModel::save,
    onRequestDelete = viewModel::requestDelete,
    onConfirmDelete = viewModel::confirmDelete,
    onCancelDelete = viewModel::cancelDelete,
  )
}

/**
 * The screen without the ViewModel: [ProfileScreen] gets its state through metroViewModel(), which has no DI
 * graph in Robolectric, so tests and previews render THIS — the same component, fed fake state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProfileContent(
  state: ProfileUiState,
  onNavigateBack: () -> Unit,
  onOpenSettings: () -> Unit,
  onOpenMemoryGraph: () -> Unit,
  onFieldChange: (ProfileField, String) -> Unit,
  onSave: () -> Unit,
  onRequestDelete: (Fact) -> Unit,
  onConfirmDelete: () -> Unit,
  onCancelDelete: () -> Unit,
) {
  val glassState = rememberGlassState()
  AcademyCollapsingScaffold(
    title = tr("Профиль", "Profile"),
    glassState = glassState,
    navigationIcon = {
      IconButton(onClick = onNavigateBack) {
        Icon(
          imageVector = Icons.AutoMirrored.Default.ArrowBack,
          contentDescription = tr("Назад", "Back"),
        )
      }
    },
    actions = {
      IconButton(
        onClick = onOpenSettings,
        modifier = Modifier.testTag("profile_settings_button"),
      ) {
        Icon(
          imageVector = Icons.Default.Settings,
          contentDescription = tr("Настройки", "Settings"),
        )
      }
    },
  ) { padding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        // AFTER verticalScroll, as on Settings: the content slides under the floating bars and the glass has
        // something to blur.
        .padding(padding)
        .padding(horizontal = 20.dp),
    ) {
      Spacer(modifier = Modifier.height(8.dp))

      SettingsSectionHeader(
        icon = Icons.Default.Person,
        title = tr("ЛИЧНЫЕ ДАННЫЕ", "PERSONAL DATA"),
      )
      Spacer(modifier = Modifier.height(12.dp))
      PersonalDataCard(state = state, onFieldChange = onFieldChange, onSave = onSave)

      Spacer(modifier = Modifier.height(28.dp))

      SettingsSectionHeader(
        icon = Icons.Default.Psychology,
        title = tr("ЧТО ПОМНИТ АССИСТЕНТ", "WHAT THE ASSISTANT REMEMBERS"),
      )
      Spacer(modifier = Modifier.height(12.dp))
      RememberedFactsCard(
        sections = state.factSections,
        onRequestDelete = onRequestDelete,
        onOpenMemoryGraph = onOpenMemoryGraph,
      )

      Spacer(modifier = Modifier.height(28.dp))

      SettingsSectionHeader(
        icon = Icons.Default.Insights,
        title = tr("РАСХОД ЗА НЕДЕЛЮ", "USAGE THIS WEEK"),
      )
      Spacer(modifier = Modifier.height(12.dp))
      LlmUsageCard(usage = state.usage)

      Spacer(modifier = Modifier.height(32.dp))
    }
  }

  state.pendingDelete?.let { fact ->
    ForgetFactDialog(fact = fact, onConfirm = onConfirmDelete, onDismiss = onCancelDelete)
  }
}

@Composable
private fun PersonalDataCard(
  state: ProfileUiState,
  onFieldChange: (ProfileField, String) -> Unit,
  onSave: () -> Unit,
) {
  GlassCard(modifier = Modifier.fillMaxWidth()) {
    Column(
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      ProfileTextField(
        label = tr("Имя", "Name"),
        field = ProfileField.NAME,
        state = state,
        onFieldChange = onFieldChange,
      )
      ProfileTextField(
        label = tr("Как к вам обращаться", "How to address you"),
        field = ProfileField.ADDRESS,
        state = state,
        onFieldChange = onFieldChange,
      )
      ProfileTextField(
        label = tr("Возраст", "Age"),
        field = ProfileField.AGE,
        state = state,
        onFieldChange = onFieldChange,
      )
      ProfileTextField(
        label = tr("Пол", "Gender"),
        field = ProfileField.GENDER,
        state = state,
        onFieldChange = onFieldChange,
      )
      ProfileTextField(
        label = tr("О себе", "About me"),
        field = ProfileField.ABOUT,
        state = state,
        onFieldChange = onFieldChange,
        singleLine = false,
      )
      // Last and quiet: shown only for a person who set it (see ProfileUiState.showReligion).
      if (state.showReligion) {
        ProfileTextField(
          label = tr("Вероисповедание", "Religion"),
          field = ProfileField.RELIGION,
          state = state,
          onFieldChange = onFieldChange,
        )
      }
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Button(
          onClick = onSave,
          enabled = state.dirty,
          shape = RoundedCornerShape(14.dp),
          colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
          ),
          modifier = Modifier.testTag("profile_save_button"),
        ) {
          Text(tr("Сохранить", "Save"))
        }
        if (state.justSaved && !state.dirty) {
          Text(
            text = tr("Сохранено", "Saved"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
          )
        }
      }
    }
  }
}

/**
 * One field: a small caption above, the input below. The caption is separate from the input because a
 * floating label hides the "not set" hint until the field has focus, and an empty field should say so quietly
 * on its own — an empty profile is not an error.
 */
@Composable
private fun ProfileTextField(
  label: String,
  field: ProfileField,
  state: ProfileUiState,
  onFieldChange: (ProfileField, String) -> Unit,
  singleLine: Boolean = true,
) {
  Column {
    Text(
      text = label,
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(4.dp))
    TextField(
      value = state.draft.valueOf(field),
      onValueChange = { onFieldChange(field, it) },
      placeholder = {
        Text(
          text = tr("не указано", "not set"),
          color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
      },
      singleLine = singleLine,
      minLines = if (singleLine) 1 else 3,
      shape = FieldShape,
      colors = TextFieldDefaults.colors(
        focusedContainerColor = GlassTokens.LightTint,
        unfocusedContainerColor = GlassTokens.LightTint,
        disabledContainerColor = GlassTokens.LightTint,
        focusedIndicatorColor = Color.Transparent,
        unfocusedIndicatorColor = Color.Transparent,
        disabledIndicatorColor = Color.Transparent,
        cursorColor = MaterialTheme.colorScheme.primary,
      ),
      modifier = Modifier
        .fillMaxWidth()
        .border(1.dp, GlassTokens.BorderBrush, FieldShape)
        .testTag("profile_field_${field.name.lowercase()}"),
    )
  }
}

@Composable
private fun RememberedFactsCard(
  sections: List<FactSection>,
  onRequestDelete: (Fact) -> Unit,
  onOpenMemoryGraph: () -> Unit,
) {
  GlassCard(modifier = Modifier.fillMaxWidth()) {
    Column(
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      if (sections.isEmpty()) {
        Text(
          text = tr("Ассистент пока ничего не запомнил", "Nothing remembered yet"),
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
          text = tr(
            "Факты, которые ассистент сохранит о вас, появятся здесь — и их можно будет забыть.",
            "Facts the assistant saves about you will appear here, and you can make it forget them.",
          ),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      } else {
        sections.forEach { section ->
          Text(
            text = factGroupTitle(section.group),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 4.dp),
          )
          section.facts.forEach { fact ->
            FactRow(fact = fact, onDelete = { onRequestDelete(fact) })
          }
        }
      }
      Spacer(modifier = Modifier.height(6.dp))
      OutlinedButton(
        onClick = onOpenMemoryGraph,
        modifier = Modifier
          .fillMaxWidth()
          .testTag("profile_open_memory_graph"),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, GlassTokens.BorderBrush),
      ) {
        Icon(
          imageVector = Icons.Default.Hub,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.size(8.dp))
        Text(tr("Открыть граф памяти", "Open memory graph"))
      }
    }
  }
}

@Composable
private fun FactRow(fact: Fact, onDelete: () -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = fact.value,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Text(
        text = factLabel(fact.key),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    IconButton(
      onClick = onDelete,
      modifier = Modifier.testTag("profile_fact_delete_${fact.id}"),
    ) {
      Icon(
        imageVector = Icons.Default.Delete,
        contentDescription = tr("Забыть", "Forget"),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun ForgetFactDialog(fact: Fact, onConfirm: () -> Unit, onDismiss: () -> Unit) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(tr("Забыть этот факт?", "Forget this fact?")) },
    text = {
      Text(
        tr(
          "«${fact.value}» будет удалено из памяти ассистента.",
          "\"${fact.value}\" will be removed from the assistant's memory.",
        ),
      )
    },
    confirmButton = {
      TextButton(onClick = onConfirm, modifier = Modifier.testTag("profile_forget_confirm")) {
        Text(tr("Забыть", "Forget"))
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss, modifier = Modifier.testTag("profile_forget_cancel")) {
        Text(tr("Отмена", "Cancel"))
      }
    },
  )
}

private fun factGroupTitle(group: FactGroup): String = when (group) {
  FactGroup.ABOUT_YOU -> tr("О ВАС", "ABOUT YOU")
  FactGroup.PREFERENCES -> tr("ПРЕДПОЧТЕНИЯ", "PREFERENCES")
  FactGroup.OTHER -> tr("ДРУГОЕ", "OTHER")
}
