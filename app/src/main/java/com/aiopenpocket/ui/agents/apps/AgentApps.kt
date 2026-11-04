package com.aiopenpocket.ui.agents.apps

// @trace agent-apps-native-screens-for-the-preset-agents-c8d8ce

import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aiopenpocket.agent.core.agent.MiniAgentCatalog
import com.aiopenpocket.i18n.tr
import com.aiopenpocket.ui.agents.AgentsViewModel
import com.aiopenpocket.ui.components.glass.auroraBackground
import dev.zacsweers.metrox.viewmodel.metroViewModel

/**
 * Agent apps: a native screen of its own for a preset agent, next to its chat. The agent and the screen
 * share one store: what the person says in the chat ("spent 12 on lunch") the agent writes through its
 * tool, and the screen shows and edits the same records - so the chat and the app never disagree.
 *
 * Ported in spirit from the old openQwoutt mini-apps (flashcards, expenses, lists), restyled in this app's
 * palette. Unlike a forged screen (cloud-forged-screens-40d702) nothing is built or paid for: the screen
 * ships in the APK.
 */
object AgentApps {
  const val TUTOR = "languages"
  const val BUDGET = "budget"
  const val TASKS = "tasks"

  private val ids = setOf(TUTOR, BUDGET, TASKS)

  fun has(agentId: String?): Boolean = agentId in ids
}

/** The body of [agentId]'s app; one composable per agent, each in its own file. */
@Composable
fun AgentAppContent(agentId: String, modifier: Modifier = Modifier) {
  when (agentId) {
    AgentApps.TUTOR -> TutorApp(modifier)
    AgentApps.BUDGET -> BudgetApp(modifier)
    AgentApps.TASKS -> TasksApp(modifier)
    else -> Box(modifier)
  }
}

/**
 * Route `agent_app/{agentId}`: the header (close, the agent's name, a Chat button - the chat is always
 * one tap away) over the agent's app.
 */
@Composable
fun AgentAppRoute(
  agentId: String,
  onOpenChat: (String) -> Unit,
  onNavigateBack: () -> Unit,
) {
  // The same find-latest-or-create chat logic the agent card uses for "Open chat".
  val agentsViewModel: AgentsViewModel = metroViewModel()
  val title = MiniAgentCatalog.get(agentId)?.displayName ?: agentId
  Box(modifier = Modifier.fillMaxSize().auroraBackground()) {
    Column(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        IconButton(onClick = onNavigateBack, modifier = Modifier.testTag("agent_app_close")) {
          Icon(Icons.Default.Close, contentDescription = tr("Закрыть", "Close"), tint = MaterialTheme.colorScheme.onSurface)
        }
        Text(
          text = title,
          color = MaterialTheme.colorScheme.onSurface,
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
          modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
        )
        Button(onClick = { agentsViewModel.openAgentChat(agentId, onOpenChat) }, modifier = Modifier.testTag("agent_app_open_chat")) {
          Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text(tr("Чат", "Chat"))
        }
      }
      AgentAppContent(agentId = agentId, modifier = Modifier.fillMaxWidth().weight(1f))
    }
  }
}

/**
 * The card of the agent apps: an opaque surface from the theme, not glass. Glass is tuned for the chat's
 * aurora and let whatever sits behind it show through (a swipe-to-delete background, a flipping card's back),
 * and its fixed tint did not follow the light and dark schemes the way the rest of these screens do.
 */
@Composable
internal fun AppPanel(
  modifier: Modifier = Modifier,
  content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
  Column(
    modifier = modifier
      .clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
      .background(MaterialTheme.colorScheme.surfaceVariant),
    content = content,
  )
}
