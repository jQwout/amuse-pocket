package com.aiopenpocket.ui.agents

// @trace multi-agent-orchestration-27b8e3
// @trace user-agent-builder-848153

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.aiopenpocket.agent.core.agent.AgentDefinition
import com.aiopenpocket.agent.core.agent.MiniAgentCatalog
import com.aiopenpocket.agent.core.agent.displayDescription
import com.aiopenpocket.agent.core.agent.displayName
import com.aiopenpocket.agent.core.agent.toDefinition
import com.aiopenpocket.agent.core.mcp.CapabilityNames
import com.aiopenpocket.i18n.AppLanguage
import com.aiopenpocket.i18n.tr
import com.aiopenpocket.ui.components.AcademyCollapsingScaffold
import com.aiopenpocket.ui.components.glass.GlassCard
import com.aiopenpocket.ui.components.glass.rememberGlassState
import com.aiopenpocket.ui.theme.MyApplicationTheme
import com.aiopenpocket.ui.theme.ThemeMode
import dev.zacsweers.metrox.viewmodel.metroViewModel

/**
 * Roster screen (Task 21): tapping a card navigates to the full-screen agent detail (`agent/{id}`,
 * [com.aiopenpocket.ui.agents.detail.AgentDetailRoute]) — the old read-only glass bottom-sheet
 * inspector is gone, along with "Open chat" living only inside that sheet. Opening a chat is now
 * the detail screen's own header action. [onCreateAgent] (the FAB) starts a brand-new draft in the
 * Task 20 builder (`agent_builder`).
 */
@Composable
fun AgentsScreen(
  onOpenAgent: (String) -> Unit,
  onCreateAgent: () -> Unit,
  onEditDraft: (String) -> Unit,
  onNavigateBack: () -> Unit,
) {
  val viewModel: AgentsViewModel = metroViewModel()
  val roster by viewModel.roster.collectAsState()
  val glassState = rememberGlassState()

  AcademyCollapsingScaffold(
    title = tr("Агенты", "Agents"),
    glassState = glassState,
    navigationIcon = {
      IconButton(onClick = onNavigateBack) {
        Icon(
          imageVector = Icons.AutoMirrored.Filled.ArrowBack,
          contentDescription = tr("Назад", "Back"),
        )
      }
    },
    floatingActionButton = {
      ExtendedFloatingActionButton(
        onClick = onCreateAgent,
        icon = { Icon(imageVector = Icons.Default.Add, contentDescription = null) },
        text = { Text(tr("Создать агента", "Create agent")) },
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
      )
    },
  ) { padding ->
    LazyColumn(
      modifier = Modifier
        .fillMaxSize()
        .padding(horizontal = 20.dp),
      // The scaffold paddings go into contentPadding, NOT .padding(): then the list takes the full height
      // and scrolls UNDER the floating glass islands (they have something to blur), while the edge cards
      // do not hide under the glass.
      contentPadding = PaddingValues(
        top = padding.calculateTopPadding(),
        bottom = padding.calculateBottomPadding() + 96.dp,
      ),
    ) {
      item {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = rosterIntro(),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        Spacer(modifier = Modifier.height(20.dp))
      }

      if (roster.presets.isNotEmpty()) {
        item {
          RosterSectionLabel(tr("Шаблоны", "Templates"))
          Spacer(modifier = Modifier.height(10.dp))
        }
        items(roster.presets, key = { it.id }) { agent ->
          AgentCard(agent = agent, onClick = { onOpenAgent(agent.id) })
          Spacer(modifier = Modifier.height(12.dp))
        }
      }

      if (roster.userAgents.isNotEmpty()) {
        item {
          Spacer(modifier = Modifier.height(8.dp))
          RosterSectionLabel(tr("Мои агенты", "My agents"))
          Spacer(modifier = Modifier.height(10.dp))
        }
        items(roster.userAgents, key = { it.id }) { agent ->
          AgentCard(agent = agent, onClick = { onOpenAgent(agent.id) })
          Spacer(modifier = Modifier.height(12.dp))
        }
      }

      // Drafts get a separate section and lead STRAIGHT to the builder: a half-built agent has no chat yet,
      // and there is nothing to show on the agent screen. Without this section a draft that the autosave
      // diligently wrote was shown by nobody at all.
      if (roster.drafts.isNotEmpty()) {
        item {
          Spacer(modifier = Modifier.height(8.dp))
          RosterSectionLabel(tr("Черновики", "Drafts"))
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            text = tr("Недособранные агенты — продолжи и опубликуй.", "Unfinished agents — pick one up and publish it."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
          )
          Spacer(modifier = Modifier.height(10.dp))
        }
        items(roster.drafts, key = { it.id }) { agent ->
          AgentCard(agent = agent, onClick = { onEditDraft(agent.id) }, isDraft = true)
          Spacer(modifier = Modifier.height(12.dp))
        }
      }
    }
  }
}

/** The roster's subtitle — shared by the screen and its preview. */
private fun rosterIntro(): String = tr(
  "Агенты-специалисты со своими инструментами. Открой нужного — и опиши задачу.",
  "Specialist agents, each with its own tools. Open one and describe your task.",
)

@Composable
private fun RosterSectionLabel(text: String) {
  Text(text = text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AgentCard(agent: AgentDefinition, onClick: () -> Unit, isDraft: Boolean = false) {
  GlassCard(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(16.dp),
    onClick = onClick,
  ) {
    // GlassCard's own Column has no contentPadding slot in the frozen API, so this inner Column
    // is the one place that owns the 16dp inset the old MaterialTheme.colorScheme.surfaceVariant Column used to apply directly
    // (same pattern as Home's CourseCard).
    Column(modifier = Modifier.padding(16.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        Box(
          modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = iconFor(agent.iconKey),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
          )
        }
        Column(modifier = Modifier.weight(1f)) {
          Text(
            // A draft is often still nameless: an empty name would give a ghost card without a single caption.
            // displayName: a preset has an English name, the user's own agent — whatever its author named it.
            text = agent.displayName.ifBlank { tr("Без имени", "Untitled") },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
          )
          Spacer(modifier = Modifier.height(2.dp))
          Text(
            text = agent.displayDescription.ifBlank { if (isDraft) tr("Черновик без описания", "Draft, no description yet") else "" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
          )
        }
        if (isDraft) {
          Text(
            text = tr("Черновик", "Draft"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.tertiary,
          )
        }
      }

      // "Under the hood": what the agent has at its disposal — tools and skills (its scope). It makes the
      // card informative: what the agent can do is visible before opening the chat. An empty list — no
      // heading: a bare "Can do" over nothing looks broken.
      // The language is a key too: the captions come from CapabilityNames in the current language, and
      // without it remember would keep the chips in the language of the first frame after a switch in
      // settings.
      val labels = remember(agent.toolNames, AppLanguage.code) { capabilityLabels(agent.toolNames) }
      if (labels.isNotEmpty()) {
        Spacer(modifier = Modifier.height(12.dp))
        Text(
          text = tr("Умеет", "Can use"),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
        )
        Spacer(modifier = Modifier.height(6.dp))
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          labels.forEach { CapabilityChip(it) }
        }
      }
    }
  }
}

@Composable
private fun CapabilityChip(label: String) {
  Text(
    text = label,
    style = MaterialTheme.typography.labelSmall,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier
      .clip(RoundedCornerShape(8.dp))
      .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
      .padding(horizontal = 8.dp, vertical = 4.dp),
  )
}

/**
 * Human-readable labels of the agent's tools — its "equipment". The names come from the shared
 * [CapabilityNames]: there used to be a dictionary of its own here, and the same tool was named
 * differently in a chip and in the builder ("Files" / "Agent files"). Only a tool with no name at all
 * (a tool from a newer app version) is skipped — a caption made from a raw id in a chip is worse than
 * none.
 */
internal fun capabilityLabels(toolNames: Set<String>): List<String> =
  toolNames.filter(CapabilityNames::isKnownTool).map(CapabilityNames::tool).distinct()

internal fun iconFor(key: String): ImageVector = when (key) {
  "wallet" -> Icons.Default.AccountBalanceWallet
  "translate" -> Icons.Default.Translate
  "checklist" -> Icons.Default.Checklist
  "alarm" -> Icons.Default.Alarm
  "restaurant" -> Icons.Default.Restaurant
  "book" -> Icons.AutoMirrored.Filled.MenuBook
  else -> Icons.Default.SmartToy
}

// ==================================================================================================
// Previews of the Agents screen (screenshot tests). The same visual frame (the scaffold's automatic
// aurora background via glassState, GlassCard on the cards) as in production, built from the same
// file-private [AgentCard], but with presets from [MiniAgentCatalog] (adapted into [AgentDefinition] via
// [toDefinition]) instead of the ViewModel — [AgentsScreen] gets [AgentsViewModel] via metroViewModel(),
// which has no DI graph to take it from in Robolectric/@Preview. [userAgents] is empty by default — as
// in production, the "My agents" section simply does not render while empty.
// ==================================================================================================

@Composable
internal fun AgentsPreviewScaffold(
  presets: List<AgentDefinition> = MiniAgentCatalog.all.map { it.toDefinition() },
  userAgents: List<AgentDefinition> = emptyList(),
) {
  MyApplicationTheme(mode = ThemeMode.DARK) {
    val glassState = rememberGlassState()
    AcademyCollapsingScaffold(
      title = tr("Агенты", "Agents"),
      glassState = glassState,
      navigationIcon = {
        IconButton(onClick = {}) {
          Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = tr("Назад", "Back"),
          )
        }
      },
      floatingActionButton = {
        ExtendedFloatingActionButton(
          onClick = {},
          icon = { Icon(imageVector = Icons.Default.Add, contentDescription = null) },
          text = { Text(tr("Создать агента", "Create agent")) },
          containerColor = MaterialTheme.colorScheme.primary,
          contentColor = MaterialTheme.colorScheme.onPrimary,
        )
      },
    ) { padding ->
      LazyColumn(
        modifier = Modifier
          .fillMaxSize()
          .padding(horizontal = 20.dp),
        // As in production: the scaffold paddings go into contentPadding, the list runs under the islands.
        contentPadding = PaddingValues(
          top = padding.calculateTopPadding(),
          bottom = padding.calculateBottomPadding() + 96.dp,
        ),
      ) {
        item {
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            text = rosterIntro(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
          )
          Spacer(modifier = Modifier.height(20.dp))
        }

        if (presets.isNotEmpty()) {
          item {
            RosterSectionLabel(tr("Шаблоны", "Templates"))
            Spacer(modifier = Modifier.height(10.dp))
          }
          items(presets, key = { it.id }) { agent ->
            AgentCard(agent = agent, onClick = {})
            Spacer(modifier = Modifier.height(12.dp))
          }
        }

        if (userAgents.isNotEmpty()) {
          item {
            Spacer(modifier = Modifier.height(8.dp))
            RosterSectionLabel(tr("Мои агенты", "My agents"))
            Spacer(modifier = Modifier.height(10.dp))
          }
          items(userAgents, key = { it.id }) { agent ->
            AgentCard(agent = agent, onClick = {})
            Spacer(modifier = Modifier.height(12.dp))
          }
        }
      }
    }
  }
}
