package com.aiopenpocket.ui.forge

// @trace cloud-forged-screens-40d702

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiopenpocket.agent.core.agent.AgentResolver
import com.aiopenpocket.agent.core.agent.displayName
import com.aiopenpocket.agent.core.forge.ForgedAgentUi
import com.aiopenpocket.agent.core.forge.ForgedScreenRepository
import com.aiopenpocket.agent.core.forge.ForgedScreenState
import com.aiopenpocket.di.AppScope
import com.aiopenpocket.i18n.tr
import com.aiopenpocket.ui.agents.AgentsViewModel
import com.aiopenpocket.ui.components.glass.GlassCard
import com.aiopenpocket.ui.components.glass.auroraBackground
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import dev.zacsweers.metrox.viewmodel.metroViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AgentUiState(
  val agentId: String = "",
  val title: String = "",
  val ui: ForgedAgentUi = ForgedAgentUi(),
)

/** The full-screen forged interface of one agent: its name, the way to the chat, and the bundle. */
@Inject
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey
class AgentUiViewModel(
  private val agentResolver: AgentResolver,
  private val forgedScreenRepository: ForgedScreenRepository,
) : ViewModel() {

  private val agentIdFlow = MutableStateFlow<String?>(null)
  private val _state = MutableStateFlow(AgentUiState())
  val state: StateFlow<AgentUiState> = _state.asStateFlow()

  init {
    viewModelScope.launch {
      @OptIn(ExperimentalCoroutinesApi::class)
      agentIdFlow.flatMapLatest { id -> if (id == null) flowOf(ForgedAgentUi()) else forgedScreenRepository.observe(id) }
        .collect { ui -> _state.update { it.copy(ui = ui) } }
    }
  }

  fun select(agentId: String) {
    if (agentIdFlow.value == agentId) return
    agentIdFlow.value = agentId
    _state.update { it.copy(agentId = agentId) }
    viewModelScope.launch {
      val name = agentResolver.resolve(agentId)?.displayName ?: agentId
      _state.update { it.copy(title = name) }
    }
  }
}

/**
 * Route `agent_ui/{agentId}`: where an agent with "Own interface" switched on opens. The header
 * carries the name, a Chat button (the chat is always one tap away) and a close cross; the body is
 * the bundle, full height. While the forge is still building, or after a failure, the body says so
 * and the Chat button is the way on.
 */
@Composable
fun AgentUiRoute(
  agentId: String,
  onOpenChat: (String) -> Unit,
  onOpenAgentDetail: (String) -> Unit,
  onNavigateBack: () -> Unit,
) {
  val viewModel: AgentUiViewModel = metroViewModel()
  // The same find-latest-or-create chat logic the agent card uses for "Open chat".
  val agentsViewModel: AgentsViewModel = metroViewModel()
  val state by viewModel.state.collectAsState()
  LaunchedEffect(agentId) { viewModel.select(agentId) }
  AgentUiScreen(
    state = state,
    onOpenChat = { agentsViewModel.openAgentChat(agentId, onOpenChat) },
    onOpenAgentDetail = { onOpenAgentDetail(agentId) },
    onNavigateBack = onNavigateBack,
    renderBundle = { ready, bytes -> ForgedBundleHost(agentId = agentId, ready = ready, bundle = bytes, modifier = Modifier.fillMaxSize()) },
  )
}

@Composable
fun AgentUiScreen(
  state: AgentUiState,
  onOpenChat: () -> Unit,
  onOpenAgentDetail: () -> Unit,
  onNavigateBack: () -> Unit,
  renderBundle: @Composable (ForgedScreenState.Ready, ByteArray) -> Unit,
) {
  Box(modifier = Modifier.fillMaxSize().auroraBackground()) {
    Column(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        IconButton(onClick = onNavigateBack, modifier = Modifier.testTag("agent_ui_close")) {
          Icon(Icons.Default.Close, contentDescription = tr("Закрыть", "Close"), tint = MaterialTheme.colorScheme.onSurface)
        }
        Text(
          text = state.title,
          // No Surface above this screen sets a content colour: without it the title is black on the dark aurora.
          color = MaterialTheme.colorScheme.onSurface,
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
          modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
        )
        Button(onClick = onOpenChat, modifier = Modifier.testTag("agent_ui_open_chat")) {
          Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text(tr("Чат", "Chat"))
        }
      }
      val ui = state.ui
      val screen = ui.state
      when {
        ui.isReady -> Box(modifier = Modifier.fillMaxWidth().weight(1f).testTag("agent_ui_bundle")) {
          renderBundle(screen as ForgedScreenState.Ready, ui.bundle!!)
        }

        screen.isInFlight -> StatusBody(
          spinner = true,
          text = tr("Ваш интерфейс в разработке — подождите немного, пока можно пользоваться чатом.", "Your interface is being built - it takes a minute or two; the chat works meanwhile."),
          onOpenChat = onOpenChat,
          onOpenAgentDetail = onOpenAgentDetail,
        )

        screen is ForgedScreenState.NeedsInfo -> StatusBody(
          spinner = false,
          text = tr("Агент задал вопрос — ответь ему в карточке агента.", "The agent has a question - answer it on the agent's card."),
          onOpenChat = onOpenChat,
          onOpenAgentDetail = onOpenAgentDetail,
        )

        screen is ForgedScreenState.Failed -> StatusBody(
          spinner = false,
          text = screen.message,
          onOpenChat = onOpenChat,
          onOpenAgentDetail = onOpenAgentDetail,
        )

        else -> StatusBody(
          spinner = !ui.loaded,
          text = if (ui.loaded) tr("У этого агента пока нет своего интерфейса.", "This agent has no interface of its own yet.") else "",
          onOpenChat = onOpenChat,
          onOpenAgentDetail = onOpenAgentDetail,
        )
      }
    }
  }
}

@Composable
private fun StatusBody(spinner: Boolean, text: String, onOpenChat: () -> Unit, onOpenAgentDetail: () -> Unit) {
  Column(
    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
      Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          if (spinner) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(modifier = Modifier.width(12.dp))
          }
          Text(text = text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("agent_ui_status"))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Button(onClick = onOpenChat) { Text(tr("Открыть чат", "Open chat")) }
          Button(onClick = onOpenAgentDetail) { Text(tr("Карточка агента", "Agent card")) }
        }
      }
    }
    Spacer(modifier = Modifier.height(8.dp))
  }
}
