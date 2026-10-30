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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aiopenpocket.agent.core.agent.AgentDefinition
import com.aiopenpocket.agent.core.agent.displayName
import com.aiopenpocket.i18n.tr
import com.aiopenpocket.ui.agents.iconFor
import com.aiopenpocket.ui.components.glass.auroraBackground

/**
 * The agent inspector's content, shown as a full-screen route from [AgentDetailRoute] (it used to be a
 * ModalBottomSheet; see the route for why the sheet was removed): an aurora background, the compact
 * header [AgentDetailModalHeader] (icon+name+status+id, a close cross), the segment tabs
 * [AgentDetailTabs] Overview/Files and scrollable content. The "Overview" tab — "Open chat" → actions →
 * stat tiles → preprompt → Plans → Tools → Skills → Reminders (heartbeat) → Hooks; the "Files" tab — the
 * files section.
 *
 * All sections are stateless components from AgentDetailSections.kt on the real [AgentDetailState]
 * (each has a focused @Preview). Plans (Task 12) open `plan/{planId}` on tap ([onOpenPlan]), "All
 * plans" opens `plans/{agentId}` ([onOpenPlanList]). Before that it was a full-screen screen on
 * AcademyCollapsingScaffold (Task 21).
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
  // The route (AgentDetailRoute) shows this content full screen; here is the content itself: the aurora
  // background, the compact header with ✕, the Overview/Files tabs and the scrollable content. The old
  // full-screen AcademyCollapsingScaffold with a ← arrow was replaced by this layout from the mockup
  // ("a modal 1:1 with ✕").
  Box(modifier = Modifier.fillMaxSize().auroraBackground()) {
    val agent = state.definition
    if (agent == null) {
      Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (state.isLoading) {
          CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        } else {
          Text(
            text = tr("Агент не найден", "Agent not found"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
          )
        }
      }
    } else {
      var tab by rememberSaveable { mutableStateOf(0) }
      // The app draws edge to edge. As a ModalBottomSheet the inspector got the status bar inset from the
      // sheet; as a full-screen route nobody gave it, and the header (name, version, ✕) sat under the clock.
      // The aurora behind still fills the status bar area — only the content steps down.
      Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        AgentDetailModalHeader(agent = agent, enabled = state.isEnabled, onClose = onNavigateBack)
        AgentDetailTabs(
          selected = tab,
          onSelect = { tab = it },
          modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        Column(
          modifier = Modifier
            // weight(1f), NOT fillMaxSize: in a Column a non-weighted fillMaxSize takes the parent's full height
            // (not "what is left after the header/tabs"), and the bottom of the scroll went off screen —
            // the tail of the sections was unreachable. weight gives the scroll exactly the remaining height.
            .fillMaxWidth()
            .weight(1f)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 40.dp)
            // The same edge-to-edge gap at the bottom: without it the last section ends under the gesture bar.
            .navigationBarsPadding()
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
              Text(tr("Открыть чат", "Open chat"))
            }
            Spacer(modifier = Modifier.height(12.dp))
            ActionsRow(
              state = state,
              onDuplicate = onDuplicate,
              onEdit = onEdit,
              onToggleEnabled = onToggleEnabled,
              onArchive = onArchive,
            )
            // The error of the last action (enable/duplicate/archive). It used to be written to the state and
            // shown nowhere: a failed "Enable" looked like a tap into nothing.
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

/** The modal's compact header per the mockup: the agent's icon, name, status dot + id, a close cross. */
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
        text = agent.displayName,
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
          // The config version is the only visible sign that "Publish" really committed the edits (the header
          // with "status · vN" went away together with the old full-screen variant). The agent's raw id
          // (`budget`, a uuid) is no longer shown here: it tells the user nothing, and the agent's name is
          // one line above.
          text = tr("версия ${agent.configVersion}", "version ${agent.configVersion}"),
          style = MaterialTheme.typography.labelMedium,
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
      Icon(imageVector = Icons.Default.Close, contentDescription = tr("Закрыть", "Close"), tint = MaterialTheme.colorScheme.onSurface)
    }
  }
}

/** Overview/Files segment tabs per the mockup: the selected one is filled with primaryContainer, the unselected one outlined. */
@Composable
private fun AgentDetailTabs(
  selected: Int,
  onSelect: (Int) -> Unit,
  modifier: Modifier = Modifier,
) {
  val tabs = listOf(tr("Обзор", "Overview"), tr("Файлы", "Files"))
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

