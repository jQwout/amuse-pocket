package com.aiopenpocket.ui.settings

// @trace app-settings-beb470

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.KeyboardArrowDown
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.aiopenpocket.agent.core.persona.PersonaCatalog
import com.aiopenpocket.ui.onboarding.components.PersonaCard
import com.aiopenpocket.ui.components.AcademyBottomNav
import com.aiopenpocket.ui.components.AcademyCollapsingScaffold
import com.aiopenpocket.ui.components.AcademyTab
import com.aiopenpocket.ui.theme.CardDark
import com.aiopenpocket.ui.theme.PersonaCardBg
import com.aiopenpocket.ui.theme.PersonaQuoteBg
import com.aiopenpocket.ui.theme.PurpleAccent
import com.aiopenpocket.ui.theme.PurpleAccentDark
import dev.zacsweers.metrox.viewmodel.metroViewModel

private val SettingsCardShape = RoundedCornerShape(16.dp)
private val SettingsFieldShape = RoundedCornerShape(12.dp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
  onNavigateBack: () -> Unit,
  onNavigateToScheduledWorks: () -> Unit,
  onNavigateToHome: () -> Unit,
  onNavigateToRoadmap: () -> Unit,
) {
  val viewModel: SettingsViewModel = metroViewModel()
  val state by viewModel.state.collectAsState()
  var showPersonaSheet by remember { mutableStateOf(false) }
  val personaSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val currentPersona = PersonaCatalog.findById(state.personaId)

  AcademyCollapsingScaffold(
    title = "Настройки",
    containerColor = PersonaQuoteBg,
    navigationIcon = {
      IconButton(onClick = onNavigateBack) {
        Icon(
          imageVector = Icons.AutoMirrored.Default.ArrowBack,
          contentDescription = "Назад",
        )
      }
    },
    actions = {
      Icon(
        imageVector = Icons.Default.Settings,
        contentDescription = null,
        modifier = Modifier
          .padding(end = 12.dp)
          .size(22.dp),
      )
    },
    bottomBar = {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .background(PersonaQuoteBg),
      ) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(top = 8.dp),
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
            onClick = viewModel::save,
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
            Text(
              text = "Сохранить изменения",
              style = MaterialTheme.typography.titleMedium,
            )
          }
          if (state.saved) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
              text = "Сохранено",
              color = PurpleAccent,
              style = MaterialTheme.typography.bodyMedium,
              modifier = Modifier.align(Alignment.CenterHorizontally),
            )
          }
        }
        AcademyBottomNav(
          selectedTab = AcademyTab.Tools,
          onTabSelected = { tab ->
            when (tab) {
              AcademyTab.Chat -> onNavigateToHome()
              AcademyTab.Path -> onNavigateToRoadmap()
              AcademyTab.Tools -> Unit
            }
          },
        )
      }
    },
  ) { padding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(padding)
        .verticalScroll(rememberScrollState()),
    ) {
      Spacer(modifier = Modifier.height(8.dp))

      SettingsInfoCard(
        modifier = Modifier.padding(horizontal = 20.dp),
      )

      Spacer(modifier = Modifier.height(28.dp))

      SettingsSectionHeader(
        icon = Icons.Default.Key,
        title = "API KEYS",
        modifier = Modifier.padding(horizontal = 20.dp),
      )
      Spacer(modifier = Modifier.height(12.dp))
      Column(
        modifier = Modifier.padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        ApiKeyField(
          label = "NVIDIA NIM API Key",
          value = state.nvidiaKey,
          onValueChange = viewModel::updateNvidiaKey,
        )
        ApiKeyField(
          label = "Groq API Key",
          value = state.groqKey,
          onValueChange = viewModel::updateGroqKey,
        )
        ApiKeyField(
          label = "GitHub Models API Key",
          value = state.githubKey,
          onValueChange = viewModel::updateGithubKey,
        )
      }

      Spacer(modifier = Modifier.height(28.dp))

      SettingsSectionHeader(
        icon = Icons.Default.Dns,
        title = "ПРОВАЙДЕР",
        modifier = Modifier.padding(horizontal = 20.dp),
      )
      Spacer(modifier = Modifier.height(12.dp))
      ProviderSelectorCard(
        selected = state.selectedProvider,
        options = listOf("AUTO", "NIM", "GROQ", "GITHUB"),
        onSelect = viewModel::updateProvider,
        modifier = Modifier.padding(horizontal = 20.dp),
      )

      Spacer(modifier = Modifier.height(28.dp))

      SettingsSectionHeader(
        icon = Icons.Default.Psychology,
        title = "ПЕРСОНА",
        modifier = Modifier.padding(horizontal = 20.dp),
      )
      Spacer(modifier = Modifier.height(12.dp))
      PersonaSelectorCard(
        personaId = state.personaId,
        displayName = currentPersona?.displayName,
        onClick = { showPersonaSheet = true },
        modifier = Modifier.padding(horizontal = 20.dp),
      )

      Spacer(modifier = Modifier.height(28.dp))

      SettingsSectionHeader(
        icon = Icons.Default.Flag,
        title = "РЕЖИМЫ АГЕНТА",
        modifier = Modifier.padding(horizontal = 20.dp),
      )
      Spacer(modifier = Modifier.height(12.dp))
      GoalIterationsCard(
        value = state.goalMaxIterations,
        onChange = viewModel::updateGoalMaxIterations,
        modifier = Modifier.padding(horizontal = 20.dp),
      )

      Spacer(modifier = Modifier.height(28.dp))

      SettingsSectionHeader(
        icon = Icons.Default.TaskAlt,
        title = "ЗАДАЧИ",
        modifier = Modifier.padding(horizontal = 20.dp),
      )
      Spacer(modifier = Modifier.height(12.dp))
      OutlinedButton(
        onClick = onNavigateToScheduledWorks,
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 20.dp)
          .height(52.dp)
          .testTag("settings_scheduled_tasks"),
        shape = SettingsCardShape,
        border = androidx.compose.foundation.BorderStroke(1.dp, CardDark),
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
        Text(
          text = "Запланированные задачи",
          style = MaterialTheme.typography.bodyLarge,
        )
      }

      Spacer(modifier = Modifier.height(28.dp))

      SettingsSectionHeader(
        icon = Icons.Default.Apps,
        title = "ИНТЕГРАЦИИ",
        modifier = Modifier.padding(horizontal = 20.dp),
      )
      Spacer(modifier = Modifier.height(12.dp))
      IntegrationsSection(modifier = Modifier.padding(horizontal = 20.dp))

      Spacer(modifier = Modifier.height(32.dp))
    }
  }

  if (showPersonaSheet) {
    ModalBottomSheet(
      onDismissRequest = { showPersonaSheet = false },
      sheetState = personaSheetState,
      containerColor = PersonaCardBg,
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .verticalScroll(rememberScrollState())
          .padding(bottom = 32.dp),
      ) {
        Text(
          text = "Выбор персоны",
          style = MaterialTheme.typography.titleLarge,
          color = MaterialTheme.colorScheme.onSurface,
          modifier = Modifier.padding(start = 20.dp, end = 20.dp),
        )
        Text(
          text = "Выбери напарника — у каждого свой характер, статы и манера",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp),
        )
        Spacer(modifier = Modifier.height(10.dp))
        state.personas.forEach { personaId ->
          val persona = PersonaCatalog.findById(personaId)
          if (persona != null) {
            PersonaCard(
              persona = persona,
              isSelected = personaId == state.personaId,
              onSelect = {
                viewModel.updatePersona(personaId)
                showPersonaSheet = false
              },
            )
          }
        }
      }
    }
  }
}

@Composable
private fun SettingsInfoCard(modifier: Modifier = Modifier) {
  Box(
    modifier = modifier
      .fillMaxWidth()
      .clip(SettingsCardShape)
      .background(PersonaCardBg)
      .padding(horizontal = 20.dp, vertical = 18.dp),
  ) {
    Column {
      Text(
        text = "AI ACADEMY",
        style = MaterialTheme.typography.labelLarge,
        color = PurpleAccent,
      )
      Spacer(modifier = Modifier.height(4.dp))
      Text(
        text = "Конфигурация окружения и ключей доступа",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
      )
    }
  }
}

@Composable
private fun SettingsSectionHeader(
  icon: ImageVector,
  title: String,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier,
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Icon(
      imageVector = icon,
      contentDescription = null,
      tint = PurpleAccent,
      modifier = Modifier.size(18.dp),
    )
    Text(
      text = title,
      style = MaterialTheme.typography.labelLarge,
      color = PurpleAccent,
    )
  }
}

@Composable
private fun ApiKeyField(
  label: String,
  value: String,
  onValueChange: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  var visible by remember { mutableStateOf(false) }

  TextField(
    value = value,
    onValueChange = onValueChange,
    label = { Text(label) },
    visualTransformation = if (visible) {
      VisualTransformation.None
    } else {
      PasswordVisualTransformation()
    },
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
    modifier = modifier.fillMaxWidth(),
  )
}

@Composable
private fun ProviderSelectorCard(
  selected: String,
  options: List<String>,
  onSelect: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  var expanded by remember { mutableStateOf(false) }

  Box(modifier = modifier) {
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
      Box(
        modifier = Modifier
          .size(44.dp)
          .clip(CircleShape)
          .background(PurpleAccentDark),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = Icons.Default.Bolt,
          contentDescription = null,
          tint = PurpleAccent,
          modifier = Modifier.size(22.dp),
        )
      }
      Text(
        text = selected,
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
          .weight(1f)
          .padding(horizontal = 16.dp),
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
      modifier = Modifier
        .fillMaxWidth(0.9f)
        .background(PersonaCardBg),
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
private fun GoalIterationsCard(
  value: Int,
  onChange: (Int) -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier
      .fillMaxWidth()
      .clip(SettingsCardShape)
      .background(PersonaCardBg)
      .padding(horizontal = 16.dp, vertical = 14.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      modifier = Modifier
        .size(44.dp)
        .clip(CircleShape)
        .background(PurpleAccentDark),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = Icons.Default.Loop,
        contentDescription = null,
        tint = PurpleAccent,
        modifier = Modifier.size(22.dp),
      )
    }
    Column(
      modifier = Modifier
        .weight(1f)
        .padding(horizontal = 16.dp),
    ) {
      Text(
        text = "Гоал-мод: лимит витков",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Text(
        text = "Сколько кругов петли агент сделает к цели",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    IconButton(onClick = { onChange(value - 1) }, enabled = value > 1) {
      Icon(
        imageVector = Icons.Default.Remove,
        contentDescription = "Меньше",
        tint = MaterialTheme.colorScheme.onSurface,
      )
    }
    Text(
      text = value.toString(),
      style = MaterialTheme.typography.titleLarge,
      color = PurpleAccent,
      modifier = Modifier.padding(horizontal = 8.dp),
    )
    IconButton(onClick = { onChange(value + 1) }, enabled = value < 10) {
      Icon(
        imageVector = Icons.Default.Add,
        contentDescription = "Больше",
        tint = MaterialTheme.colorScheme.onSurface,
      )
    }
  }
}

@Composable
private fun IntegrationsSection(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current

  // Статус разрешений читается из системы вживую; refreshTick форсит пересчёт
  // после возврата из системного диалога / настроек (ON_RESUME).
  var refreshTick by remember { mutableIntStateOf(0) }

  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) refreshTick++
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  fun isGranted(permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

  val calendarGranted = remember(refreshTick) {
    isGranted(Manifest.permission.READ_CALENDAR) && isGranted(Manifest.permission.WRITE_CALENDAR)
  }
  val smsGranted = remember(refreshTick) { isGranted(Manifest.permission.READ_SMS) }

  val calendarLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestMultiplePermissions(),
  ) { refreshTick++ }
  val smsLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestMultiplePermissions(),
  ) { refreshTick++ }

  Column(
    modifier = modifier,
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
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
    Box(
      modifier = Modifier
        .size(44.dp)
        .clip(CircleShape)
        .background(PurpleAccentDark),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = PurpleAccent,
        modifier = Modifier.size(22.dp),
      )
    }
    Column(
      modifier = Modifier
        .weight(1f)
        .padding(horizontal = 16.dp),
    ) {
      Text(
        text = title,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Text(
        text = subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
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
      ) {
        Text(text = "Разрешить", style = MaterialTheme.typography.labelLarge)
      }
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
    Text(
      text = text,
      style = MaterialTheme.typography.labelMedium,
      color = color,
    )
  }
}

@Composable
private fun PersonaSelectorCard(
  personaId: String,
  displayName: String?,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier
      .fillMaxWidth()
      .clip(SettingsCardShape)
      .background(PersonaCardBg)
      .clickable(onClick = onClick)
      .padding(horizontal = 16.dp, vertical = 18.dp)
      .testTag("settings_persona_selector"),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      modifier = Modifier
        .size(44.dp)
        .clip(CircleShape)
        .background(PurpleAccentDark),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = Icons.Default.Psychology,
        contentDescription = null,
        tint = PurpleAccent,
        modifier = Modifier.size(22.dp),
      )
    }
    Column(
      modifier = Modifier
        .weight(1f)
        .padding(horizontal = 16.dp),
    ) {
      Text(
        text = "ТЕКУЩАЯ РОЛЬ",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(
        text = displayName ?: personaId,
        style = MaterialTheme.typography.titleLarge,
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
