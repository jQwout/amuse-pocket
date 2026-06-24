package com.aiopenpocket.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aiopenpocket.agent.core.persona.PersonaCatalog
import com.aiopenpocket.ui.theme.CardDark
import com.aiopenpocket.ui.theme.MyApplicationTheme
import com.aiopenpocket.ui.theme.PersonaCardBg
import com.aiopenpocket.ui.theme.PersonaQuoteBg
import com.aiopenpocket.ui.theme.PurpleAccent
import com.aiopenpocket.ui.theme.PurpleAccentDark
import dev.zacsweers.metrox.viewmodel.metroViewModel

private val SettingsCardShape = RoundedCornerShape(16.dp)
private val SettingsFieldShape = RoundedCornerShape(12.dp)
private val SidePad = 20.dp
private val PROVIDERS = listOf("AUTO", "NIM", "GROQ", "GITHUB")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
  onNavigateBack: () -> Unit,
  onNavigateToScheduledWorks: () -> Unit,
) {
  val viewModel: SettingsViewModel = metroViewModel()
  val state by viewModel.state.collectAsState()
  SettingsContent(
    state = state,
    onNavigateBack = onNavigateBack,
    onNavigateToScheduledWorks = onNavigateToScheduledWorks,
    onUpdateNvidia = viewModel::updateNvidiaKey,
    onUpdateGroq = viewModel::updateGroqKey,
    onUpdateGithub = viewModel::updateGithubKey,
    onUpdateProvider = viewModel::updateProvider,
    onUpdatePersona = viewModel::updatePersona,
    onSave = viewModel::save,
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsContent(
  state: SettingsUiState,
  onNavigateBack: () -> Unit,
  onNavigateToScheduledWorks: () -> Unit,
  onUpdateNvidia: (String) -> Unit,
  onUpdateGroq: (String) -> Unit,
  onUpdateGithub: (String) -> Unit,
  onUpdateProvider: (String) -> Unit,
  onUpdatePersona: (String) -> Unit,
  onSave: () -> Unit,
) {
  var showPersonaSheet by remember { mutableStateOf(false) }
  val personaSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val currentPersona = PersonaCatalog.findById(state.personaId)

  Scaffold(
    containerColor = PersonaQuoteBg,
    bottomBar = { SaveBar(state.saved, onSave) },
  ) { padding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(padding)
        .verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
      SettingsTopBar(onNavigateBack = onNavigateBack)
      SettingsInfoCard(modifier = Modifier.padding(horizontal = SidePad))

      SettingsSection(icon = Icons.Default.Key, title = "API KEYS") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
          ApiKeyField("NVIDIA NIM API Key", state.nvidiaKey, onUpdateNvidia)
          ApiKeyField("Groq API Key", state.groqKey, onUpdateGroq)
          ApiKeyField("GitHub Models API Key", state.githubKey, onUpdateGithub)
        }
      }

      SettingsSection(icon = Icons.Default.Dns, title = "ПРОВАЙДЕР") {
        ProviderSelectorCard(state.selectedProvider, PROVIDERS, onUpdateProvider)
      }

      SettingsSection(icon = Icons.Default.Psychology, title = "ПЕРСОНА") {
        PersonaSelectorCard(
          personaId = state.personaId,
          displayName = currentPersona?.displayName,
          onClick = { showPersonaSheet = true },
        )
      }

      SettingsSection(icon = Icons.Default.TaskAlt, title = "ЗАДАЧИ") {
        ScheduledWorksButton(onClick = onNavigateToScheduledWorks)
      }

      SettingsSection(icon = Icons.Default.Apps, title = "ИНТЕГРАЦИИ") {
        IntegrationsSection()
      }

      Spacer(modifier = Modifier.height(4.dp))
    }
  }

  if (showPersonaSheet) {
    PersonaSheet(
      personas = state.personas,
      selectedPersonaId = state.personaId,
      sheetState = personaSheetState,
      onSelect = {
        onUpdatePersona(it)
        showPersonaSheet = false
      },
      onDismiss = { showPersonaSheet = false },
    )
  }
}

@Composable
private fun SaveBar(saved: Boolean, onSave: () -> Unit) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .background(PersonaQuoteBg)
      .padding(horizontal = SidePad)
      .padding(bottom = 24.dp, top = 8.dp),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Icon(
        imageVector = Icons.Default.Lock,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(14.dp),
      )
      Text(
        text = "Ключи хранятся в зашифрованном виде на устройстве.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Spacer(modifier = Modifier.height(16.dp))
    Button(
      onClick = onSave,
      modifier = Modifier
        .fillMaxWidth()
        .height(52.dp)
        .testTag("settings_save_button"),
      shape = RoundedCornerShape(14.dp),
      colors = ButtonDefaults.buttonColors(
        containerColor = PurpleAccent,
        contentColor = PurpleAccentDark,
      ),
    ) {
      Text("Сохранить изменения", style = MaterialTheme.typography.titleMedium)
    }
    if (saved) {
      Spacer(modifier = Modifier.height(8.dp))
      Text(
        text = "Сохранено",
        color = PurpleAccent,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.align(Alignment.CenterHorizontally),
      )
    }
  }
}

@Composable
private fun SettingsSection(
  icon: ImageVector,
  title: String,
  content: @Composable () -> Unit,
) {
  Column(
    modifier = Modifier.padding(horizontal = SidePad),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Icon(icon, contentDescription = null, tint = PurpleAccent, modifier = Modifier.size(18.dp))
      Text(title, style = MaterialTheme.typography.labelLarge, color = PurpleAccent)
    }
    content()
  }
}

@Composable
private fun SettingsTopBar(onNavigateBack: () -> Unit) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 8.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    IconButton(onClick = onNavigateBack) {
      Icon(
        imageVector = Icons.AutoMirrored.Default.ArrowBack,
        contentDescription = "Назад",
        tint = MaterialTheme.colorScheme.onSurface,
      )
    }
    Text(
      text = "Настройки",
      style = MaterialTheme.typography.titleLarge,
      color = MaterialTheme.colorScheme.onSurface,
      modifier = Modifier.weight(1f),
    )
    Icon(
      imageVector = Icons.Default.Settings,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(end = 16.dp).size(22.dp),
    )
  }
}

@Composable
private fun SettingsInfoCard(modifier: Modifier = Modifier) {
  Column(
    modifier = modifier
      .fillMaxWidth()
      .clip(SettingsCardShape)
      .background(PersonaCardBg)
      .padding(horizontal = SidePad, vertical = 18.dp),
  ) {
    Text("LUMINA AI LEARNING", style = MaterialTheme.typography.labelLarge, color = PurpleAccent)
    Spacer(modifier = Modifier.height(4.dp))
    Text(
      "Конфигурация окружения и ключей доступа",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurface,
    )
  }
}

@Composable
private fun ApiKeyField(
  label: String,
  value: String,
  onValueChange: (String) -> Unit,
) {
  var visible by remember { mutableStateOf(false) }
  TextField(
    value = value,
    onValueChange = onValueChange,
    label = { Text(label) },
    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
    trailingIcon = {
      IconButton(onClick = { visible = !visible }) {
        Icon(
          imageVector = if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
          contentDescription = if (visible) "Скрыть" else "Показать",
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    },
    singleLine = true,
    shape = SettingsFieldShape,
    colors = TextFieldDefaults.colors(
      focusedContainerColor = PersonaCardBg,
      unfocusedContainerColor = PersonaCardBg,
      disabledContainerColor = PersonaCardBg,
      focusedIndicatorColor = Color.Transparent,
      unfocusedIndicatorColor = Color.Transparent,
      disabledIndicatorColor = Color.Transparent,
      cursorColor = PurpleAccent,
      focusedLabelColor = PurpleAccent,
      unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ),
    modifier = Modifier.fillMaxWidth(),
  )
}

@Composable
private fun ProviderSelectorCard(
  selected: String,
  options: List<String>,
  onSelect: (String) -> Unit,
) {
  var expanded by remember { mutableStateOf(false) }
  Box {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .clip(SettingsCardShape)
        .background(PersonaCardBg)
        .clickable { expanded = true }
        .padding(horizontal = 16.dp, vertical = 18.dp)
        .testTag("settings_provider_selector"),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      AccentCircleIcon(icon = Icons.Default.Bolt)
      Text(
        text = selected,
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
      )
      Icon(
        imageVector = Icons.Default.KeyboardArrowDown,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    DropdownMenu(
      expanded = expanded,
      onDismissRequest = { expanded = false },
      modifier = Modifier.fillMaxWidth(0.9f).background(PersonaCardBg),
    ) {
      options.forEach { option ->
        DropdownMenuItem(
          text = {
            Text(
              text = option,
              color = if (option == selected) PurpleAccent else MaterialTheme.colorScheme.onSurface,
            )
          },
          onClick = {
            onSelect(option)
            expanded = false
          },
        )
      }
    }
  }
}

@Composable
private fun ScheduledWorksButton(onClick: () -> Unit) {
  OutlinedButton(
    onClick = onClick,
    modifier = Modifier
      .fillMaxWidth()
      .height(52.dp)
      .testTag("settings_scheduled_tasks"),
    shape = SettingsCardShape,
    border = BorderStroke(1.dp, CardDark),
    colors = ButtonDefaults.outlinedButtonColors(
      containerColor = PersonaCardBg,
      contentColor = MaterialTheme.colorScheme.onSurface,
    ),
  ) {
    Icon(
      imageVector = Icons.Default.CalendarMonth,
      contentDescription = null,
      tint = PurpleAccent,
      modifier = Modifier.size(20.dp),
    )
    Spacer(modifier = Modifier.size(12.dp))
    Text("Запланированные задачи", style = MaterialTheme.typography.bodyLarge)
  }
}

@Composable
private fun IntegrationsSection() {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  var refreshTick by remember { mutableIntStateOf(0) }

  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) refreshTick++
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  fun granted(vararg perms: String): Boolean =
    perms.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

  val calendarGranted = remember(refreshTick) {
    granted(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
  }
  val smsGranted = remember(refreshTick) { granted(Manifest.permission.READ_SMS) }
  val calendarLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestMultiplePermissions(),
  ) { refreshTick++ }
  val smsLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestMultiplePermissions(),
  ) { refreshTick++ }

  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    IntegrationRow(
      icon = Icons.Default.Event,
      title = "Календарь",
      subtitle = "Чтение и создание событий",
      granted = calendarGranted,
      onRequest = {
        calendarLauncher.launch(
          arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
        )
      },
    )
    IntegrationRow(
      icon = Icons.Default.Sms,
      title = "SMS",
      subtitle = "Чтение входящих сообщений",
      granted = smsGranted,
      onRequest = { smsLauncher.launch(arrayOf(Manifest.permission.READ_SMS)) },
    )
    IntegrationRow(
      icon = Icons.Default.Alarm,
      title = "Будильник",
      subtitle = "Установка через системные часы",
      granted = true,
      grantedLabel = "Активно",
      onRequest = null,
    )
    IntegrationRow(
      icon = Icons.Default.Email,
      title = "Почта",
      subtitle = "Составление и отправка письма",
      granted = true,
      grantedLabel = "Без разрешения",
      onRequest = null,
    )
  }
}

@Composable
private fun IntegrationRow(
  icon: ImageVector,
  title: String,
  subtitle: String,
  granted: Boolean,
  onRequest: (() -> Unit)?,
  grantedLabel: String = "Разрешено",
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(SettingsCardShape)
      .background(PersonaCardBg)
      .padding(horizontal = 16.dp, vertical = 14.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    AccentCircleIcon(icon = icon)
    Column(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
      Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
      Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    when {
      granted -> StatusChip(text = grantedLabel, granted = true)
      onRequest != null -> Button(
        onClick = onRequest,
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(
          containerColor = PurpleAccent,
          contentColor = PurpleAccentDark,
        ),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
      ) { Text("Разрешить", style = MaterialTheme.typography.labelLarge) }
      else -> StatusChip(text = "Не разрешено", granted = false)
    }
  }
}

@Composable
private fun StatusChip(text: String, granted: Boolean) {
  val color = if (granted) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant
  Box(
    modifier = Modifier
      .clip(RoundedCornerShape(8.dp))
      .background(color.copy(alpha = 0.15f))
      .padding(horizontal = 10.dp, vertical = 6.dp),
  ) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = color)
  }
}

@Composable
private fun PersonaSelectorCard(
  personaId: String,
  displayName: String?,
  onClick: () -> Unit,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(SettingsCardShape)
      .background(PersonaCardBg)
      .clickable(onClick = onClick)
      .padding(horizontal = 16.dp, vertical = 18.dp)
      .testTag("settings_persona_selector"),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    AccentCircleIcon(icon = Icons.Default.Psychology)
    Column(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
      Text(
        text = "ТЕКУЩАЯ РОЛЬ",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(
        text = displayName ?: personaId,
        style = MaterialTheme.typography.titleLarge.copy(fontStyle = FontStyle.Italic),
        color = MaterialTheme.colorScheme.onSurface,
      )
    }
    Icon(
      imageVector = Icons.AutoMirrored.Default.KeyboardArrowRight,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
private fun AccentCircleIcon(icon: ImageVector) {
  Box(
    modifier = Modifier
      .size(44.dp)
      .clip(CircleShape)
      .background(PurpleAccentDark),
    contentAlignment = Alignment.Center,
  ) {
    Icon(icon, contentDescription = null, tint = PurpleAccent, modifier = Modifier.size(22.dp))
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PersonaSheet(
  personas: List<String>,
  selectedPersonaId: String,
  sheetState: androidx.compose.material3.SheetState,
  onSelect: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = PersonaCardBg,
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = SidePad)
        .padding(bottom = 32.dp),
    ) {
      Text(
        "Выбор персоны",
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Spacer(modifier = Modifier.height(16.dp))
      personas.forEach { personaId ->
        val persona = PersonaCatalog.findById(personaId)
        val isSelected = personaId == selectedPersonaId
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onSelect(personaId) }
            .background(if (isSelected) PurpleAccentDark.copy(alpha = 0.4f) else Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 14.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = persona?.displayName ?: personaId,
              style = MaterialTheme.typography.bodyLarge,
              color = MaterialTheme.colorScheme.onSurface,
            )
            if (persona != null) {
              Text(
                text = persona.tone,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
          if (isSelected) {
            Icon(Icons.Default.Check, contentDescription = "Выбрано", tint = PurpleAccent)
          }
        }
      }
    }
  }
}

@Preview(showBackground = true, backgroundColor = 0xFF121212, heightDp = 1400)
@Composable
private fun SettingsScreenPreview() {
  MyApplicationTheme {
    SettingsContent(
      state = SettingsUiState(
        nvidiaKey = "nvapi-abc",
        groqKey = "",
        githubKey = "ghp_xyz",
        selectedProvider = "AUTO",
        personaId = "sharp_partner",
        personas = listOf("sharp_partner", "calm_mentor"),
        saved = true,
      ),
      onNavigateBack = {},
      onNavigateToScheduledWorks = {},
      onUpdateNvidia = {},
      onUpdateGroq = {},
      onUpdateGithub = {},
      onUpdateProvider = {},
      onUpdatePersona = {},
      onSave = {},
    )
  }
}
