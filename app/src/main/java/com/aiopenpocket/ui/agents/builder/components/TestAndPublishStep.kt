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
import com.aiopenpocket.agent.loop.ToolTrace
import com.aiopenpocket.agent.loop.TurnStatus
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
  testStreamingText: String,
  testToolTrace: List<ToolTrace>,
  onSetExecutionMode: (ExecutionMode) -> Unit,
  onSendTestMessage: (String) -> Unit,
  onPublish: () -> Unit,
  onArchive: () -> Unit,
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
    Text(text = "Проверка и публикация", style = MaterialTheme.typography.headlineSmall)
    Text(
      text = "Отправь тестовое сообщение, посмотри, как отвечает агент, и опубликуй его.",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(top = 6.dp, bottom = 16.dp),
    )

    Text(text = "Режим проверки", style = MaterialTheme.typography.titleMedium)
    Spacer(modifier = Modifier.height(8.dp))
    Row {
      FilterChip(
        selected = executionMode == ExecutionMode.PREVIEW,
        onClick = { onSetExecutionMode(ExecutionMode.PREVIEW) },
        label = { Text("Предпросмотр") },
        modifier = Modifier.testTag("agent_builder_preview"),
      )
      Spacer(modifier = Modifier.width(8.dp))
      FilterChip(
        selected = executionMode == ExecutionMode.LIVE,
        onClick = { onSetExecutionMode(ExecutionMode.LIVE) },
        label = { Text("Живой тест") },
        modifier = Modifier.testTag("agent_builder_live_test"),
      )
    }
    Spacer(modifier = Modifier.height(4.dp))
    Text(
      text = if (executionMode == ExecutionMode.LIVE) {
        "Живой тест выполняет реальные действия (SMS, календарь, файлы и т.д.) по-настоящему."
      } else {
        "В предпросмотре инструменты с побочными эффектами не выполняются по-настоящему — " +
          "агент получает синтетический результат."
      },
      style = MaterialTheme.typography.bodySmall,
      color = if (executionMode == ExecutionMode.LIVE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(modifier = Modifier.height(16.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
      BuilderTextField(
        value = testInput,
        onValueChange = { testInput = it },
        label = "Тестовое сообщение",
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
      ) { Text("Отправить") }
    }

    Spacer(modifier = Modifier.height(16.dp))
    TestRunStatus(testStatus, testStreamingText)

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

    Text(text = "Публикация", style = MaterialTheme.typography.titleMedium)
    Spacer(modifier = Modifier.height(8.dp))
    // Не просто «есть ошибки», а какие именно и на каком шаге: иначе с последнего шага непонятно,
    // куда возвращаться, — а ошибка вполне может сидеть на первом.
    if (errors.isNotEmpty()) {
      Column(modifier = Modifier.padding(bottom = 8.dp)) {
        Text(
          text = "Нельзя опубликовать, пока не исправлено:",
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
    Button(
      onClick = onPublish,
      enabled = canPublish && !isSaving,
      modifier = Modifier.testTag("agent_builder_publish"),
    ) {
      if (isSaving) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp).padding(end = 8.dp), strokeWidth = 2.dp)
      }
      Text(if (draft.status == AgentStatus.PUBLISHED) "Опубликовать изменения" else "Опубликовать")
    }

    // Архивирование доступно и черновику: иначе брошенный черновик остаётся в секции «Черновики»
    // навсегда и убрать его оттуда нечем. Смысл действия один и тот же (строка выживает, из списков
    // уходит) — меняется только формулировка.
    if (draft.status != AgentStatus.ARCHIVED) {
      Spacer(modifier = Modifier.height(8.dp))
      TextButton(onClick = onArchive, modifier = Modifier.testTag("agent_builder_archive")) {
        Text(
          text = if (draft.status == AgentStatus.PUBLISHED) "Архивировать агента" else "Удалить черновик",
          color = MaterialTheme.colorScheme.error,
        )
      }
    }
  }
}

private fun stepTitle(step: BuilderStep): String = when (step) {
  BuilderStep.IDENTITY -> "Основа"
  BuilderStep.INSTRUCTIONS -> "Инструкции"
  BuilderStep.CAPABILITIES -> "Возможности"
  BuilderStep.LOOP -> "Режимы"
  BuilderStep.HOOKS -> "Хуки"
  BuilderStep.TEST -> "Проверка"
}

@Composable
private fun TestRunStatus(status: TurnStatus?, streamingText: String) {
  when (status) {
    null -> Unit
    is TurnStatus.Running -> Row(verticalAlignment = Alignment.CenterVertically) {
      CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
      Spacer(modifier = Modifier.width(8.dp))
      Text(text = "Агент отвечает…", style = MaterialTheme.typography.bodyMedium)
    }

    is TurnStatus.Completed -> Column(modifier = Modifier.fillMaxWidth().glassCardSurface().padding(12.dp)) {
      Text(
        text = streamingText.ifBlank { "Пустой ответ." },
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
