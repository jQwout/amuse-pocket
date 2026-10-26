package com.aiopenpocket.ui.settings

// @trace app-settings-beb470
//
// Cards and sections of the settings screen, moved out of SettingsScreen.kt (the same package, so no
// cross-imports — only private -> internal visibility for those the screen calls). Each card is
// self-contained and talks to the screen through callback parameters.

import android.content.pm.PackageManager
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.aiopenpocket.agent.core.identity.ChatFontScale
import com.aiopenpocket.agent.core.llm.LlmProvider
import com.aiopenpocket.i18n.tr
import com.aiopenpocket.ui.components.glass.glassCardSurface
import com.aiopenpocket.ui.components.MarkdownText
import com.aiopenpocket.ui.theme.GlassTokens
import java.util.Locale

@Composable
internal fun SettingsInfoCard(modifier: Modifier = Modifier) {
  Box(
    modifier = modifier
      .fillMaxWidth()
      .glassCardSurface(SettingsCardShape)
      .padding(horizontal = 20.dp, vertical = 18.dp),
  ) {
    Column {
      Text(
        text = "AI ACADEMY",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
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
internal fun SettingsSectionHeader(
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
      tint = MaterialTheme.colorScheme.primary,
      modifier = Modifier.size(18.dp),
    )
    Text(
      text = title,
      style = MaterialTheme.typography.labelLarge,
      color = MaterialTheme.colorScheme.primary,
    )
  }
}

@Composable
internal fun ApiKeyField(
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
          contentDescription = if (visible) tr("Скрыть", "Hide") else tr("Показать", "Show"),
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    },
    singleLine = true,
    shape = SettingsFieldShape,
    colors = TextFieldDefaults.colors(
      focusedContainerColor = GlassTokens.LightTint,
      unfocusedContainerColor = GlassTokens.LightTint,
      disabledContainerColor = GlassTokens.LightTint,
      focusedIndicatorColor = Color.Transparent,
      unfocusedIndicatorColor = Color.Transparent,
      disabledIndicatorColor = Color.Transparent,
      cursorColor = MaterialTheme.colorScheme.primary,
      focusedLabelColor = MaterialTheme.colorScheme.primary,
      unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ),
    modifier = modifier
      .fillMaxWidth()
      .border(1.dp, GlassTokens.BorderBrush, SettingsFieldShape),
  )
}

/**
 * Where to get a Meta key. Not only the owner's path: hackathon judges and anyone who builds the public
 * repository arrive here without a key, and the screen must explain itself, without the README.
 */
@Composable
internal fun MetaKeyHint(modifier: Modifier = Modifier) {
  val uriHandler = LocalUriHandler.current
  Text(
    text = tr(
      "Где взять ключ: dev.meta.ai — нажмите, откроется инструкция.",
      "Where to get a key: dev.meta.ai — tap to open the guide.",
    ),
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.primary,
    // runCatching: without a browser on the device openUri throws ActivityNotFoundException — a hint must
    // not crash the settings screen.
    modifier = modifier.clickable { runCatching { uriHandler.openUri(META_API_KEY_GUIDE_URL) } },
  )
}

private const val META_API_KEY_GUIDE_URL = "https://dev.meta.ai/docs/quickstart"

/**
 * Where requests will go right now. The route is switched by a single pasted key (see
 * [com.aiopenpocket.agent.core.llm.activeLlmProvider]), and without this line a person would not learn that
 * entering a Meta key changed anything, and without any keys at all — would learn it from an error already in
 * the chat.
 */
@Composable
internal fun ProviderRouteLine(
  provider: LlmProvider,
  openRouterKeyMissing: Boolean,
  modifier: Modifier = Modifier,
) {
  val noKey = provider == LlmProvider.OPENROUTER && openRouterKeyMissing
  val text = when {
    provider == LlmProvider.META ->
      tr("Запросы идут напрямую в Meta Model API.", "Requests go straight to Meta Model API.")
    noKey ->
      tr("Ключа нет — запросы не уйдут. Введите ключ Meta Model API.", "No key yet — requests won't go out. Add a Meta Model API key.")
    else ->
      tr("Запросы идут через OpenRouter. С ключом Meta они пойдут напрямую.", "Requests go through OpenRouter. Add a Meta key to go direct.")
  }
  val color = if (noKey) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    modifier = modifier.testTag("settings_provider_route"),
  ) {
    Icon(
      imageVector = Icons.Default.Key,
      contentDescription = null,
      tint = color,
      modifier = Modifier.size(14.dp),
    )
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = color)
  }
}

/**
 * The chat font size: a segmented control (the letters "A" of a growing size between flanking A/A) + a live
 * preview bubble drawn by the same [MarkdownText] as the real chat — the size matches exactly. Applied on
 * "Save": [onSelect] only updates the state (as SelectorCard used to do), persisting is in
 * SettingsViewModel.save(). The preview reacts to the choice instantly (it reads [selected]). `internal`
 * (not `private`) for the sake of a screenshot test in the same package.
 */
@Composable
internal fun ChatFontSizeCard(
  selected: ChatFontScale,
  onSelect: (ChatFontScale) -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier = modifier) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .testTag("settings_chat_font_selector"),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      // The flanking small "A" — the left end of the size axis.
      Text(text = "A", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Row(
        modifier = Modifier
          .weight(1f)
          .padding(horizontal = 12.dp)
          .clip(SettingsCardShape)
          .background(MaterialTheme.colorScheme.surfaceVariant)
          .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        ChatFontScale.entries.forEach { preset ->
          val isSelected = preset == selected
          Box(
            modifier = Modifier
              .weight(1f)
              .clip(SettingsFieldShape)
              .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent)
              .clickable { onSelect(preset) }
              .padding(vertical = 10.dp)
              .testTag("font_segment_${preset.name}"),
            contentAlignment = Alignment.Center,
          ) {
            // An "A" in the preset's own size — the segments grow from left to right.
            Text(
              text = "A",
              fontSize = (15 * preset.scale).sp,
              color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            )
          }
        }
      }
      // The flanking large "A" — the right end of the axis.
      Text(text = "A", fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    Spacer(modifier = Modifier.height(16.dp))

    // The live preview bubble: the same MarkdownText(fontScale) as in production — the size matches the chat.
    Box(
      modifier = Modifier
        .clip(RoundedCornerShape(20.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant)
        .padding(horizontal = 16.dp, vertical = 14.dp)
        .testTag("settings_chat_font_preview"),
    ) {
      MarkdownText(
        text = "Пример текста ответа — так агент будет выглядеть в чате.",
        compact = true,
        fontScale = selected.scale,
      )
    }
  }
}

/**
 * ONE selector row shape for the whole settings screen: a meaningful icon in a round badge, a label + the
 * current value, a chevron to the RIGHT (it opens a sheet rather than dropping down).
 *
 * "Model"/"Theme" used to be a stock `DropdownMenu`, and "Persona" a glass sheet, so two different choice
 * patterns lived on one screen, and the dropdown's was worse: a flat opaque panel with the default rounding
 * over the aurora, `.background()` on the outer modifier fought the menu's inner `Surface` (a hard rectangle
 * against a rounded surface), and the selected item differed only by text color. Plus the icon was always a
 * lightning bolt — even for the theme.
 */
@Composable
private fun SettingsSelectorRow(
  label: String,
  value: String,
  icon: ImageVector,
  onClick: () -> Unit,
  testTag: String,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier
      .fillMaxWidth()
      .glassCardSurface(SettingsCardShape)
      .clickable(onClick = onClick)
      .padding(horizontal = 16.dp, vertical = 18.dp)
      .testTag(testTag),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      modifier = Modifier
        .size(44.dp)
        .clip(CircleShape)
        .background(MaterialTheme.colorScheme.onPrimary),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(22.dp),
      )
    }
    Column(
      modifier = Modifier
        .weight(1f)
        .padding(horizontal = 16.dp),
    ) {
      Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(
        text = value,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    Icon(
      imageVector = Icons.AutoMirrored.Default.KeyboardArrowRight,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

/** A value selector (model, theme). Opens a [SelectorSheet] — nothing drops down by itself. */
@Composable
internal fun SelectorCard(
  label: String,
  selected: String,
  icon: ImageVector,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  testTag: String = "settings_selector",
) {
  SettingsSelectorRow(
    label = label,
    value = selected,
    icon = icon,
    onClick = onClick,
    testTag = testTag,
    modifier = modifier,
  )
}

/**
 * A glass choice sheet — the only way to choose a value in settings (the same pattern the persona already
 * had). There can be many items (models), so the sheet scrolls; the selected one is marked with a checkmark
 * AND a lime edge — like the selected language in onboarding and `PersonaCard`, not by text color alone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SelectorSheet(
  title: String,
  subtitle: String?,
  options: List<String>,
  selected: String,
  sheetState: SheetState,
  onSelect: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = MaterialTheme.colorScheme.surface,
  ) {
    SelectorSheetContent(
      title = title,
      subtitle = subtitle,
      options = options,
      selected = selected,
      onSelect = onSelect,
    )
  }
}

/**
 * The content of a [SelectorSheet] without the sheet itself. Moved out separately because `ModalBottomSheet`
 * is a popup window: `onRoot()` in a screenshot test does not capture it, and the sheet's layout would be left
 * without a baseline. It also separates the content from the container.
 */
@Composable
internal fun SelectorSheetContent(
  title: String,
  subtitle: String?,
  options: List<String>,
  selected: String,
  onSelect: (String) -> Unit,
) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .testTag("settings_selector_sheet"),
  ) {
    Text(
      text = title,
      style = MaterialTheme.typography.titleLarge,
      color = MaterialTheme.colorScheme.onSurface,
      modifier = Modifier.padding(horizontal = 20.dp),
    )
    if (!subtitle.isNullOrBlank()) {
      Text(
        text = subtitle,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp).padding(top = 4.dp),
      )
    }
    Spacer(modifier = Modifier.height(12.dp))
    // LazyColumn, not forEach in a scrolling column: the OpenRouter model list is live, it has hundreds of
    // rows, and eagerly composing all the cards would mean a sheet frozen for half a second on every opening.
    // heightIn(max) instead of fillMaxHeight — a short list (theme, provider) must stay a short sheet rather
    // than expand to the whole screen.
    LazyColumn(
      modifier = Modifier
        .fillMaxWidth()
        .heightIn(max = MAX_SELECTOR_LIST_HEIGHT),
      contentPadding = PaddingValues(bottom = 32.dp),
    ) {
      // No key ON PURPOSE: the rows here hold no state and are not animated, so a key gives nothing — while
      // `key = { it }` crashes LazyColumn on two identical rows. The list's source is a third-party
      // service's catalog, and the settings screen must not crash over a duplicate in someone else's data.
      items(options) { option ->
        val isSelected = option == selected
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .glassCardSurface(SettingsCardShape)
            .then(
              if (isSelected) {
                Modifier.border(2.dp, MaterialTheme.colorScheme.primary, SettingsCardShape)
              } else {
                Modifier
              },
            )
            .clickable { onSelect(option) }
            .padding(horizontal = 16.dp, vertical = 16.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = option,
            style = MaterialTheme.typography.bodyLarge,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
          )
          if (isSelected) {
            Icon(
              imageVector = Icons.Default.Check,
              contentDescription = "Выбрано",
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(20.dp),
            )
          }
        }
      }
    }
  }
}

/** The height ceiling of the list in the glass sheet — beyond it the list scrolls inside itself. */
private val MAX_SELECTOR_LIST_HEIGHT = 520.dp

@Composable
internal fun GoalIterationsCard(
  value: Int,
  onChange: (Int) -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier
      .fillMaxWidth()
      .glassCardSurface(SettingsCardShape)
      .padding(horizontal = 16.dp, vertical = 14.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      modifier = Modifier
        .size(44.dp)
        .clip(CircleShape)
        .background(MaterialTheme.colorScheme.onPrimary),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = Icons.Default.Loop,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
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
      color = MaterialTheme.colorScheme.primary,
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
internal fun IntegrationsSection(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current

  // The permission status is read from the system live; refreshTick forces a recomputation
  // after returning from the system dialog / settings (ON_RESUME).
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
  val calendarLauncher = rememberLauncherForActivityResult(
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
      .glassCardSurface(SettingsCardShape)
      .padding(horizontal = 16.dp, vertical = 14.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      modifier = Modifier
        .size(44.dp)
        .clip(CircleShape)
        .background(MaterialTheme.colorScheme.onPrimary),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
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
          containerColor = MaterialTheme.colorScheme.primary,
          contentColor = MaterialTheme.colorScheme.onPrimary,
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

/**
 * The persona opens its own sheet (it has rich cards with stats, not a flat list of rows), but the shape of
 * the row itself is the shared [SettingsSelectorRow], so the three settings selectors read as one family.
 */
@Composable
internal fun PersonaSelectorCard(
  personaId: String,
  displayName: String?,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  SettingsSelectorRow(
    label = "ТЕКУЩАЯ РОЛЬ",
    value = displayName ?: personaId,
    icon = Icons.Default.Psychology,
    onClick = onClick,
    testTag = "settings_persona_selector",
    modifier = modifier,
  )
}

/**
 * Spend over the week: how many calls, tokens and money went.
 *
 * Read-only and no buttons: the card answers "how much am I spending", it does not manage limits. Three
 * things are named out loud, because without them the figure above misleads: the share of background calls
 * (the user did not order them), the share of estimates (the provider does not always name the spend) and the
 * absence of a price (if the provider gave only tokens, dollars must not be made up from them).
 */
@Composable
internal fun LlmUsageCard(
  usage: UsageSummary?,
  modifier: Modifier = Modifier,
) {
  Box(
    modifier = modifier
      .fillMaxWidth()
      .glassCardSurface(SettingsCardShape)
      .padding(horizontal = 20.dp, vertical = 18.dp),
  ) {
    if (usage == null || usage.calls == 0) {
      Column {
        Text(
          text = "Пока не о чем отчитываться",
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = "Счётчик заполняется по мере разговоров с агентом.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      return@Box
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
      UsageRow(label = "Вызовов модели", value = usage.calls.toString())
      UsageRow(
        label = "Токенов",
        value = "${formatTokens(usage.promptTokens)} вход / ${formatTokens(usage.completionTokens)} выход",
      )
      UsageRow(
        label = "Стоимость",
        // An empty string instead of zero: zero would mean "free", while the honest answer is "not named".
        value = usage.costUsd?.let { "$" + String.format(Locale.US, "%.4f", it) }
          ?: "провайдер не называет",
      )
      if (usage.backgroundCalls > 0) {
        UsageRow(
          label = "Из них в фоне",
          value = "${usage.backgroundCalls} · ${formatTokens(usage.backgroundTokens)} токенов",
        )
      }
      if (usage.estimatedCalls > 0) {
        UsageRow(label = "Посчитано оценкой", value = "${usage.estimatedCalls} из ${usage.calls}")
      }
      if (usage.byModel.isNotEmpty()) {
        Spacer(modifier = Modifier.height(2.dp))
        Text(
          text = "ПО МОДЕЛЯМ",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.primary,
        )
        // Three rows, not the whole list: OpenRouter has hundreds of models, and a tail of one-off calls answers
        // none of the questions people look here for.
        usage.byModel.take(3).forEach { model ->
          UsageRow(
            label = model.model.substringAfterLast('/'),
            value = "${model.calls} · ${formatTokens(model.tokens)}",
          )
        }
      }
    }
  }
}

@Composable
private fun UsageRow(label: String, value: String) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
      text = value,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurface,
    )
  }
}

/** Thousands as "12.3k": an exact token count is unreadable and affects nothing. */
private fun formatTokens(tokens: Long): String = when {
  tokens < 1_000 -> tokens.toString()
  tokens < 1_000_000 -> String.format(Locale.US, "%.1fk", tokens / 1_000.0)
  else -> String.format(Locale.US, "%.2fM", tokens / 1_000_000.0)
}
