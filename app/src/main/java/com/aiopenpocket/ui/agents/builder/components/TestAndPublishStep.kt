package com.aiopenpocket.ui.agents.builder.components

// @trace user-agent-builder-848153

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.aiopenpocket.agent.core.agent.AgentStatus
import com.aiopenpocket.agent.core.agent.ValidationIssue
import com.aiopenpocket.agent.core.policy.ExecutionMode
import com.aiopenpocket.agent.core.policy.ToolApprovalRequest
import com.aiopenpocket.agent.loop.ToolTrace
import com.aiopenpocket.agent.loop.TurnStatus
import com.aiopenpocket.i18n.tr
import com.aiopenpocket.ui.chat.components.ToolApprovalBar
import com.aiopenpocket.ui.chat.components.ToolTracePanel
import com.aiopenpocket.ui.agents.builder.AgentBuilderValidator
import com.aiopenpocket.ui.agents.builder.AgentDraft
import com.aiopenpocket.ui.agents.builder.BuilderStep
import com.aiopenpocket.ui.components.glass.glassCardSurface

/**
 * Step 6/6: run the draft against the real [com.aiopenpocket.agent.loop.TurnManager] (in
 * [ExecutionMode.PREVIEW] by default, or [ExecutionMode.LIVE] behind an explicit toggle), then
 * publish. [testStatus]/[testStreamingText] come from
 * [com.aiopenpocket.ui.agents.builder.AgentBuilderTestViewModel] — this composable never talks to
 * `TurnManager`/`ConversationRepository` itself.
 */
@Composable
fun TestAndPublishStep(
  draft: AgentDraft,
  executionMode: ExecutionMode,
  canPublish: Boolean,
  /** EVERY current issue, across all six steps — not just this step's. Rendered as the publish-gate summary. */
  errors: List<ValidationIssue>,
  isSaving: Boolean,
  testStatus: TurnStatus?,
  /** The test turn's answer — `AgentBuilderTestViewModel.testReplyText` (not only the stream: see there). */
  testStreamingText: String,
  testToolTrace: List<ToolTrace>,
  onSetExecutionMode: (ExecutionMode) -> Unit,
  onSendTestMessage: (String) -> Unit,
  onPublish: () -> Unit,
  onArchive: () -> Unit,
  /** Just published successfully — the confirmation is shown (see `AgentBuilderUiState.justPublished`). */
  justPublished: Boolean = false,
  /** A "Require confirmation" hook is waiting for a decision in the test turn; null — nothing is waiting. */
  testPendingApproval: ToolApprovalRequest? = null,
  onResolveTestApproval: (requestId: String, approved: Boolean) -> Unit = { _, _ -> },
  /** "Open chat" in the publish confirmation; null — no button. */
  onOpenChat: (() -> Unit)? = null,
  /** "Open Settings" under the no-key message of a test run; null — no button. */
  onOpenSettings: (() -> Unit)? = null,
  modifier: Modifier = Modifier,
  /**
   * Paddings for the wizard's floating glass islands (their heights are measured in AgentBuilderScreen).
   * Applied AFTER verticalScroll — then the content scrolls UNDER the glass and there is something for
   * it to blur. A viewport inset from outside (through [modifier]) would do the opposite: the canvas does
   * not slide under the islands and the blur samples only the aurora.
   */
  contentPadding: PaddingValues = PaddingValues(),
) {
  var testInput by remember { mutableStateOf("") }

  Column(
    modifier = modifier
      .verticalScroll(rememberScrollState())
      .padding(contentPadding)
      .padding(horizontal = 20.dp, vertical = 16.dp),
  ) {
    Text(text = tr("Проверка и публикация", "Test and publish"), style = MaterialTheme.typography.headlineSmall)
    Text(
      text = tr(
        "Отправь тестовое сообщение, посмотри, как отвечает агент, и опубликуй его.",
        "Send a test message, see how the agent replies, then publish it.",
      ),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(top = 6.dp, bottom = 16.dp),
    )

    Text(text = tr("Режим проверки", "Test mode"), style = MaterialTheme.typography.titleMedium)
    Spacer(modifier = Modifier.height(8.dp))
    Row {
      FilterChip(
        selected = executionMode == ExecutionMode.PREVIEW,
        onClick = { onSetExecutionMode(ExecutionMode.PREVIEW) },
        label = { Text(tr("Предпросмотр", "Preview")) },
        modifier = Modifier.testTag("agent_builder_preview"),
      )
      Spacer(modifier = Modifier.width(8.dp))
      FilterChip(
        selected = executionMode == ExecutionMode.LIVE,
        onClick = { onSetExecutionMode(ExecutionMode.LIVE) },
        label = { Text(tr("Живой тест", "Live test")) },
        modifier = Modifier.testTag("agent_builder_live_test"),
      )
    }
    Spacer(modifier = Modifier.height(4.dp))
    Text(
      text = if (executionMode == ExecutionMode.LIVE) {
        tr(
          "Живой тест выполняет реальные действия (календарь, файлы, письма и т.д.) по-настоящему.",
          "A live test really performs actions (calendar, files, email and so on).",
        )
      } else {
        tr(
          "В предпросмотре инструменты с побочными эффектами не выполняются по-настоящему — " +
            "агент получает синтетический результат.",
          "In preview, tools with side effects don't actually run — " +
            "the agent gets a simulated result.",
        )
      },
      style = MaterialTheme.typography.bodySmall,
      color = if (executionMode == ExecutionMode.LIVE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(modifier = Modifier.height(16.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
      BuilderTextField(
        value = testInput,
        onValueChange = { testInput = it },
        label = tr("Тестовое сообщение", "Test message"),
        modifier = Modifier.weight(1f),
      )
      Spacer(modifier = Modifier.width(8.dp))
      Button(
        onClick = {
          if (testInput.isNotBlank()) {
            onSendTestMessage(testInput)
            testInput = ""
          }
        },
      ) { Text(tr("Отправить", "Send")) }
    }

    Spacer(modifier = Modifier.height(16.dp))
    TestRunStatus(testStatus, testStreamingText, onOpenSettings)

    // The same confirmation bar as in the chat. A "Require confirmation" hook puts a call on hold in
    // preview too, and without the bar here the test turn silently hung for two minutes until the timeout.
    testPendingApproval?.let { request ->
      Spacer(modifier = Modifier.height(8.dp))
      ToolApprovalBar(
        request = request,
        onApprove = { onResolveTestApproval(request.requestId, true) },
        onDeny = { onResolveTestApproval(request.requestId, false) },
      )
    }

    // The run's trace (spec 11.2): without it a preview and a live test look the same to the user, although
    // the whole point of a preview is to see which tools the agent reached for and what it got back
    // (including the synthetic Simulate answers instead of a real side effect).
    if (testToolTrace.isNotEmpty()) {
      Spacer(modifier = Modifier.height(12.dp))
      ToolTracePanel(traces = testToolTrace, modifier = Modifier.testTag("agent_builder_tool_trace"))
    }

    Spacer(modifier = Modifier.height(24.dp))
    HorizontalDivider()
    Spacer(modifier = Modifier.height(16.dp))

    Text(text = tr("Публикация", "Publishing"), style = MaterialTheme.typography.titleMedium)
    Spacer(modifier = Modifier.height(8.dp))
    // A success confirmation: without it "Publish" simply stopped spinning, and it was unclear whether the
    // agent had gone to the roster and where to go next.
    val publishedUnchanged = justPublished && draft.status == AgentStatus.PUBLISHED
    if (publishedUnchanged) {
      PublishedNotice(configVersion = draft.configVersion, onOpenChat = onOpenChat)
      Spacer(modifier = Modifier.height(12.dp))
    }
    // Not just "there are errors", but which ones and on which step: otherwise from the last step it is
    // unclear where to go back to — and the error may well be on the first one.
    if (errors.isNotEmpty()) {
      Column(modifier = Modifier.padding(bottom = 8.dp)) {
        Text(
          text = tr("Нельзя опубликовать, пока не исправлено:", "Can't publish until these are fixed:"),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
        )
        errors.forEach { issue ->
          Text(
            text = "• ${stepTitle(AgentBuilderValidator.stepFor(issue))}: ${issue.message}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 2.dp),
          )
        }
      }
    }
    // Right after publishing there is nothing to publish: "Publish changes" under "Agent published" looked
    // unfinished. Any edit clears justPublished — and the button comes back.
    if (!publishedUnchanged) {
      Button(
        onClick = onPublish,
        enabled = canPublish && !isSaving,
        modifier = Modifier.testTag("agent_builder_publish"),
      ) {
        if (isSaving) {
          CircularProgressIndicator(modifier = Modifier.size(16.dp).padding(end = 8.dp), strokeWidth = 2.dp)
        }
        Text(
          if (draft.status == AgentStatus.PUBLISHED) {
            tr("Опубликовать изменения", "Publish changes")
          } else {
            tr("Опубликовать", "Publish")
          },
        )
      }
    }

    // Archiving is available for a draft too: otherwise an abandoned draft stays in the "Drafts" section
    // forever with nothing to remove it. The meaning of the action is the same (the row survives, it
    // leaves the lists) — so for a draft the button promises an archive too: the old "Delete draft" lied,
    // the row stayed in the database.
    if (draft.status != AgentStatus.ARCHIVED) {
      Spacer(modifier = Modifier.height(8.dp))
      TextButton(onClick = onArchive, modifier = Modifier.testTag("agent_builder_archive")) {
        Text(
          text = if (draft.status == AgentStatus.PUBLISHED) {
            tr("Архивировать агента", "Archive agent")
          } else {
            tr("Архивировать черновик", "Archive draft")
          },
          color = MaterialTheme.colorScheme.error,
        )
      }
    }
  }
}

/** The "published" badge with the version and, if the route can, a way into the agent's chat. */
@Composable
private fun PublishedNotice(configVersion: Long, onOpenChat: (() -> Unit)?) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .glassCardSurface()
      .padding(12.dp)
      .testTag("agent_builder_published"),
  ) {
    Text(
      text = tr(
        "Агент опубликован (версия $configVersion) — он уже в «Мои агенты».",
        "Agent published (version $configVersion) — it's already in “My agents”.",
      ),
      style = MaterialTheme.typography.bodyMedium,
    )
    if (onOpenChat != null) {
      Spacer(modifier = Modifier.height(8.dp))
      Button(onClick = onOpenChat, modifier = Modifier.testTag("agent_builder_open_chat")) {
        Text(tr("Открыть чат", "Open chat"))
      }
    }
  }
}

private fun stepTitle(step: BuilderStep): String = when (step) {
  BuilderStep.IDENTITY -> tr("Основа", "Basics")
  BuilderStep.INSTRUCTIONS -> tr("Инструкции", "Instructions")
  BuilderStep.CAPABILITIES -> tr("Возможности", "Capabilities")
  BuilderStep.LOOP -> tr("Режимы", "Modes")
  BuilderStep.HOOKS -> tr("Хуки", "Hooks")
  BuilderStep.TEST -> tr("Проверка", "Test")
}

/** [replyText] — the turn's answer: the stream or the message written to the conversation (for "Plan"/"Goal" turns only the latter). */
@Composable
private fun TestRunStatus(status: TurnStatus?, replyText: String, onOpenSettings: (() -> Unit)?) {
  when (status) {
    null -> Unit
    is TurnStatus.Running -> Row(verticalAlignment = Alignment.CenterVertically) {
      CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
      Spacer(modifier = Modifier.width(8.dp))
      Text(text = tr("Агент отвечает…", "Agent is replying…"), style = MaterialTheme.typography.bodyMedium)
    }

    is TurnStatus.Completed -> Column(modifier = Modifier.fillMaxWidth().glassCardSurface().padding(12.dp)) {
      Text(
        text = replyText.ifBlank { tr("Пустой ответ.", "Empty reply.") },
        style = MaterialTheme.typography.bodyMedium,
      )
      status.error?.let {
        Text(
          text = it,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
          modifier = Modifier.padding(top = 6.dp),
        )
      }
    }

    is TurnStatus.Failed -> Column {
      Text(
        text = status.message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
      )
      // The dry run has no model to talk to without the person's Meta key; the way out is Settings.
      if (status.needsKey && onOpenSettings != null) {
        TextButton(onClick = onOpenSettings) { Text(tr("Открыть настройки", "Open Settings")) }
      }
    }
  }
}
