package com.aiopenpocket.ui.agents.builder.components

// @trace user-agent-builder-848153

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.aiopenpocket.agent.core.agent.ValidationIssue
import com.aiopenpocket.agent.core.llm.AgentModel
import com.aiopenpocket.agent.loop.AgentMode
import com.aiopenpocket.i18n.tr
import com.aiopenpocket.ui.agents.builder.AgentDraft

/**
 * Every [AgentMode] a custom agent can be started in from the wizard. [AgentMode.TUTOR] is
 * deliberately excluded — it is a lesson-internal, code-driven mode (mirrors
 * [com.aiopenpocket.agent.core.agent.MiniAgent.toDefinition]'s own preset scope), never something a
 * user picks for their own agent.
 */
private val SELECTABLE_MODES = listOf(AgentMode.CHAT, AgentMode.PLAN, AgentMode.GOAL, AgentMode.REFLECT)

/** Step 4/6: which loop modes this agent can run in, and which one a new chat with it opens in. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LoopModesStep(
  draft: AgentDraft,
  errors: List<ValidationIssue>,
  onUpdateDraft: ((AgentDraft) -> AgentDraft) -> Unit,
  modifier: Modifier = Modifier,
  /**
   * Paddings for the wizard's floating glass islands (their heights are measured in AgentBuilderScreen).
   * Applied AFTER verticalScroll — then the content scrolls UNDER the glass and there is something for
   * it to blur. A viewport inset from outside (through [modifier]) would do the opposite: the canvas does
   * not slide under the islands and the blur samples only the aurora.
   */
  contentPadding: PaddingValues = PaddingValues(),
) {
  Column(
    modifier = modifier
      .verticalScroll(rememberScrollState())
      .padding(contentPadding)
      .padding(horizontal = 20.dp, vertical = 16.dp),
  ) {
    Text(text = tr("Режимы работы", "Modes"), style = MaterialTheme.typography.headlineSmall)
    Text(
      text = tr(
        "В каких режимах агент может отвечать, и какой запускается по умолчанию.",
        "Which modes the agent can answer in, and which one starts by default.",
      ),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(top = 6.dp, bottom = 20.dp),
    )

    ModesFields(draft = draft, errors = errors, onUpdateDraft = onUpdateDraft)

    Spacer(modifier = Modifier.height(24.dp))
    Text(text = tr("Модель", "Model"), style = MaterialTheme.typography.titleMedium)
    Text(
      text = tr(
        "На какой модели Muse работает этот агент. Если выбранной модели нет на вашем маршруте, агент работает на модели приложения.",
        "Which Muse model this agent runs on. If your route doesn't offer it, the agent falls back to the app's model.",
      ),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
    )
    ModelChips(draft = draft, errors = errors, onUpdateDraft = onUpdateDraft)

    Spacer(modifier = Modifier.height(20.dp))
    Text(
      text = tr(
        "Режим по умолчанию — тот, в котором открывается новый чат с этим агентом.",
        "The default mode is the one a new chat with this agent opens in.",
      ),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

/**
 * Allowed modes and the default mode as chips — the first half of [LoopModesStep], also a section of the
 * review screen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ModesFields(
  draft: AgentDraft,
  errors: List<ValidationIssue>,
  onUpdateDraft: ((AgentDraft) -> AgentDraft) -> Unit,
) {
  Column {
    Text(text = tr("Разрешённые режимы", "Allowed modes"), style = MaterialTheme.typography.titleMedium)
    Spacer(modifier = Modifier.height(8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      SELECTABLE_MODES.forEach { mode ->
        val selected = mode in draft.allowedModes
        FilterChip(
          selected = selected,
          onClick = {
            onUpdateDraft { d ->
              val updated = if (selected) d.allowedModes - mode else d.allowedModes + mode
              // allowedModes is never left empty — otherwise there would be nothing to pick as the default.
              if (updated.isEmpty()) d else d.copy(allowedModes = updated)
            }
          },
          label = { Text(modeLabel(mode)) },
        )
      }
    }
    // No ErrorText("allowedModes") here: AgentValidator produces no issue with that field — the only rule
    // about modes (default_mode_not_allowed) is tagged "defaultMode", which is what is shown below.

    Spacer(modifier = Modifier.height(20.dp))
    Text(text = tr("Режим по умолчанию", "Default mode"), style = MaterialTheme.typography.titleMedium)
    Spacer(modifier = Modifier.height(8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      SELECTABLE_MODES.forEach { mode ->
        FilterChip(
          selected = draft.defaultMode == mode,
          onClick = {
            // Choosing the default always keeps it inside allowedModes — otherwise we would hit
            // AgentValidator.default_mode_not_allowed out of nowhere.
            onUpdateDraft { it.copy(defaultMode = mode, allowedModes = it.allowedModes + mode) }
          },
          label = { Text(modeLabel(mode)) },
        )
      }
    }
    ErrorText(errors, "defaultMode")
  }
}

/**
 * The model chips (Wonder / Spark / app default) with their inline error — the last block of
 * [LoopModesStep], also a section of the review screen, where they must be visible without a tap.
 */
@Composable
internal fun ModelChips(
  draft: AgentDraft,
  errors: List<ValidationIssue>,
  onUpdateDraft: ((AgentDraft) -> AgentDraft) -> Unit,
) {
  Column {
    // Built per call, not as a static list: tr() must run when the text is shown.
    val modelOptions = listOf<Pair<String?, String>>(
      null to tr("Модель приложения", "App default"),
      AgentModel.WONDER.key to tr(
        "Muse Wonder — сильнейшая, для долгой работы в несколько шагов",
        "Muse Wonder — strongest, for long multi-step work",
      ),
      AgentModel.SPARK.key to tr("Muse Spark — быстрее и дешевле", "Muse Spark — faster and cheaper"),
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
      modelOptions.forEach { (key, label) ->
        FilterChip(
          selected = draft.model == key,
          onClick = { onUpdateDraft { it.copy(model = key) } },
          label = { Text(label) },
          modifier = Modifier.testTag("builder_model_${key ?: "default"}"),
        )
      }
    }
    ErrorText(errors, "model")
  }
}

/** Short model name for read-only displays (the agent inspector); the picker uses the long labels. */
fun agentModelShortLabel(key: String?): String = when (AgentModel.fromKey(key)) {
  AgentModel.WONDER -> "Muse Wonder"
  AgentModel.SPARK -> "Muse Spark"
  null -> tr("Модель приложения", "App default")
}

private fun modeLabel(mode: AgentMode): String = when (mode) {
  AgentMode.CHAT -> tr("Чат", "Chat")
  AgentMode.PLAN -> tr("План", "Plan")
  AgentMode.GOAL -> tr("Цель", "Goal")
  AgentMode.REFLECT -> tr("Рефлексия", "Reflect")
  AgentMode.TUTOR -> tr("Репетитор", "Tutor")
}
