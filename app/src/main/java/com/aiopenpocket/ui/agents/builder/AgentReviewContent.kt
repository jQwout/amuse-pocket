package com.aiopenpocket.ui.agents.builder

// @trace user-agent-builder-848153

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.aiopenpocket.agent.core.hook.AgentHook
import com.aiopenpocket.agent.core.policy.ToolApprovalRequest
import com.aiopenpocket.agent.loop.ToolTrace
import com.aiopenpocket.agent.loop.TurnStatus
import com.aiopenpocket.agent.core.policy.ExecutionMode
import com.aiopenpocket.i18n.tr
import com.aiopenpocket.ui.agents.builder.components.CapabilitiesExtras
import com.aiopenpocket.ui.agents.builder.components.DryRunSection
import com.aiopenpocket.ui.agents.builder.components.ErrorText
import com.aiopenpocket.ui.agents.builder.components.IdentityFields
import com.aiopenpocket.ui.agents.builder.components.InstructionsFields
import com.aiopenpocket.ui.agents.builder.components.ModelChips
import com.aiopenpocket.ui.agents.builder.components.ModesFields
import com.aiopenpocket.ui.agents.builder.components.PublishBlock
import com.aiopenpocket.ui.agents.builder.components.ToolPicker
import com.aiopenpocket.ui.agents.builder.components.hookActionLabel
import com.aiopenpocket.ui.agents.builder.components.hookEventLabel

/**
 * The review screen: every part of the draft on ONE scrollable page, so the person sees what the chat set and
 * can change any of it in place. It is the second half of building an agent by chat — the model only ever
 * writes a draft; this page is where the person looks at all of it and publishes (the Publish button itself
 * lives in the screen's bottom bar, see [AgentBuilderScreen]).
 *
 * Sections are the wizard's own controls ([IdentityFields], [InstructionsFields], [ToolPicker], [ModesFields],
 * [ModelChips], [DryRunSection], [PublishBlock]), not copies: every edit goes through [onUpdateDraft], i.e.
 * the same `AgentBuilderViewModel.updateDraft`, so validation and autosave behave exactly as in the wizard.
 * Tools and the model are always open on purpose — the owner wants every checkbox in sight without a tap;
 * only the secondary blocks (skills/memory/cards, the dry run) fold.
 *
 * Inline errors are shown from the start: nobody opens a review of an untouched empty form (the wizard's
 * "show errors only after Next" rule exists for that case), and a Publish that is greyed out has to say why.
 * Each control draws only the issues of its own field, so the full [AgentBuilderUiState.errors] goes to all.
 */
@Composable
internal fun AgentReviewContent(
  state: AgentBuilderUiState,
  availableSkillIds: Set<String>,
  testStatus: TurnStatus?,
  testStreamingText: String,
  testToolTrace: List<ToolTrace>,
  onUpdateDraft: ((AgentDraft) -> AgentDraft) -> Unit,
  onSetExecutionMode: (ExecutionMode) -> Unit,
  onSendTestMessage: (String) -> Unit,
  onArchive: () -> Unit,
  testPendingApproval: ToolApprovalRequest?,
  onResolveTestApproval: (requestId: String, approved: Boolean) -> Unit,
  onOpenChat: (() -> Unit)?,
  onOpenSettings: (() -> Unit)?,
  onEditStepByStep: (BuilderStep) -> Unit,
  modifier: Modifier = Modifier,
  /** The wizard's floating islands (see [AgentBuilderScreen]): the page scrolls under them. */
  contentPadding: PaddingValues = PaddingValues(),
) {
  val draft = state.draft
  val errors = state.errors

  Column(
    modifier = modifier
      .verticalScroll(rememberScrollState())
      .padding(contentPadding)
      .padding(horizontal = 20.dp, vertical = 16.dp)
      .testTag("agent_builder_review"),
  ) {
    Text(text = tr("Проверь агента", "Review your agent"), style = MaterialTheme.typography.headlineSmall)
    Text(
      text = tr(
        "Здесь всё, что настроено в чате. Поправь что угодно — агент появится в «Мои агенты», " +
          "только когда ты нажмёшь «Опубликовать».",
        "Everything the chat set is here. Change anything — the agent only reaches “My agents” " +
          "when you tap Publish.",
      ),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(top = 6.dp),
    )
    TextButton(
      onClick = { onEditStepByStep(BuilderStep.IDENTITY) },
      modifier = Modifier.testTag("agent_builder_edit_steps"),
    ) { Text(tr("Править по шагам", "Edit step by step")) }

    ReviewSection(title = tr("Имя и описание", "Name and description"), tag = "identity") {
      IdentityFields(draft = draft, errors = errors, onUpdateDraft = onUpdateDraft)
    }

    ReviewSection(title = tr("Инструкции", "Instructions"), tag = "instructions") {
      InstructionsFields(draft = draft, errors = errors, onUpdateDraft = onUpdateDraft, minLines = 5)
    }

    ReviewSection(
      title = tr("Инструменты", "Tools"),
      summary = tr("выбрано ${draft.toolNames.size}", "${draft.toolNames.size} selected"),
      tag = "tools",
    ) {
      ErrorText(errors, "toolNames")
      Spacer(modifier = Modifier.height(8.dp))
      ToolPicker(draft = draft, onUpdateDraft = onUpdateDraft, compact = true)
    }

    ReviewSection(title = tr("Режимы", "Modes"), tag = "modes") {
      ModesFields(draft = draft, errors = errors, onUpdateDraft = onUpdateDraft)
    }

    ReviewSection(title = tr("Модель", "Model"), tag = "model") {
      ModelChips(draft = draft, errors = errors, onUpdateDraft = onUpdateDraft)
    }

    ReviewSection(
      title = tr("Правила (хуки)", "Rules (hooks)"),
      summary = "${draft.hooks.size}",
      tag = "rules",
    ) {
      ErrorText(errors, "hooks")
      HooksSummary(hooks = draft.hooks)
      TextButton(
        onClick = { onEditStepByStep(BuilderStep.HOOKS) },
        modifier = Modifier.testTag("agent_builder_edit_rules"),
      ) { Text(tr("Править правила", "Edit rules")) }
    }

    // Folded: the chat never sets these, but a draft that was opened from the roster can carry them — and an
    // error here would block Publish with nothing on screen to fix, so an error unfolds the section.
    ReviewSection(
      title = tr("Навыки, память, карточки", "Skills, memory, cards"),
      tag = "extras",
      collapsible = true,
      forceExpanded = errors.any { it.field == "skillIds" || it.field == "allowedWidgetTemplates" },
    ) {
      CapabilitiesExtras(
        draft = draft,
        errors = errors,
        availableSkillIds = availableSkillIds,
        onUpdateDraft = onUpdateDraft,
      )
    }

    // Folded: optional. A pending approval or a run in progress unfolds it, or the approval bar would sit
    // hidden while the turn waits on it.
    ReviewSection(
      title = tr("Пробный запуск", "Try it"),
      tag = "dry_run",
      collapsible = true,
      forceExpanded = testPendingApproval != null || testStatus is TurnStatus.Running,
    ) {
      DryRunSection(
        executionMode = state.executionMode,
        testStatus = testStatus,
        testStreamingText = testStreamingText,
        testToolTrace = testToolTrace,
        onSetExecutionMode = onSetExecutionMode,
        onSendTestMessage = onSendTestMessage,
        testPendingApproval = testPendingApproval,
        onResolveTestApproval = onResolveTestApproval,
        onOpenSettings = onOpenSettings,
      )
    }

    // The blockers list and Archive; the Publish button and the "published" notice are in the bottom bar.
    PublishBlock(
      draft = draft,
      canPublish = state.canPublish,
      errors = errors,
      isSaving = state.isSaving,
      onPublish = {},
      onArchive = onArchive,
      justPublished = state.justPublished,
      onOpenChat = onOpenChat,
      showPublishButton = false,
      showPublishedNotice = false,
    )
    Spacer(modifier = Modifier.height(16.dp))
  }
}

/**
 * One block of the review page: a title (with a short [summary] beside it), a divider above, the controls
 * below. [collapsible] blocks start folded and can be toggled; [forceExpanded] keeps one open while it has
 * something the person must see.
 */
@Composable
private fun ReviewSection(
  title: String,
  tag: String,
  summary: String? = null,
  collapsible: Boolean = false,
  forceExpanded: Boolean = false,
  content: @Composable () -> Unit,
) {
  var expanded by remember { mutableStateOf(false) }
  val open = !collapsible || expanded || forceExpanded

  Column(modifier = Modifier.fillMaxWidth().testTag("agent_builder_review_$tag")) {
    Spacer(modifier = Modifier.height(16.dp))
    HorizontalDivider()
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .then(if (collapsible) Modifier.clickable(role = Role.Button) { expanded = !expanded } else Modifier)
        .padding(vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(text = title, style = MaterialTheme.typography.titleMedium)
      if (summary != null) {
        Text(
          text = summary,
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.weight(1f),
        )
      } else {
        Spacer(modifier = Modifier.weight(1f))
      }
      if (collapsible) {
        Icon(
          imageVector = if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
          contentDescription = if (open) tr("Свернуть", "Collapse") else tr("Развернуть", "Expand"),
        )
      }
    }
    if (open) content()
  }
}

/** The draft's hooks as a read-only list — editing them is the hooks step's job ("Edit rules" goes there). */
@Composable
private fun HooksSummary(hooks: List<AgentHook>) {
  if (hooks.isEmpty()) {
    Text(
      text = tr("Правил пока нет.", "No rules yet."),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    return
  }
  hooks.sortedWith(compareBy({ it.priority }, { it.id })).forEach { hook ->
    val line = "${hookEventLabel(hook.event)} → ${hookActionLabel(hook.actionType)}"
    Text(
      text = if (hook.enabled) line else "$line (${tr("выключено", "off")})",
      style = MaterialTheme.typography.bodyMedium,
      color = if (hook.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(vertical = 2.dp),
    )
  }
}
