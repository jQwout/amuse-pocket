package com.aiopenpocket.ui.agents.detail

// @trace cloud-forged-screens-40d702

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.aiopenpocket.agent.core.forge.AgentUiRequest
import com.aiopenpocket.agent.core.forge.ForgedAgentUi
import com.aiopenpocket.agent.core.forge.ForgedScreenState
import com.aiopenpocket.i18n.tr
import com.aiopenpocket.ui.agents.builder.components.BuilderTextField
import com.aiopenpocket.ui.components.glass.GlassCard

/**
 * "Interface" on the agent's card: the switch between the chat and the agent's own screen, and the
 * levers once the person chose the screen - follow the job, answer the agent's question, open the
 * screen, ask for a change, regenerate.
 *
 * Stateless like the other sections; every action goes up to the ViewModel. [renderBundle] is a slot
 * on purpose: in the app it is `ForgedBundleHost` (the Ketoy interpreter), in previews and screenshot
 * tests a stand-in card - Robolectric has no VM to run the bundle, and the section's own layout is
 * what those tests guard.
 */
@Composable
fun ForgedScreenSection(
  ui: ForgedAgentUi,
  draft: String,
  configured: Boolean,
  onSetCustomUi: (Boolean) -> Unit,
  onDraftChange: (String) -> Unit,
  onRequestChange: () -> Unit,
  onRegenerate: () -> Unit,
  onRetry: () -> Unit,
  onAnswer: (String) -> Unit,
  onRemove: () -> Unit,
  onOpenUi: () -> Unit,
  renderBundle: @Composable (ForgedScreenState.Ready, ByteArray) -> Unit,
  modifier: Modifier = Modifier,
  /** Debug builds only: renders the bundled sample without a gateway. Null hides the chip. */
  onLoadSample: (() -> Unit)? = null,
) {
  val state = ui.state
  Column(modifier = modifier.fillMaxWidth()) {
    AgentSectionHeader(
      icon = Icons.Default.Cloud,
      label = tr("Интерфейс", "Interface"),
      trailing = (state as? ForgedScreenState.Ready)?.let { tr("версия ${it.version}", "version ${it.version}") },
    )
    Spacer(modifier = Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      FilterChip(
        selected = !ui.customUiEnabled,
        onClick = { onSetCustomUi(false) },
        label = { Text(tr("Чат", "Chat")) },
        modifier = Modifier.testTag("forged_mode_chat"),
      )
      FilterChip(
        selected = ui.customUiEnabled,
        onClick = { onSetCustomUi(true) },
        label = { Text(tr("Свой интерфейс · бета", "Own interface · beta")) },
        modifier = Modifier.testTag("forged_mode_custom"),
      )
    }
    Spacer(modifier = Modifier.height(10.dp))
    if (!configured) {
      Text(
        text = tr("Укажи адрес кузницы и токен в Настройках.", "Set the forge address and token in Settings."),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.testTag("forged_not_configured").padding(bottom = 8.dp),
      )
    }
    when (state) {
      ForgedScreenState.None -> NoneCard(
        customUi = ui.customUiEnabled,
        configured = configured,
        onRegenerate = onRegenerate,
        onLoadSample = onLoadSample,
      )

      is ForgedScreenState.Requested -> ProgressCard(
        text = tr("В очереди у кузницы…", "Queued at the forge…"),
        request = state.request,
        onCancel = onRemove,
      )

      is ForgedScreenState.Working -> ProgressCard(
        text = state.message ?: tr("Агент пишет код и собирает экран…", "The agent is writing and compiling the screen…"),
        request = state.request,
        onCancel = onRemove,
      )

      is ForgedScreenState.NeedsInfo -> QuestionCard(question = state.question, onAnswer = onAnswer, onCancel = onRemove)

      is ForgedScreenState.Ready -> ReadyCard(
        state = state,
        bundle = ui.bundle,
        draft = draft,
        configured = configured,
        onDraftChange = onDraftChange,
        onRequestChange = onRequestChange,
        onRegenerate = onRegenerate,
        onOpenUi = onOpenUi,
        onRemove = onRemove,
        renderBundle = renderBundle,
      )

      is ForgedScreenState.Failed -> FailedCard(
        message = state.message,
        configured = configured,
        onRetry = onRetry,
        onRemove = onRemove,
      )
    }
  }
}

@Composable
private fun NoneCard(customUi: Boolean, configured: Boolean, onRegenerate: () -> Unit, onLoadSample: (() -> Unit)?) {
  GlassCard(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text(
        text = if (customUi) {
          tr(
            "Экрана пока нет. Кузница соберёт его из имени, описания и инструкций агента.",
            "No screen yet. The forge builds it from the agent's name, description and instructions.",
          )
        } else {
          tr(
            "Агент открывается в чате. Включи «Свой интерфейс» — и агент в облаке напишет ему собственный экран на Compose; приложение покажет его без обновления.",
            "The agent opens in the chat. Switch on \"Own interface\" and an agent in the cloud writes it a screen of its own in Compose; the app shows it without an update.",
          )
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
      )
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (customUi) {
          Button(onClick = onRegenerate, enabled = configured, modifier = Modifier.testTag("forged_build")) {
            Text(tr("Собрать экран", "Build the screen"))
          }
        }
        if (onLoadSample != null) {
          TextButton(onClick = onLoadSample, modifier = Modifier.testTag("forged_load_sample")) {
            Text(tr("Пример из сборки", "Bundled sample"))
          }
        }
      }
    }
  }
}

/**
 * A job in flight. An opaque sage panel, not glass: on glass with half-transparent text the one card
 * that says "something is happening" read as washed out, quieter than the stats below it (owner, phone,
 * 4 Nov 2026).
 */
@Composable
private fun ProgressCard(text: String, request: String, onCancel: () -> Unit) {
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(20.dp),
    color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
  ) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(
          modifier = Modifier.size(22.dp),
          strokeWidth = 2.5.dp,
          color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(text = text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("forged_status"))
      }
      Text(
        text = tr("Пока можно пользоваться чатом.", "The chat works meanwhile."),
        style = MaterialTheme.typography.bodyMedium,
      )
      Text(
        // @trace screen-brief-and-mockup-before-the-forge-builds-82cfb2
        // A brief build quotes the person's own words; the generic line is left for requests without them.
        text = AgentUiRequest.briefOf(request)?.let { "“${it.text.take(160)}”" }
          ?: if (AgentUiRequest.isAuto(request)) {
            tr("Экран собирается из имени, описания и инструкций агента.", "Built from the agent name, description and instructions.")
          } else {
            "“${request.take(160)}”"
          },
        style = MaterialTheme.typography.bodySmall,
      )
      TextButton(onClick = onCancel, modifier = Modifier.testTag("forged_remove")) {
        Text(tr("Отменить", "Cancel"))
      }
    }
  }
}

@Composable
private fun QuestionCard(question: String, onAnswer: (String) -> Unit, onCancel: () -> Unit) {
  var answer by rememberSaveable { mutableStateOf("") }
  GlassCard(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text(
        text = tr("Агент спрашивает:", "The agent asks:"),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
      )
      Text(text = question, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("forged_question"))
      BuilderTextField(
        value = answer,
        onValueChange = { answer = it },
        placeholder = tr("Твой ответ", "Your answer"),
        minLines = 1,
        maxLines = 3,
        modifier = Modifier.fillMaxWidth().testTag("forged_answer_field"),
      )
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
          onClick = { onAnswer(answer.trim()); answer = "" },
          enabled = answer.isNotBlank(),
          modifier = Modifier.testTag("forged_answer"),
        ) {
          Text(tr("Ответить", "Answer"))
        }
        TextButton(onClick = onCancel, modifier = Modifier.testTag("forged_remove")) {
          Text(tr("Отменить", "Cancel"))
        }
      }
    }
  }
}

@Composable
private fun ReadyCard(
  state: ForgedScreenState.Ready,
  bundle: ByteArray?,
  draft: String,
  configured: Boolean,
  onDraftChange: (String) -> Unit,
  onRequestChange: () -> Unit,
  onRegenerate: () -> Unit,
  onOpenUi: () -> Unit,
  onRemove: () -> Unit,
  renderBundle: @Composable (ForgedScreenState.Ready, ByteArray) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
      // A fixed height on purpose: the section sits inside the card's vertical scroll, and a guest
      // screen that scrolls itself must not fight it. The full screen is one tap away.
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .height(320.dp)
          .clip(RoundedCornerShape(16.dp))
          .testTag("forged_screen"),
      ) {
        if (bundle != null) {
          renderBundle(state, bundle)
        } else {
          Text(
            text = tr("Бандл не найден на диске.", "The bundle is missing on disk."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(16.dp),
          )
        }
      }
    }
    GlassCard(modifier = Modifier.fillMaxWidth()) {
      Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = onOpenUi, modifier = Modifier.fillMaxWidth().testTag("forged_open")) {
          Text(tr("Открыть во весь экран", "Open full screen"))
        }
        BuilderTextField(
          value = draft,
          onValueChange = onDraftChange,
          placeholder = tr("Что добавить или изменить? Например: добавь кнопку сброса", "What to add or change? e.g. add a reset button"),
          minLines = 1,
          maxLines = 3,
          modifier = Modifier.fillMaxWidth().testTag("forged_request_field"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Button(
            onClick = onRequestChange,
            enabled = configured && draft.isNotBlank(),
            modifier = Modifier.testTag("forged_update"),
          ) {
            Text(tr("Обновить", "Update"))
          }
          TextButton(onClick = onRegenerate, enabled = configured, modifier = Modifier.testTag("forged_regenerate")) {
            Text(tr("Заново", "Regenerate"))
          }
          TextButton(onClick = onRemove, modifier = Modifier.testTag("forged_remove")) {
            Text(tr("Убрать", "Remove"))
          }
        }
      }
    }
  }
}

@Composable
private fun FailedCard(message: String, configured: Boolean, onRetry: () -> Unit, onRemove: () -> Unit) {
  GlassCard(
    modifier = Modifier.fillMaxWidth(),
    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.10f),
  ) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.testTag("forged_status"),
      )
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onRetry, enabled = configured, modifier = Modifier.testTag("forged_build")) {
          Text(tr("Повторить", "Try again"))
        }
        TextButton(onClick = onRemove, modifier = Modifier.testTag("forged_remove")) {
          Text(tr("Убрать", "Remove"))
        }
      }
    }
  }
}
