package com.aiopenpocket.ui.agents.detail

// @trace user-agent-builder-848153
// @trace structured-plan-engine-907066

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aiopenpocket.agent.core.agent.AgentDefinition
import com.aiopenpocket.ui.agents.iconFor
import com.aiopenpocket.ui.components.glass.auroraBackground

/**
 * Содержимое модалки-инспектора агента (открывается как ModalBottomSheet из [AgentDetailRoute],
 * решение «модалка 1:1 с ✕» по присланному макету): аврора-фон, компактная шапка
 * [AgentDetailModalHeader] (иконка+имя+статус+id, крестик закрытия), сегмент-табы [AgentDetailTabs]
 * Обзор/Файлы и прокручиваемый контент. Вкладка «Обзор» — «Открыть чат» → действия → стат-плитки →
 * препромпт → Планы → Инструменты → Навыки → Напоминания (heartbeat) → Хуки; вкладка «Файлы» —
 * секция файлов.
 *
 * Все секции — stateless-компоненты из AgentDetailSections.kt на реальном [AgentDetailState] (у
 * каждой есть фокусное @Preview). Планы (Task 12) тапом открывают `plan/{planId}` ([onOpenPlan]),
 * «Все планы» — `plans/{agentId}` ([onOpenPlanList]). Ранее это был полноэкранный экран на
 * AcademyCollapsingScaffold (Task 21); редизайн вернул презентацию к шторке по макету.
 */
@Composable
fun AgentDetailScreen(
  state: AgentDetailState,
  onOpenChat: () -> Unit,
  onDuplicate: () -> Unit,
  onEdit: () -> Unit,
  onToggleEnabled: () -> Unit,
  onArchive: () -> Unit,
  onNavigateBack: () -> Unit,
  onOpenPlan: (String) -> Unit = {},
  onOpenPlanList: () -> Unit = {},
) {
  // Модалка (ModalBottomSheet) собирается в AgentDetailRoute; здесь её содержимое: аврора-фон,
  // компактная шапка с ✕, табы Обзор/Файлы и прокручиваемый контент. Полноэкранный
  // AcademyCollapsingScaffold с ←-стрелкой заменён на шторку по макету (решение «модалка 1:1 с ✕»).
  Box(modifier = Modifier.fillMaxSize().auroraBackground()) {
    val agent = state.definition
    if (agent == null) {
      Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (state.isLoading) {
          CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        } else {
          Text(
            text = "Агент не найден",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
          )
        }
      }
    } else {
      var tab by rememberSaveable { mutableStateOf(0) }
      Column(modifier = Modifier.fillMaxSize()) {
        AgentDetailModalHeader(agent = agent, enabled = state.isEnabled, onClose = onNavigateBack)
        AgentDetailTabs(
          selected = tab,
          onSelect = { tab = it },
          modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        Column(
          modifier = Modifier
            // weight(1f), а НЕ fillMaxSize: в Column нон-weighted fillMaxSize берёт полную высоту
            // родителя (не «оставшуюся после шапки/табов»), и низ прокрутки уходил под экран —
            // хвост секций был недостижим. weight отдаёт скроллу ровно остаток высоты.
            .fillMaxWidth()
            .weight(1f)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 40.dp)
            .testTag("agent_detail_scroll"),
        ) {
          if (tab == 1) {
            Spacer(modifier = Modifier.height(8.dp))
            FilesSection(files = state.files)
          } else {
            Spacer(modifier = Modifier.height(8.dp))
            Button(
              onClick = onOpenChat,
              modifier = Modifier.fillMaxWidth().testTag("agent_detail_open_chat"),
            ) {
              Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(modifier = Modifier.width(8.dp))
              Text("Открыть чат")
            }
            Spacer(modifier = Modifier.height(12.dp))
            ActionsRow(
              state = state,
              onDuplicate = onDuplicate,
              onEdit = onEdit,
              onToggleEnabled = onToggleEnabled,
              onArchive = onArchive,
            )
            // Ошибка последнего действия (включить/дублировать/архивировать). Раньше она писалась в
            // state и нигде не показывалась: неудачное «Включить» выглядело как тап в пустоту.
            state.actionError?.let { error ->
              Spacer(modifier = Modifier.height(8.dp))
              Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("agent_detail_action_error"),
              )
            }
            if (state.hasInvalidCapabilities) {
              Spacer(modifier = Modifier.height(16.dp))
              InvalidCapabilitiesBanner()
            }
            Spacer(modifier = Modifier.height(20.dp))
            StatTiles(state = state)
            Spacer(modifier = Modifier.height(24.dp))
            PromptSection(agent = agent)
            Spacer(modifier = Modifier.height(24.dp))
            PlansSection(state = state, onOpenPlan = onOpenPlan, onOpenPlanList = onOpenPlanList)
            Spacer(modifier = Modifier.height(24.dp))
            ToolsSection(tools = state.tools)
            Spacer(modifier = Modifier.height(24.dp))
            SkillsSection(skills = state.skills)
            Spacer(modifier = Modifier.height(24.dp))
            HeartbeatSection(heartbeats = state.activeHeartbeats)
            Spacer(modifier = Modifier.height(24.dp))
            HooksSection(hooks = state.hooks)
          }
        }
      }
    }
  }
}

/** Компактная шапка модалки по макету: иконка агента, имя, статус-точка + id, крестик закрытия. */
@Composable
private fun AgentDetailModalHeader(
  agent: AgentDefinition,
  enabled: Boolean,
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer),
      contentAlignment = Alignment.Center,
    ) {
      Icon(imageVector = iconFor(agent.iconKey), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
    }
    Spacer(modifier = Modifier.width(12.dp))
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = agent.name,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
          modifier = Modifier.size(7.dp).clip(CircleShape)
            .background(if (enabled) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)),
        )
        Text(
          // id + версия конфига: версия — единственный видимый признак того, что «Опубликовать»
          // действительно зафиксировало правки (шапка со «status · vN» уехала вместе со старым
          // полноэкранным вариантом, и версию перестало быть видно вообще где-либо).
          text = "${agent.id} · v${agent.configVersion}",
          style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
    IconButton(
      onClick = onClose,
      modifier = Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
    ) {
      Icon(imageVector = Icons.Default.Close, contentDescription = "Закрыть", tint = MaterialTheme.colorScheme.onSurface)
    }
  }
}

/** Сегмент-табы Обзор/Файлы по макету: выбранный — заливка primaryContainer, невыбранный — обводка. */
@Composable
private fun AgentDetailTabs(
  selected: Int,
  onSelect: (Int) -> Unit,
  modifier: Modifier = Modifier,
) {
  val tabs = listOf("Обзор", "Файлы")
  Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    tabs.forEachIndexed { i, label ->
      val isSel = i == selected
      val shape = RoundedCornerShape(12.dp)
      Box(
        modifier = Modifier
          .weight(1f)
          .clip(shape)
          .background(if (isSel) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
          .then(if (isSel) Modifier else Modifier.border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f), shape))
          .clickable { onSelect(i) }
          .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = label,
          style = MaterialTheme.typography.labelLarge,
          fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal,
          color = if (isSel) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
      }
    }
  }
}

