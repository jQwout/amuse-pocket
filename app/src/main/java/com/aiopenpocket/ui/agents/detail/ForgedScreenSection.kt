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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.aiopenpocket.agent.core.forge.ForgedScreenState
import com.aiopenpocket.i18n.tr
import com.aiopenpocket.ui.agents.builder.components.BuilderTextField
import com.aiopenpocket.ui.components.glass.GlassCard

/**
 * "Cloud-forged screen": the section of the agent's card where the person asks for a screen, follows
 * the job, answers the agent's question and finally sees the screen itself, rendered from the
 * downloaded bundle. Stateless like the other sections; every action goes up to the ViewModel.
 *
 * [renderBundle] is a slot on purpose: in the app it is `ForgedKetoyScreen` (the Ketoy interpreter),
 * in previews and screenshot tests it is a stand-in card - Robolectric has no VM to run the bundle,
 * and the section's own layout is what those tests guard.
 */
@Composable
fun ForgedScreenSection(
  state: ForgedScreenState,
  bundle: ByteArray?,
  draft: String,
  configured: Boolean,
  onDraftChange: (String) -> Unit,
  onBuild: () -> Unit,
  onAnswer: (String) -> Unit,
  onUpdate: () -> Unit,
  onRemove: () -> Unit,
  renderBundle: @Composable (ForgedScreenState.Ready, ByteArray) -> Unit,
  modifier: Modifier = Modifier,
  /** Debug builds only: renders the bundled sample without a gateway. Null hides the chip. */
  onLoadSample: (() -> Unit)? = null,
  /**
   * Crash sentinel hooks (see `ForgedScreenStore.markRendering`): [onRenderStarted] runs synchronously
   * in composition right before the bundle composes, [onRenderStopped] when it leaves composition.
   */
  onRenderStarted: (String) -> Unit = {},
  onRenderStopped: () -> Unit = {},
) {
  Column(modifier = modifier.fillMaxWidth()) {
    AgentSectionHeader(
      icon = Icons.Default.Cloud,
      label = tr("Экран из облака", "Cloud-forged screen"),
      trailing = (state as? ForgedScreenState.Ready)?.let { tr("версия ${it.version}", "version ${it.version}") },
    )
    Spacer(modifier = Modifier.height(10.dp))
    when (state) {
      ForgedScreenState.None -> RequestCard(
        draft = draft,
        configured = configured,
        onDraftChange = onDraftChange,
        onSubmit = onBuild,
        submitLabel = tr("Собрать экран", "Build screen"),
        placeholder = tr(
          "Какой экран нужен агенту? Например: трекер воды на 8 стаканов со сбросом",
          "What screen should this agent get? e.g. a water tracker with 8 glasses and reset",
        ),
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

      is ForgedScreenState.NeedsInfo -> QuestionCard(
        question = state.question,
        onAnswer = onAnswer,
        onCancel = onRemove,
      )

      is ForgedScreenState.Ready -> {
        // Before the bundle composes, not after: an interpreter exception in the first composition
        // kills the process, and the flag is what turns the next launch into a failure card.
        remember(state.jobId) { onRenderStarted(state.jobId); Unit }
        // The interpreter fails in the first composition when it fails at all. Two drawn frames later
        // the screen counts as alive and the flag comes down - otherwise a process killed while the
        // card is open (a reinstall, a swipe from recents) would read as a crash on the next launch.
        LaunchedEffect(state.jobId) {
          withFrameNanos { }
          withFrameNanos { }
          onRenderStopped()
        }
        DisposableEffect(state.jobId) { onDispose { onRenderStopped() } }
        ReadyCard(
          state = state,
          bundle = bundle,
          draft = draft,
          configured = configured,
          onDraftChange = onDraftChange,
          onUpdate = onUpdate,
          onRemove = onRemove,
          renderBundle = renderBundle,
        )
      }

      is ForgedScreenState.Failed -> FailedCard(
        message = state.message,
        configured = configured,
        onRetry = onBuild,
        onRemove = onRemove,
      )
    }
  }
}

@Composable
private fun RequestCard(
  draft: String,
  configured: Boolean,
  onDraftChange: (String) -> Unit,
  onSubmit: () -> Unit,
  submitLabel: String,
  placeholder: String,
  onLoadSample: (() -> Unit)?,
) {
  GlassCard(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text(
        text = tr(
          "Опиши экран — агент в облаке напишет его на Compose, кузница соберёт, приложение покажет здесь. Без обновления приложения.",
          "Describe a screen - an agent in the cloud writes it in Compose, the forge compiles it, and it shows up here. No app update.",
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
      )
      BuilderTextField(
        value = draft,
        onValueChange = onDraftChange,
        placeholder = placeholder,
        minLines = 2,
        maxLines = 4,
        modifier = Modifier.fillMaxWidth().testTag("forged_request_field"),
      )
      if (!configured) {
        Text(
          text = tr("Укажи адрес кузницы и токен в Настройках.", "Set the forge address and token in Settings."),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
          modifier = Modifier.testTag("forged_not_configured"),
        )
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(
          onClick = onSubmit,
          enabled = configured && draft.isNotBlank(),
          modifier = Modifier.testTag("forged_build"),
        ) {
          Text(submitLabel)
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

@Composable
private fun ProgressCard(text: String, request: String, onCancel: () -> Unit) {
  GlassCard(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(modifier = Modifier.width(12.dp))
        Text(
          text = text,
          style = MaterialTheme.typography.bodyMedium,
          modifier = Modifier.testTag("forged_status"),
        )
      }
      Text(
        text = "“$request”",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
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
  onUpdate: () -> Unit,
  onRemove: () -> Unit,
  renderBundle: @Composable (ForgedScreenState.Ready, ByteArray) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
      // A fixed height on purpose: the section sits inside the card's vertical scroll, and a guest
      // screen that scrolls itself must not fight it. 420dp fits a phone's screen with the header.
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .height(420.dp)
          .clip(RoundedCornerShape(16.dp))
          .testTag("forged_screen"),
      ) {
        if (bundle != null) {
          // A new job is a new screen: key on the job so the interpreter is recreated, not recomposed
          // with the previous bundle's state (the entry point name is usually the same).
          key(state.jobId) { renderBundle(state, bundle) }
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
        Text(
          text = "“${state.request}”",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        BuilderTextField(
          value = draft,
          onValueChange = onDraftChange,
          placeholder = tr("Что изменить? Например: сделай стаканы синими", "What should change? e.g. make the glasses blue"),
          minLines = 1,
          maxLines = 3,
          modifier = Modifier.fillMaxWidth().testTag("forged_request_field"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Button(
            onClick = onUpdate,
            enabled = configured && draft.isNotBlank(),
            modifier = Modifier.testTag("forged_update"),
          ) {
            Text(tr("Обновить", "Update"))
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
