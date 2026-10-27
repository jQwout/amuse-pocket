package com.aiopenpocket.ui.settings

// @trace app-settings-beb470
//
// Cards and sections of the settings screen, moved out of SettingsScreen.kt (the same package, so no
// cross-imports — only private -> internal visibility for those the screen calls). Each card is
// self-contained and talks to the screen through callback parameters. The selectors (model, theme,
// persona) are in SettingsSelectors.kt, the integrations section in SettingsIntegrations.kt.

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
