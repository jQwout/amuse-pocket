package com.aiopenpocket.ui.forge

// @trace cloud-forged-screens-40d702

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiopenpocket.agent.core.forge.ForgedAgentUi
import com.aiopenpocket.agent.core.forge.ForgedScreenRepository
import com.aiopenpocket.agent.core.forge.ForgedScreenState
import com.aiopenpocket.di.AppScope
import com.aiopenpocket.i18n.tr
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

/** Observes one agent's forged interface for the chat banner; a ViewModel of its own keeps ChatViewModel untouched. */
@Inject
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey
class ForgedUiBannerViewModel(private val forgedScreenRepository: ForgedScreenRepository) : ViewModel() {
  private val agentIdFlow = MutableStateFlow<String?>(null)
  private val _ui = MutableStateFlow(ForgedAgentUi())
  val ui: StateFlow<ForgedAgentUi> = _ui.asStateFlow()

  init {
    viewModelScope.launch {
      @OptIn(ExperimentalCoroutinesApi::class)
      agentIdFlow.flatMapLatest { id -> if (id == null) flowOf(ForgedAgentUi()) else forgedScreenRepository.observe(id) }
        .collect { ui -> _ui.update { ui } }
    }
  }

  fun select(agentId: String?) {
    agentIdFlow.value = agentId
  }
}

/**
 * The chat's line about the agent's own interface: "being built, use the chat meanwhile" while a
 * job is in flight, "ready - open" once the bundle is on disk and the person chose the interface.
 * Nothing for an agent that lives in the chat.
 */
@Composable
fun ForgedUiBanner(agentId: String?, onOpenUi: (String) -> Unit, modifier: Modifier = Modifier) {
  if (agentId == null) return
  val viewModel: ForgedUiBannerViewModel = metroViewModel()
  LaunchedEffect(agentId) { viewModel.select(agentId) }
  val ui by viewModel.ui.collectAsState()
  ForgedUiBannerContent(ui = ui, onOpenUi = { onOpenUi(agentId) }, modifier = modifier)
}

@Composable
fun ForgedUiBannerContent(ui: ForgedAgentUi, onOpenUi: () -> Unit, modifier: Modifier = Modifier) {
  val state = ui.state
  val building = state.isInFlight
  val ready = ui.showsCustomUi
  if (!building && !ready) return
  // Opaque, not glass: the banner floats over the chat, and on glass the messages scrolling behind it
  // showed through its text (owner, phone, 4 Nov 2026).
  Surface(
    modifier = modifier.fillMaxWidth().testTag("forged_banner"),
    shape = RoundedCornerShape(16.dp),
    color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (building) {
        CircularProgressIndicator(
          modifier = Modifier.size(18.dp),
          strokeWidth = 2.dp,
          color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(10.dp))
      }
      Text(
        text = if (building) {
          tr("Твой интерфейс в разработке — пока пользуйся чатом.", "Your interface is being built - use the chat meanwhile.")
        } else {
          tr("Интерфейс агента готов.", "The agent's interface is ready.")
        },
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.weight(1f),
      )
      if (ready) {
        TextButton(onClick = onOpenUi, modifier = Modifier.testTag("forged_banner_open")) {
          Text(tr("Открыть", "Open"))
        }
      }
    }
  }
}
