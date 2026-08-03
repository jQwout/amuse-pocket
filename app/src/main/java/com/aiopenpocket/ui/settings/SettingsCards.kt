package com.aiopenpocket.ui.settings

// @trace app-settings-beb470
//
// Карточки и секции экрана настроек, вынесенные из SettingsScreen.kt (тот же пакет, поэтому
// без кросс-импортов — только видимость private -> internal у тех, что зовёт экран). Каждая
// карточка самодостаточна и общается с экраном через параметры-колбэки.

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Sms
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
import com.aiopenpocket.ui.components.glass.glassCardSurface
import com.aiopenpocket.ui.components.MarkdownText
import com.aiopenpocket.ui.theme.GlassTokens

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
          contentDescription = if (visible) "Скрыть" else "Показать",
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
 * Размер шрифта чата: сегмент-контрол (буквы «A» растущего кегля между фланкирующими A/A) +
 * живой пузырь-превью, отрисованный тем же [MarkdownText], что и настоящий чат — размер точь-в-точь.
 * Применение по «Сохранить»: [onSelect] обновляет только состояние (как раньше делал SelectorCard),
 * персист — в SettingsViewModel.save(). Превью реагирует на выбор мгновенно (читает [selected]).
 * `internal` (не `private`) ради скриншот-теста в том же пакете.
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
      // Фланкирующая маленькая «A» — левый край оси размера.
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
            // «A» кеглем самого пресета — сегменты растут слева направо.
            Text(
              text = "A",
              fontSize = (15 * preset.scale).sp,
              color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            )
          }
        }
      }
      // Фланкирующая крупная «A» — правый край оси.
      Text(text = "A", fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    Spacer(modifier = Modifier.height(16.dp))

    // Живой пузырь-превью: тот же MarkdownText(fontScale), что и в бою — размер совпадает с чатом.
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
 * ОДНА форма строки-селектора на весь экран настроек: осмысленная иконка в круглом бейдже, метка +
 * текущее значение, шеврон ВПРАВО (открывает лист, а не выпадает вниз).
 *
 * Раньше «Модель»/«Тема» были стоковым `DropdownMenu`, а «Персона» — стеклянным листом, из-за чего
 * на одном экране жили два разных паттерна выбора, и худший был у дропдауна: плоская непрозрачная
 * панель с дефолтным скруглением поверх авроры, `.background()` на внешнем модификаторе спорил с
 * внутренним `Surface` меню (жёсткий прямоугольник против скруглённой поверхности), а выбранный
 * пункт отличался только цветом текста. Плюс иконка всегда была молнией — даже у темы.
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

/** Селектор-значение (модель, тема). Открывает [SelectorSheet] — сам ничего не выпадает. */
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
 * Стеклянный лист выбора — единственный способ выбирать значение в настройках (тот же паттерн, что
 * уже был у персоны). Пунктов может быть много (моделей), поэтому лист прокручиваемый; выбранный
 * помечен галочкой И лаймовой гранью — как выбранный язык в онбординге и `PersonaCard`, а не одним
 * цветом текста.
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
 * Содержимое [SelectorSheet] без самого листа. Вынесено отдельно, потому что `ModalBottomSheet` —
 * popup-окно: `onRoot()` в скриншот-тесте его не захватывает, и вёрстка листа осталась бы без
 * эталона. Заодно контент отделён от контейнера.
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
    // LazyColumn, а не forEach в скроллящейся колонке: список моделей OpenRouter живой, в нём
    // сотни строк, и eager-композиция всех карточек означала бы застывший на полсекунды лист при
    // каждом открытии. heightIn(max) вместо fillMaxHeight — короткий список (тема, провайдер)
    // должен остаться коротким листом, а не разворачиваться во весь экран.
    LazyColumn(
      modifier = Modifier
        .fillMaxWidth()
        .heightIn(max = MAX_SELECTOR_LIST_HEIGHT),
      contentPadding = PaddingValues(bottom = 32.dp),
    ) {
      items(options, key = { it }) { option ->
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

/** Потолок высоты списка в стеклянном листе — дальше он скроллится внутри себя. */
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
 * Персона открывает свой лист (у неё богатые карточки со статами, не плоский список строк), но форма
 * самой строки — общая [SettingsSelectorRow], чтобы три селектора настроек читались как одна семья.
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
