package com.aiopenpocket.ui.agents.builder.components

// @trace user-agent-builder-848153

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.aiopenpocket.agent.core.agent.AgentValidator
import com.aiopenpocket.agent.core.agent.ValidationIssue
import com.aiopenpocket.ui.agents.builder.AgentDraft
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
      .padding(horizontal = 20.dp, vertical = 16.dp),
  ) {
    Text(text = "Возможности", style = MaterialTheme.typography.headlineSmall)
    Text(
      text = "Инструменты и навыки, которыми будет пользоваться агент.",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(top = 6.dp, bottom = 16.dp),
    )
    ErrorText(errors, "toolNames")

    Text(text = "Инструменты", style = MaterialTheme.typography.titleMedium)
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
      title = "Расширенные инструменты",
      expanded = advancedExpanded,
      onClick = { advancedExpanded = !advancedExpanded },
    )
    if (advancedExpanded) {
      Text(
        text = "Эти инструменты особенно чувствительны — включай, только если точно понимаешь, зачем.",
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
    Text(text = "Навыки", style = MaterialTheme.typography.titleMedium)
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

    Spacer(modifier = Modifier.height(16.dp))
    CustomSkillEditor(
      existingIds = availableSkillIds + draft.skillIds,
      onCreate = { id -> onUpdateDraft { d -> d.copy(skillIds = d.skillIds + id) } },
    )

    Spacer(modifier = Modifier.height(20.dp))
    Text(text = "Шаблоны карточек", style = MaterialTheme.typography.titleMedium)
    Spacer(modifier = Modifier.height(4.dp))
    Text(
      text = "Какие карточки render_card может показывать этот агент.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(8.dp))
    ErrorText(errors, "allowedWidgetTemplates")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      AgentValidator.KNOWN_WIDGET_TEMPLATES.sorted().forEach { template ->
        val selected = template in draft.allowedWidgetTemplates
        FilterChip(
          selected = selected,
          onClick = {
            onUpdateDraft { d ->
              d.copy(allowedWidgetTemplates = if (selected) d.allowedWidgetTemplates - template else d.allowedWidgetTemplates + template)
            }
          },
          label = { Text(template) },
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
          text = "Разрешение Android: $permission",
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
    label = { Text(skillLabel(id)) },
    modifier = Modifier.testTag("agent_builder_skill_$id"),
  )
}

private fun skillLabel(id: String): String = when (id) {
  "budget" -> "Бюджет"
  "diary" -> "Дневник"
  "fitness" -> "Фитнес"
  "languages" -> "Языки"
  else -> id
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
        contentDescription = if (expanded) "Свернуть" else "Развернуть",
      )
    }
  }
}

/**
 * Authors a NEW custom skill's manifest ([SkillManifest]-shaped: name/description/whenToUse) and
 * instruction body. Only [id] actually flows anywhere from Task 20: it is appended to
 * [AgentDraft.skillIds] via [onCreate] — the only lever [com.aiopenpocket.ui.agents.builder.AgentBuilderViewModel]
 * exposes over the draft. [name]/[description]/[whenToUse]/instructions are captured here for the
 * manifest-fields UI the brief asks for, but nothing in Task 19's ViewModel/[AgentDraft] carries
 * custom-skill CONTENT (`UserSkillEntity` rows are written only inside
 * [com.aiopenpocket.agent.core.agent.UserAgentRepository.publish], which this wizard never calls with
 * a `skills` argument) — see the Task 20 report for this gap.
 */
@Composable
private fun CustomSkillEditor(existingIds: Set<String>, onCreate: (String) -> Unit) {
  var expanded by remember { mutableStateOf(false) }
  var id by remember { mutableStateOf("") }
  var name by remember { mutableStateOf("") }
  var description by remember { mutableStateOf("") }
  var whenToUse by remember { mutableStateOf("") }
  var instructions by remember { mutableStateOf("") }

  Column {
    ExpanderHeader(title = "Свой навык", expanded = expanded, onClick = { expanded = !expanded })
    if (expanded) {
      GlassField(value = id, onValueChange = { id = it.trim().take(64) }, label = "id (латиницей, напр. running-plan)")
      Spacer(modifier = Modifier.height(8.dp))
      GlassField(value = name, onValueChange = { name = it }, label = "Название")
      Spacer(modifier = Modifier.height(8.dp))
      GlassField(value = description, onValueChange = { description = it }, label = "Описание")
      Spacer(modifier = Modifier.height(8.dp))
      GlassField(value = whenToUse, onValueChange = { whenToUse = it }, label = "Когда использовать")
      Spacer(modifier = Modifier.height(8.dp))
      GlassField(
        value = instructions,
        onValueChange = { instructions = it.take(AgentValidator.MAX_CUSTOM_SKILL_CHARS) },
        label = "Инструкция",
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
          onCreate(id)
          id = ""; name = ""; description = ""; whenToUse = ""; instructions = ""
          expanded = false
        },
        enabled = canCreate,
      ) { Text("Добавить навык") }
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
  OutlinedTextField(
    value = value,
    onValueChange = onValueChange,
    label = { Text(label) },
    minLines = minLines,
    maxLines = maxLines,
    modifier = Modifier
      .fillMaxWidth()
      .glassCardSurface(),
    colors = OutlinedTextFieldDefaults.colors(
      focusedContainerColor = Color.Transparent,
      unfocusedContainerColor = Color.Transparent,
    ),
  )
}
