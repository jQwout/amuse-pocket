package com.aiopenpocket.ui.agents.builder.components

// @trace user-agent-builder-848153
// @trace structured-plan-engine-907066

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aiopenpocket.agent.core.hook.AgentHook
import com.aiopenpocket.agent.core.hook.HookActionType
import com.aiopenpocket.agent.core.hook.HookEngine
import com.aiopenpocket.agent.core.hook.HookEvent
import com.aiopenpocket.agent.loop.AgentMode
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Mirrors [HookEngine]'s file-private `ToolNameCondition` shape (`{"names": [...]}`) structurally —
 * kotlinx.serialization matches by JSON field name, not by class identity, so encoding this LOCAL
 * copy produces exactly what [HookEngine.conditionMatches] decodes at runtime. Duplicated because the
 * real type is `private` to `HookEngine.kt` and out of this task's file scope.
 */
@Serializable
private data class ToolNameConditionDto(val names: Set<String> = emptySet())

/** Mirrors [HookEngine]'s file-private `TextPayload` shape (`{"text": "..."}`) — see [ToolNameConditionDto]. */
@Serializable
private data class TextPayloadDto(val text: String = "")

/** Plan-lifecycle events (Structured Plan Engine Task 9) — only reachable for an agent that can run PLAN/GOAL. */
private val PLAN_EVENTS = setOf(
  HookEvent.ON_PLAN_CREATED,
  HookEvent.ON_PLAN_APPROVED,
  HookEvent.BEFORE_PLAN_STEP,
  HookEvent.AFTER_PLAN_STEP,
  HookEvent.ON_PLAN_BLOCKED,
  HookEvent.ON_PLAN_COMPLETED,
)

/**
 * Add/edit dialog for one [AgentHook] (Task 20 plan Step 5): only offers
 * [HookEngine.actionsAllowedFor]-compatible actions for the chosen [HookEvent], and NEVER shows
 * [AgentHook.conditionJson]/[AgentHook.actionJson] as raw text — both are built from/parsed into the
 * typed fields below.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HookEditorDialog(
  agentId: String,
  existing: AgentHook?,
  agentToolNames: Set<String>,
  allowedModes: Set<AgentMode>,
  onSave: (AgentHook) -> Unit,
  onDismiss: () -> Unit,
) {
  // Плановые события агенту без PLAN/GOAL предлагать нечего — они у него не наступят никогда, и
  // хук на них выглядел бы как настроенное, но молча мёртвое правило (design spec 12.2).
  val plansEnabled = AgentMode.PLAN in allowedModes || AgentMode.GOAL in allowedModes
  val selectableEvents = HookEvent.entries.filter { plansEnabled || it !in PLAN_EVENTS || it == existing?.event }

  var event by remember { mutableStateOf(existing?.event ?: HookEvent.BEFORE_TOOL) }
  var actionType by remember { mutableStateOf(existing?.actionType ?: HookActionType.LOG) }
  var scopedToTools by remember { mutableStateOf(existing?.let(::parseScopedToolNames) ?: emptySet()) }
  var payloadText by remember { mutableStateOf(existing?.let(::parsePayloadText) ?: "") }
  var priority by remember { mutableStateOf(existing?.priority ?: 0) }
  var enabled by remember { mutableStateOf(existing?.enabled ?: true) }

  // Derived, never written back to `actionType` itself: switching `event` can make the previously
  // chosen action invalid for it (e.g. REQUIRE_APPROVAL only makes sense for BEFORE_TOOL) — falling
  // back to the first still-allowed action here (a plain computed value) avoids assigning to
  // MutableState during composition, which a `remember(event) { actionType = ... }`-style fix-up would do.
  val allowedActions = HookEngine.actionsAllowedFor(event).toList()
  val effectiveActionType = if (actionType in allowedActions) actionType else allowedActions.first()

  val supportsToolCondition = event == HookEvent.BEFORE_TOOL || event == HookEvent.AFTER_TOOL
  val needsPayload = effectiveActionType == HookActionType.ADD_INSTRUCTION || effectiveActionType == HookActionType.LOG
  val canSave = !needsPayload || payloadText.isNotBlank()

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(if (existing == null) "Новый хук" else "Хук") },
    text = {
      Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        Text(text = "Когда", style = MaterialTheme.typography.labelLarge)
        Spacer(modifier = Modifier.height(4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          selectableEvents.forEach { candidate ->
            FilterChip(
              selected = event == candidate,
              onClick = { event = candidate },
              label = { Text(hookEventLabel(candidate)) },
            )
          }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "Действие", style = MaterialTheme.typography.labelLarge)
        Spacer(modifier = Modifier.height(4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          allowedActions.forEach { candidate ->
            FilterChip(
              selected = effectiveActionType == candidate,
              onClick = { actionType = candidate },
              label = { Text(hookActionLabel(candidate)) },
            )
          }
        }

        if (supportsToolCondition) {
          Spacer(modifier = Modifier.height(16.dp))
          Text(text = "Условие", style = MaterialTheme.typography.labelLarge)
          Text(
            text = "Пусто — сработает для любого инструмента агента. Иначе — только для выбранных:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Spacer(modifier = Modifier.height(4.dp))
          if (agentToolNames.isEmpty()) {
            Text(
              text = "У агента пока нет ни одного инструмента — сработает для любого.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
              agentToolNames.sorted().forEach { name ->
                FilterChip(
                  selected = name in scopedToTools,
                  onClick = { scopedToTools = if (name in scopedToTools) scopedToTools - name else scopedToTools + name },
                  label = { Text(name) },
                )
              }
            }
          }
        }

        if (needsPayload) {
          Spacer(modifier = Modifier.height(16.dp))
          BuilderTextField(
            value = payloadText,
            onValueChange = { payloadText = it },
            label = if (effectiveActionType == HookActionType.ADD_INSTRUCTION) "Текст инструкции" else "Текст в лог",
            minLines = 2,
            maxLines = 4,
          )
        }

        Spacer(modifier = Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(text = "Приоритет", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
          IconButton(onClick = { priority -= 1 }) {
            Icon(imageVector = Icons.Default.Remove, contentDescription = "Ниже приоритет")
          }
          Text(text = "$priority", style = MaterialTheme.typography.titleMedium)
          IconButton(onClick = { priority += 1 }) {
            Icon(imageVector = Icons.Default.Add, contentDescription = "Выше приоритет")
          }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(text = "Включён", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
          Switch(checked = enabled, onCheckedChange = { enabled = it })
        }
      }
    },
    confirmButton = {
      TextButton(
        enabled = canSave,
        onClick = {
          val conditionType = if (supportsToolCondition && scopedToTools.isNotEmpty()) "tool_name_in" else "always"
          val conditionJson = if (conditionType == "tool_name_in") {
            Json.encodeToString(ToolNameConditionDto(scopedToTools))
          } else {
            "{}"
          }
          val actionJson = if (needsPayload) Json.encodeToString(TextPayloadDto(payloadText)) else "{}"
          onSave(
            AgentHook(
              id = existing?.id ?: UUID.randomUUID().toString(),
              agentId = agentId,
              event = event,
              conditionType = conditionType,
              conditionJson = conditionJson,
              actionType = effectiveActionType,
              actionJson = actionJson,
              priority = priority,
              enabled = enabled,
            ),
          )
        },
      ) { Text("Сохранить") }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("Отмена") }
    },
  )
}

private fun parseScopedToolNames(hook: AgentHook): Set<String> =
  if (hook.conditionType == "tool_name_in") {
    runCatching { Json.decodeFromString<ToolNameConditionDto>(hook.conditionJson).names }.getOrDefault(emptySet())
  } else {
    emptySet()
  }

private fun parsePayloadText(hook: AgentHook): String =
  if (hook.actionType == HookActionType.ADD_INSTRUCTION || hook.actionType == HookActionType.LOG) {
    runCatching { Json.decodeFromString<TextPayloadDto>(hook.actionJson).text }.getOrDefault("")
  } else {
    ""
  }

/** Human summary of an [AgentHook]'s condition, for [HooksStep]'s list — never the raw JSON. */
internal fun hookConditionSummary(hook: AgentHook): String =
  if (hook.conditionType == "tool_name_in") {
    val names = parseScopedToolNames(hook)
    if (names.isEmpty()) "любой инструмент" else "инструменты: ${names.sorted().joinToString(", ")}"
  } else {
    "всегда"
  }

/**
 * Task 9 (Structured Plan Engine) adds the six plan-lifecycle [HookEvent]s here so this exhaustive
 * `when` keeps compiling and every event is selectable in the picker above — the `FilterChip` loop
 * already iterates ALL [HookEvent.entries] and the action picker already derives from
 * [HookEngine.actionsAllowedFor], so both just work once this label exists. NOT done here (design
 * spec section 12.2 / plan Task 9 Step 4's "hide plan events when both PLAN and GOAL are absent from
 * `AgentDefinition.allowedModes`"): this dialog has no `allowedModes` parameter today, and threading
 * it through (`HookEditorDialog` → `HooksStep` → the builder wizard's in-progress `AgentDefinition`)
 * is a separate, larger UI change than this task's engine/firing scope — flagged as a follow-up, not
 * silently dropped.
 */
internal fun hookEventLabel(event: HookEvent): String = when (event) {
  HookEvent.BEFORE_TURN -> "Перед ходом"
  HookEvent.AFTER_TURN -> "После хода"
  HookEvent.BEFORE_TOOL -> "Перед инструментом"
  HookEvent.AFTER_TOOL -> "После инструмента"
  HookEvent.ON_ERROR -> "При ошибке"
  HookEvent.ON_COMPLETE -> "По завершении"
  HookEvent.ON_PLAN_CREATED -> "План создан"
  HookEvent.ON_PLAN_APPROVED -> "План одобрен"
  HookEvent.BEFORE_PLAN_STEP -> "Перед шагом плана"
  HookEvent.AFTER_PLAN_STEP -> "После шага плана"
  HookEvent.ON_PLAN_BLOCKED -> "План заблокирован"
  HookEvent.ON_PLAN_COMPLETED -> "План завершён"
}

internal fun hookActionLabel(action: HookActionType): String = when (action) {
  HookActionType.ADD_INSTRUCTION -> "Добавить инструкцию"
  HookActionType.REQUIRE_APPROVAL -> "Требовать подтверждение"
  HookActionType.ALLOW -> "Разрешить"
  HookActionType.DENY -> "Запретить"
  HookActionType.LOG -> "Записать в лог"
  HookActionType.STOP -> "Остановить"
}
