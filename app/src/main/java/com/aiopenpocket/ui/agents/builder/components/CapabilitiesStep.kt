package com.aiopenpocket.ui.agents.builder.components

// @trace user-agent-builder-848153

import com.aiopenpocket.agent.tools.RETIRED_CARD_TEMPLATES
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.aiopenpocket.agent.core.agent.AgentValidator
import com.aiopenpocket.agent.core.agent.ValidationIssue
import com.aiopenpocket.agent.core.mcp.CapabilityNames
import com.aiopenpocket.agent.tools.LESSON_CARD_TEMPLATES
import com.aiopenpocket.i18n.tr
import com.aiopenpocket.ui.agents.builder.AgentDraft
import com.aiopenpocket.ui.agents.builder.CustomSkillDraft
import com.aiopenpocket.ui.components.glass.glassCardSurface

/**
 * Step 3/6: tools, skills and widget templates in the draft's scope, with the risk-rail consent gate
 * (Task 20 plan Step 4) and the custom-skill authoring editor (Step 5).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CapabilitiesStep(
  draft: AgentDraft,
  errors: List<ValidationIssue>,
  availableSkillIds: Set<String>,
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
  var pendingConsentTool by remember { mutableStateOf<ToolCatalogEntry?>(null) }
  var advancedExpanded by remember { mutableStateOf(false) }

  fun setToolSelected(name: String, selected: Boolean) {
    onUpdateDraft { d -> d.copy(toolNames = if (selected) d.toolNames + name else d.toolNames - name) }
  }

  fun onToggleTool(tool: ToolCatalogEntry, selected: Boolean) {
    if (selected && tool.requiresConsent) {
      pendingConsentTool = tool
    } else {
      setToolSelected(tool.name, selected)
    }
  }

  Column(
    modifier = modifier
      .verticalScroll(rememberScrollState())
      .padding(contentPadding)
      .padding(horizontal = 20.dp, vertical = 16.dp),
  ) {
    Text(text = tr("Возможности", "Capabilities"), style = MaterialTheme.typography.headlineSmall)
    Text(
      text = tr("Инструменты и навыки, которыми будет пользоваться агент.", "Tools and skills the agent will use."),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(top = 6.dp, bottom = 16.dp),
    )
    ErrorText(errors, "toolNames")

    Text(text = tr("Инструменты", "Tools"), style = MaterialTheme.typography.titleMedium)
    Spacer(modifier = Modifier.height(8.dp))
    ToolCatalog.default.forEach { tool ->
      ToolRow(
        tool = tool,
        selected = tool.name in draft.toolNames,
        onToggle = { selected -> onToggleTool(tool, selected) },
      )
      Spacer(modifier = Modifier.height(6.dp))
    }

    Spacer(modifier = Modifier.height(8.dp))
    ExpanderHeader(
      title = tr("Расширенные инструменты", "Advanced tools"),
      expanded = advancedExpanded,
      onClick = { advancedExpanded = !advancedExpanded },
    )
    if (advancedExpanded) {
      Text(
        text = tr(
          "Эти инструменты особенно чувствительны — включай, только если точно понимаешь, зачем.",
          "These tools are especially sensitive — turn them on only if you know exactly why.",
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(vertical = 8.dp),
      )
      ToolCatalog.advanced.forEach { tool ->
        ToolRow(
          tool = tool,
          selected = tool.name in draft.toolNames,
          onToggle = { selected -> onToggleTool(tool, selected) },
        )
        Spacer(modifier = Modifier.height(6.dp))
      }
    }

    Spacer(modifier = Modifier.height(20.dp))
    Text(text = tr("Навыки", "Skills"), style = MaterialTheme.typography.titleMedium)
    Spacer(modifier = Modifier.height(8.dp))
    ErrorText(errors, "skillIds")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      availableSkillIds.sorted().forEach { id ->
        SkillChip(
          id = id,
          selected = id in draft.skillIds,
          onClick = {
            val selected = id in draft.skillIds
            onUpdateDraft { d -> d.copy(skillIds = if (selected) d.skillIds - id else d.skillIds + id) }
          },
        )
      }
    }

    Spacer(modifier = Modifier.height(12.dp))
    OwnSkillsList(
      skills = draft.customSkills,
      onDelete = { id ->
        onUpdateDraft { d ->
          d.copy(customSkills = d.customSkills.filterNot { it.id == id }, skillIds = d.skillIds - id)
        }
      },
    )

    Spacer(modifier = Modifier.height(8.dp))
    CustomSkillEditor(
      existingIds = availableSkillIds + draft.skillIds,
      onCreate = { skill ->
        // The skill goes into the draft WHOLE (not just its id): the autosave keeps the body, and the
        // validator sees it in customSkills and does not report unknown_skill — the id used to go into
        // skillIds alone, and the step got stuck for good on an error nothing could clear.
        onUpdateDraft { d -> d.copy(customSkills = d.customSkills + skill, skillIds = d.skillIds + skill.id) }
      },
    )

    Spacer(modifier = Modifier.height(20.dp))
    MemorySection(
      agentId = draft.id,
      includeSharedProfile = draft.includeSharedProfile,
      stateKeyPrefixes = draft.stateKeyPrefixes,
      onToggleSharedProfile = { checked -> onUpdateDraft { it.copy(includeSharedProfile = checked) } },
    )

    Spacer(modifier = Modifier.height(20.dp))
    Text(text = tr("Шаблоны карточек", "Card templates"), style = MaterialTheme.typography.titleMedium)
    Spacer(modifier = Modifier.height(4.dp))
    Text(
      text = tr("Какие карточки агент может показывать в чате.", "Which cards the agent can show in the chat."),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(8.dp))
    ErrorText(errors, "allowedWidgetTemplates")
    // The Academy lesson templates (quiz, gap, matching…) are drawn by the lesson itself, bypassing
    // render_card — an ordinary agent does not need them, and they have no place in the choice. One
    // already ticked (e.g. on a copy) stays visible, or it could not be unticked.
    // Retired ones ("Weather", "Switch") are no longer accepted by render_card — offering them would mean
    // a choice that turns into an error in the chat.
    val offeredTemplates = AgentValidator.KNOWN_WIDGET_TEMPLATES
      .filter { (it !in LESSON_CARD_TEMPLATES && it !in RETIRED_CARD_TEMPLATES) || it in draft.allowedWidgetTemplates }
      .sortedBy(::widgetTemplateLabel)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      offeredTemplates.forEach { template ->
        val selected = template in draft.allowedWidgetTemplates
        FilterChip(
          selected = selected,
          onClick = {
            onUpdateDraft { d ->
              d.copy(allowedWidgetTemplates = if (selected) d.allowedWidgetTemplates - template else d.allowedWidgetTemplates + template)
            }
          },
          label = { Text(widgetTemplateLabel(template)) },
        )
      }
    }
    Spacer(modifier = Modifier.height(24.dp))
  }

  pendingConsentTool?.let { tool ->
    RiskConsentDialog(
      tool = tool,
      onConfirm = {
        setToolSelected(tool.name, true)
        pendingConsentTool = null
      },
      onDismiss = { pendingConsentTool = null },
    )
  }
}

@Composable
private fun ToolRow(tool: ToolCatalogEntry, selected: Boolean, onToggle: (Boolean) -> Unit) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .glassCardSurface()
      .toggleable(value = selected, onValueChange = onToggle, role = Role.Checkbox)
      .testTag("agent_builder_tool_${tool.name}")
      .padding(12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Checkbox(checked = selected, onCheckedChange = null)
    Spacer(modifier = Modifier.width(12.dp))
    Column(modifier = Modifier.weight(1f)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = tool.label, style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = riskClassLabel(tool.riskClass),
          style = MaterialTheme.typography.labelSmall,
          color = riskColor(tool.riskClass),
        )
      }
      Text(
        text = tool.consequence,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      tool.permission?.let { permission ->
        Text(
          text = tr("Разрешение Android: $permission", "Android permission: $permission"),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
private fun SkillChip(id: String, selected: Boolean, onClick: () -> Unit) {
  FilterChip(
    selected = selected,
    onClick = onClick,
    // The name comes from the shared CapabilityNames: the same skill is called the same on the agent screen.
    label = { Text(CapabilityNames.skill(id)) },
    modifier = Modifier.testTag("agent_builder_skill_$id"),
  )
}

/** A card template's human name for the chip (in the interface language) — raw ids (`chart_bar`, `notification_permission`) tell the user nothing. */
private fun widgetTemplateLabel(template: String): String = when (template) {
  "todo_list" -> tr("Список дел", "To-do list")
  "weather_card" -> tr("Погода", "Weather")
  "timer" -> tr("Таймер", "Timer")
  "chart_bar" -> tr("Диаграмма", "Chart")
  "table" -> tr("Таблица", "Table")
  "form" -> tr("Форма", "Form")
  "choice" -> tr("Выбор варианта", "Choice")
  "switch" -> tr("Переключатель", "Toggle")
  "notification_permission" -> tr("Разрешение на уведомления", "Notification permission")
  "reminder" -> tr("Напоминание", "Reminder")
  "image" -> tr("Картинка", "Image")
  "lesson_quiz" -> tr("Квиз урока", "Lesson quiz")
  "lesson_complete" -> tr("Итог урока", "Lesson summary")
  "reveal" -> tr("Скрытый ответ", "Hidden answer")
  "concept" -> tr("Понятие", "Concept")
  "example" -> tr("Пример", "Example")
  "fill" -> tr("Заполни пропуск", "Fill in the blank")
  "sort" -> tr("Сортировка", "Sorting")
  "match" -> tr("Сопоставление", "Matching")
  "scenario" -> tr("Сценарий", "Scenario")
  else -> template
}

@Composable
private fun ExpanderHeader(title: String, expanded: Boolean, onClick: () -> Unit) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(text = title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
    IconButton(onClick = onClick) {
      Icon(
        imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
        contentDescription = if (expanded) tr("Свернуть", "Collapse") else tr("Развернуть", "Expand"),
      )
    }
  }
}

/** Per-agent memory rail: what the agent's own namespace is, and whether it also reads the shared profile. */
@Composable
private fun MemorySection(
  agentId: String,
  includeSharedProfile: Boolean,
  stateKeyPrefixes: Set<String>,
  onToggleSharedProfile: (Boolean) -> Unit,
) {
  Column {
    Text(text = tr("Память", "Memory"), style = MaterialTheme.typography.titleMedium)
    Spacer(modifier = Modifier.height(4.dp))
    val namespace = "agent:${agentId.ifBlank { "…" }}:"
    Text(
      // Isolation is not a promise in the prompt but code (MemoryTools/FileTools cut by the prefix), so the
      // user is shown the real namespace, not a generic "memory is isolated" phrase.
      text = tr(
        "Свои факты агент пишет и читает только в своём пространстве " +
          "«$namespace» — другие агенты его не видят.",
        "The agent writes and reads its own facts only in its own space " +
          "“$namespace” — other agents can't see it.",
      ),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(12.dp))
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Column(modifier = Modifier.weight(1f)) {
        Text(text = tr("Общий профиль пользователя", "Shared user profile"), style = MaterialTheme.typography.bodyMedium)
        Text(
          text = tr(
            "Агент дополнительно ЧИТАЕТ общие факты о тебе. Писать в них он не может.",
            "The agent can also READ shared facts about you. It can't write to them.",
          ),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Switch(
        checked = includeSharedProfile,
        onCheckedChange = onToggleSharedProfile,
        modifier = Modifier.testTag("agent_builder_shared_profile"),
      )
    }
    // Only for copies of presets that use this (e.g. "Tutor"). There is no editor, but it has to be shown:
    // these prefixes used to be silently lost on the copy's very first edit, and there was no way to
    // understand why the copy worked differently from the original.
    if (stateKeyPrefixes.isNotEmpty()) {
      Spacer(modifier = Modifier.height(12.dp))
      Text(
        text = tr("Состояние в промпте: ", "State in the prompt: ") + stateKeyPrefixes.sorted().joinToString(", "),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("agent_builder_state_prefixes"),
      )
    }
  }
}

/** This agent's own authored skills, with a way to remove one — the control whose absence used to make a mistyped custom skill unfixable. */
@Composable
private fun OwnSkillsList(skills: List<CustomSkillDraft>, onDelete: (String) -> Unit) {
  if (skills.isEmpty()) return
  Column {
    Text(text = tr("Свои навыки агента", "Agent's own skills"), style = MaterialTheme.typography.titleSmall)
    Spacer(modifier = Modifier.height(6.dp))
    skills.forEach { skill ->
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .glassCardSurface()
          .testTag("agent_builder_own_skill_${skill.id}")
          .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(text = skill.name.ifBlank { skill.id }, style = MaterialTheme.typography.titleSmall)
          Text(
            text = skill.id,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        IconButton(onClick = { onDelete(skill.id) }) {
          Icon(imageVector = Icons.Default.Delete, contentDescription = tr("Удалить навык", "Delete skill"))
        }
      }
      Spacer(modifier = Modifier.height(6.dp))
    }
  }
}

/**
 * Authors a NEW custom skill: manifest fields (name/description/whenToUse) plus the instruction body.
 * The WHOLE [CustomSkillDraft] goes to [onCreate] — it used to hand over the bare id and drop
 * everything else on the floor, which left the draft referencing a skill that did not exist anywhere.
 */
@Composable
private fun CustomSkillEditor(existingIds: Set<String>, onCreate: (CustomSkillDraft) -> Unit) {
  var expanded by remember { mutableStateOf(false) }
  var id by remember { mutableStateOf("") }
  var name by remember { mutableStateOf("") }
  var description by remember { mutableStateOf("") }
  var whenToUse by remember { mutableStateOf("") }
  var instructions by remember { mutableStateOf("") }

  Column {
    ExpanderHeader(title = tr("Свой навык", "Custom skill"), expanded = expanded, onClick = { expanded = !expanded })
    if (expanded) {
      GlassField(
        value = id,
        onValueChange = { id = it.trim().take(64) },
        label = tr("id (латиницей, напр. running-plan)", "id (Latin letters, e.g. running-plan)"),
      )
      Spacer(modifier = Modifier.height(8.dp))
      GlassField(value = name, onValueChange = { name = it }, label = tr("Название", "Name"))
      Spacer(modifier = Modifier.height(8.dp))
      GlassField(value = description, onValueChange = { description = it }, label = tr("Описание", "Description"))
      Spacer(modifier = Modifier.height(8.dp))
      GlassField(value = whenToUse, onValueChange = { whenToUse = it }, label = tr("Когда использовать", "When to use"))
      Spacer(modifier = Modifier.height(8.dp))
      GlassField(
        value = instructions,
        onValueChange = { instructions = it.take(AgentValidator.MAX_CUSTOM_SKILL_CHARS) },
        label = tr("Инструкция", "Instructions"),
        minLines = 4,
        maxLines = 8,
      )
      Text(
        text = "${instructions.length} / ${AgentValidator.MAX_CUSTOM_SKILL_CHARS}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp),
      )
      Spacer(modifier = Modifier.height(8.dp))
      val canCreate = id.isNotBlank() && id !in existingIds && name.isNotBlank() && instructions.isNotBlank()
      TextButton(
        onClick = {
          onCreate(
            CustomSkillDraft(
              id = id,
              name = name,
              description = description,
              whenToUse = whenToUse,
              instructions = instructions,
            ),
          )
          id = ""; name = ""; description = ""; whenToUse = ""; instructions = ""
          expanded = false
        },
        enabled = canCreate,
        modifier = Modifier.testTag("agent_builder_add_skill"),
      ) { Text(tr("Добавить навык", "Add skill")) }
    }
  }
}

@Composable
private fun GlassField(
  value: String,
  onValueChange: (String) -> Unit,
  label: String,
  minLines: Int = 1,
  maxLines: Int = 1,
) {
  BuilderTextField(
    value = value,
    onValueChange = onValueChange,
    label = label,
    minLines = minLines,
    maxLines = maxLines,
  )
}
