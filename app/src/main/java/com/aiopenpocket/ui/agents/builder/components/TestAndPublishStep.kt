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
  /** Ответ тестового хода — `AgentBuilderTestViewModel.testReplyText` (не только стрим: см. там). */
  testStreamingText: String,
  testToolTrace: List<ToolTrace>,
  onSetExecutionMode: (ExecutionMode) -> Unit,
  onSendTestMessage: (String) -> Unit,
  onPublish: () -> Unit,
  onArchive: () -> Unit,
  /** Только что успешно опубликовано — показываем подтверждение (см. `AgentBuilderUiState.justPublished`). */
  justPublished: Boolean = false,
  /** Хук «Требовать подтверждение» ждёт решения в тестовом ходе; null — ничего не ждёт. */
  testPendingApproval: ToolApprovalRequest? = null,
  onResolveTestApproval: (approved: Boolean) -> Unit = {},
  /** «Открыть чат» в подтверждении публикации; null — кнопки нет. */
  onOpenChat: (() -> Unit)? = null,
  modifier: Modifier = Modifier,
  /**
   * Отступы под плавающие стеклянные острова визарда (высоты меряются в AgentBuilderScreen).
   * Применяются ПОСЛЕ verticalScroll — тогда контент прокручивается ПОД стеклом и его есть
   * чему размывать. Инсет вьюпорта снаружи (через [modifier]) дал бы обратное: полотно под
   * острова не заезжает и блюр сэмплит одну аврору.
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
    TestRunStatus(testStatus, testStreamingText)

    // Тот же бар подтверждения, что в чате. Хук «Требовать подтверждение» ставит вызов на ожидание
    // и в предпросмотре, а без бара здесь тестовый ход молча висел две минуты до таймаута.
    testPendingApproval?.let { request ->
      Spacer(modifier = Modifier.height(8.dp))
      ToolApprovalBar(
        request = request,
        onApprove = { onResolveTestApproval(true) },
        onDeny = { onResolveTestApproval(false) },
      )
    }

    // Trace прогона (спека 11.2): без него предпросмотр и живой тест выглядят для юзера одинаково,
    // хотя весь смысл предпросмотра — увидеть, за какие инструменты агент взялся и что ему вернули
    // (включая синтетические ответы Simulate вместо реального побочного эффекта).
    if (testToolTrace.isNotEmpty()) {
      Spacer(modifier = Modifier.height(12.dp))
      ToolTracePanel(traces = testToolTrace, modifier = Modifier.testTag("agent_builder_tool_trace"))
    }

    Spacer(modifier = Modifier.height(24.dp))
    HorizontalDivider()
    Spacer(modifier = Modifier.height(16.dp))

    Text(text = tr("Публикация", "Publishing"), style = MaterialTheme.typography.titleMedium)
    Spacer(modifier = Modifier.height(8.dp))
    // Подтверждение успеха: без него «Опубликовать» просто переставала крутиться, и было непонятно,
    // ушёл ли агент в ростер и куда идти дальше.
    val publishedUnchanged = justPublished && draft.status == AgentStatus.PUBLISHED
    if (publishedUnchanged) {
      PublishedNotice(configVersion = draft.configVersion, onOpenChat = onOpenChat)
      Spacer(modifier = Modifier.height(12.dp))
    }
    // Не просто «есть ошибки», а какие именно и на каком шаге: иначе с последнего шага непонятно,
    // куда возвращаться, — а ошибка вполне может сидеть на первом.
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
    // Сразу после публикации публиковать нечего: «Опубликовать изменения» под «Агент опубликован»
    // выглядела как недоделка. Любая правка гасит justPublished — и кнопка возвращается.
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

    // Архивирование доступно и черновику: иначе брошенный черновик остаётся в секции «Черновики»
    // навсегда и убрать его оттуда нечем. Смысл действия один и тот же (строка выживает, из списков
    // уходит) — поэтому и черновику кнопка обещает архив: прежнее «Удалить черновик» врало, строка
    // в базе оставалась.
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

/** Плашка «опубликовано» с версией и, если маршрут умеет, переходом в чат агента. */
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

/** [replyText] — ответ хода: стрим или записанное в диалог сообщение (у ходов «План»/«Цель» только оно). */
@Composable
private fun TestRunStatus(status: TurnStatus?, replyText: String) {
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

    is TurnStatus.Failed -> Text(
      text = status.message,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.error,
    )
  }
}
