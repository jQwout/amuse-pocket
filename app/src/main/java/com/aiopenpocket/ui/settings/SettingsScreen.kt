package com.aiopenpocket.ui.settings

// @trace app-settings-beb470
// @trace vybor-provaydera-i-zhivoy-katalog-modeley-fe9a53

import com.aiopenpocket.agent.core.identity.ChatFontScale
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiopenpocket.BuildConfig
import com.aiopenpocket.agent.core.llm.bundledOpenRouterKey
import com.aiopenpocket.agent.core.persona.PersonaCatalog
import com.aiopenpocket.ui.onboarding.components.PersonaCard
import com.aiopenpocket.ui.components.AcademyBottomNav
import com.aiopenpocket.ui.components.AcademyCollapsingScaffold
import com.aiopenpocket.ui.components.MarkdownText
import com.aiopenpocket.ui.components.glass.glassCardSurface
import com.aiopenpocket.ui.components.glass.rememberGlassState
import com.aiopenpocket.ui.components.AcademyTab
import com.aiopenpocket.ui.theme.GlassTokens
import com.aiopenpocket.ui.theme.ThemeMode
import dev.zacsweers.metrox.viewmodel.metroViewModel

// internal (не private): общие для экрана и вынесенных карточек в SettingsCards.kt (тот же пакет).
internal val SettingsCardShape = RoundedCornerShape(16.dp)
internal val SettingsFieldShape = RoundedCornerShape(12.dp)

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

  // Выбор значения везде идёт через стеклянный лист (как у персоны), а не через стоковый
  // DropdownMenu — см. SettingsSelectorRow.
  var showModelSheet by remember { mutableStateOf(false) }
  val modelSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  var showThemeSheet by remember { mutableStateOf(false) }
  val themeSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  // Лист темы живёт вне прокручиваемой колонки, поэтому маппинг label↔enum поднят сюда (раньше
  // объявлялся прямо у селектора). Порядок фиксированный — LinkedHashMap.
  val themeLabels = remember {
    linkedMapOf(
      ThemeMode.LIGHT to "Светлая",
      ThemeMode.DARK to "Тёмная",
      ThemeMode.SYSTEM to "Системная",
    )
  }
  val themeByLabel = remember { themeLabels.entries.associate { (mode, label) -> label to mode } }

  // Аврора-фон + стекло, как на вкладке «Чат» — навбар/каркас становятся прозрачными (см. glassState).
  val glassState = rememberGlassState()
  AcademyCollapsingScaffold(
    title = "Настройки",
    glassState = glassState,
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
          .fillMaxWidth(),
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
              text = "Ключ хранится в зашифрованном виде на устройстве.",
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
              containerColor = MaterialTheme.colorScheme.primary,
              contentColor = MaterialTheme.colorScheme.onPrimary,
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
              color = MaterialTheme.colorScheme.primary,
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
          glassState = glassState,
        )
      }
    },
  ) { padding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        // ПОСЛЕ verticalScroll: иначе отступы инсетят вьюпорт и контент не заезжает под
        // плавающие острова — стеклу нечего размывать.
        .padding(padding),
    ) {
      Spacer(modifier = Modifier.height(8.dp))

      SettingsInfoCard(
        modifier = Modifier.padding(horizontal = 20.dp),
      )

      Spacer(modifier = Modifier.height(28.dp))

      SettingsSectionHeader(
        icon = Icons.Default.Key,
        title = "КЛЮЧ API",
        modifier = Modifier.padding(horizontal = 20.dp),
      )
      Spacer(modifier = Modifier.height(12.dp))
      Column(
        modifier = Modifier.padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        ApiKeyField(
          label = "Ключ OpenRouter",
          value = state.openRouterKey,
          onValueChange = viewModel::updateOpenRouterKey,
        )
      }

      // Без этой строки первым сигналом о забытом ключе был бы «All providers failed» в чате, уже
      // после отправки вопроса. Тем же предикатом, что и резолвер ключа в цепочке, — иначе
      // предупреждение и реальное поведение однажды разойдутся.
      val keyMissing = state.openRouterKey.isBlank() && bundledOpenRouterKey(BuildConfig.OPENROUTER_API_KEY).isBlank()
      if (keyMissing) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
          Icon(
            imageVector = Icons.Default.Key,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(14.dp),
          )
          Text(
            text = "Ключ OpenRouter не задан — запросы не уйдут.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
          )
        }
      }

      Spacer(modifier = Modifier.height(28.dp))

      SettingsSectionHeader(
        icon = Icons.Default.Memory,
        title = "МОДЕЛЬ",
        modifier = Modifier.padding(horizontal = 20.dp),
      )
      Spacer(modifier = Modifier.height(12.dp))
      SelectorCard(
        label = "ТЕКУЩАЯ МОДЕЛЬ",
        selected = modelLabel(state.selectedModel),
        // Та же иконка, что у заголовка секции — строка и заголовок читаются как одно целое.
        icon = Icons.Default.Memory,
        onClick = { showModelSheet = true },
        modifier = Modifier.padding(horizontal = 20.dp),
        testTag = "settings_model_selector",
      )

      Spacer(modifier = Modifier.height(28.dp))

      SettingsSectionHeader(
        icon = Icons.Default.Palette,
        title = "ТЕМА",
        modifier = Modifier.padding(horizontal = 20.dp),
      )
      Spacer(modifier = Modifier.height(12.dp))
      SelectorCard(
        label = "ОФОРМЛЕНИЕ",
        selected = themeLabels.getValue(state.themeMode),
        icon = Icons.Default.Palette,
        onClick = { showThemeSheet = true },
        modifier = Modifier.padding(horizontal = 20.dp),
        testTag = "settings_theme_selector",
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
        icon = Icons.Default.FormatSize,
        title = "РАЗМЕР ТЕКСТА",
        modifier = Modifier.padding(horizontal = 20.dp),
      )
      Spacer(modifier = Modifier.height(12.dp))
      ChatFontSizeCard(
        selected = state.chatFontScale,
        onSelect = viewModel::updateChatFontScale,
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
        border = androidx.compose.foundation.BorderStroke(1.dp, GlassTokens.BorderBrush),
        colors = ButtonDefaults.outlinedButtonColors(
          containerColor = GlassTokens.LightTint,
          contentColor = MaterialTheme.colorScheme.onSurface,
        ),
      ) {
        Icon(
          imageVector = Icons.Default.CalendarMonth,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
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
        icon = Icons.Default.Insights,
        title = "РАСХОД ЗА НЕДЕЛЮ",
        modifier = Modifier.padding(horizontal = 20.dp),
      )
      Spacer(modifier = Modifier.height(12.dp))
      LlmUsageCard(
        usage = state.usage,
        modifier = Modifier.padding(horizontal = 20.dp),
      )

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

  if (showModelSheet) {
    SelectorSheet(
      title = "Выбор модели",
      subtitle = "На чём агент будет думать. Быстрее — не всегда умнее.",
      options = state.availableModels.map(::modelLabel),
      selected = modelLabel(state.selectedModel),
      sheetState = modelSheetState,
      onSelect = { label ->
        state.availableModels.firstOrNull { modelLabel(it) == label }?.let(viewModel::updateModel)
        showModelSheet = false
      },
      onDismiss = { showModelSheet = false },
    )
  }

  if (showThemeSheet) {
    SelectorSheet(
      title = "Оформление",
      subtitle = "Системная подхватывает настройку телефона.",
      options = themeLabels.values.toList(),
      selected = themeLabels.getValue(state.themeMode),
      sheetState = themeSheetState,
      onSelect = { label ->
        themeByLabel[label]?.let(viewModel::updateThemeMode)
        showThemeSheet = false
      },
      onDismiss = { showThemeSheet = false },
    )
  }

  if (showPersonaSheet) {
    ModalBottomSheet(
      onDismissRequest = { showPersonaSheet = false },
      sheetState = personaSheetState,
      containerColor = MaterialTheme.colorScheme.surface,
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

/**
 * Имя модели для человека: «meta/muse-spark-1.3» → «Muse Spark 1.3», "AUTO" → «Авто — по приоритету».
 * Id остаётся ключом в настройках и в цепочке; здесь только подпись — без префикса вендора (он у всех
 * один) и без сентинела настроек, который пользователю ничего не говорит.
 */
internal fun modelLabel(id: String): String {
  if (id == "AUTO") return "Авто — по приоритету"
  return id.substringAfter('/')
    .split('-')
    // «30b» — размер модели в миллиардах параметров, его пишут «30B».
    .joinToString(" ") { part -> if (part.matches(SIZE_SUFFIX)) part.uppercase() else part.replaceFirstChar { it.uppercase() } }
}

private val SIZE_SUFFIX = Regex("\\d+b")
