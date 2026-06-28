package com.aiopenpocket.ui.agents

// @trace multi-agent-orchestration-27b8e3

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiopenpocket.agent.core.agent.MiniAgent
import com.aiopenpocket.agent.core.agent.MiniAgentCatalog
import com.aiopenpocket.agent.core.identity.UserPreferencesRepository
import com.aiopenpocket.agent.core.memory.repository.ConversationRepository
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import dev.zacsweers.metro.binding
import kotlinx.coroutines.launch

@Inject
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey(AgentsViewModel::class)
class AgentsViewModel(
  private val conversationRepository: ConversationRepository,
  private val userPreferencesRepository: UserPreferencesRepository,
) : ViewModel() {

  val agents: List<MiniAgent> = MiniAgentCatalog.all

  /** Opens a fresh chat bound to [agentId] (scoped tools + role) and returns its id via [onReady]. */
  fun openAgentChat(agentId: String, onReady: (String) -> Unit) {
    viewModelScope.launch {
      val agent = MiniAgentCatalog.get(agentId) ?: return@launch
      val personaId = userPreferencesRepository.getDefaultPersonaId()
      val id = conversationRepository.createConversation(
        title = agent.name,
        personaId = personaId,
        agentId = agent.id,
      )
      val greeting = "Это агент «${agent.name}». ${agent.description} Чем помочь?"
      conversationRepository.addMessage(id, "assistant", greeting)
      onReady(id)
    }
  }
}
