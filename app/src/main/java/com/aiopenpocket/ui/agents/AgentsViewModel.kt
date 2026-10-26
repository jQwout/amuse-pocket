package com.aiopenpocket.ui.agents

// @trace multi-agent-orchestration-27b8e3
// @trace user-agent-builder-848153

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiopenpocket.agent.core.agent.AgentDefinition
import com.aiopenpocket.agent.core.agent.AgentResolver
import com.aiopenpocket.agent.core.agent.AgentSource
import com.aiopenpocket.agent.core.agent.displayDescription
import com.aiopenpocket.agent.core.agent.displayName
import com.aiopenpocket.agent.core.identity.UserPreferencesRepository
import com.aiopenpocket.agent.core.memory.repository.ConversationRepository
import com.aiopenpocket.di.AppScope
import com.aiopenpocket.i18n.tr
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import dev.zacsweers.metro.binding
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Deterministic roster sections (Task 21): "Templates" (read-only presets) and "My agents" (the
 * caller's own PUBLISHED custom agents) from [AgentResolver.observeRoster]'s single flat list, plus
 * "Drafts" from [AgentResolver.observeDrafts]. Order within each list matches its source flow's
 * own order (presets in catalog order, everything else most-recently-updated first).
 *
 * [drafts] is a separate list, not a third slice of the roster flow: a draft is deliberately NOT
 * startable (it never becomes a chat), it is only resumable in the wizard. But it does need to be
 * SHOWN somewhere — every autosave writes one, and before this section existed nothing listed them,
 * so closing the wizard before "Publish" left the agent in Room with no way back to it.
 * ARCHIVED agents stay out of both lists, as before.
 */
data class AgentRosterSections(
  val presets: List<AgentDefinition> = emptyList(),
  val userAgents: List<AgentDefinition> = emptyList(),
  val drafts: List<AgentDefinition> = emptyList(),
)

@Inject
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey
class AgentsViewModel(
  private val agentResolver: AgentResolver,
  private val conversationRepository: ConversationRepository,
  private val userPreferencesRepository: UserPreferencesRepository,
) : ViewModel() {

  /** Roster split into "Templates"/"My agents"/"Drafts" — see [AgentRosterSections]. */
  val roster: StateFlow<AgentRosterSections> =
    combine(agentResolver.observeRoster(), agentResolver.observeDrafts()) { all, drafts ->
      AgentRosterSections(
        presets = all.filter { it.source == AgentSource.PRESET },
        userAgents = all.filter { it.source == AgentSource.USER },
        drafts = drafts,
      )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AgentRosterSections())

  /**
   * Agents whose chat is being opened right now. A guard against a quick double tap: without it two
   * taps on an agent with no existing chat would both see "no chat" (the first insert is not committed
   * yet) and create two duplicates — the very bug that reuse fixes. Accessed only from the main thread
   * (viewModelScope), so a plain set is enough.
   */
  private val opening = mutableSetOf<String>()

  /**
   * Opens the chat of agent [agentId] (tool scope + role) and hands its id to [onReady]. Reuses this
   * agent's existing chat if there is one — otherwise every repeated entry would spawn a new empty agent
   * chat in the conversation list ("agents spawn chats").
   */
  fun openAgentChat(agentId: String, onReady: (String) -> Unit) {
    // add() == false → this agent is already being opened: ignore the repeated tap.
    if (!opening.add(agentId)) return
    viewModelScope.launch {
      try {
        val agent = agentResolver.resolve(agentId) ?: return@launch
        val existing = conversationRepository.findLatestConversationByAgent(agent.id)
        if (existing != null) {
          onReady(existing.id)
          return@launch
        }
        val personaId = userPreferencesRepository.getDefaultPersonaId()
        // The title and the greeting are written to the DB once — in the interface language at the moment
        // the chat is created (a preset has an English name/description, the user's own agent —
        // whatever its author named it).
        val name = agent.displayName
        val description = agent.displayDescription
        val id = conversationRepository.createConversation(
          title = name,
          personaId = personaId,
          agentId = agent.id,
        )
        val greeting = tr(
          "Это агент «$name». $description Чем помочь?",
          "This is the “$name” agent. $description How can I help?",
        )
        conversationRepository.addMessage(id, "assistant", greeting)
        onReady(id)
      } finally {
        opening.remove(agentId)
      }
    }
  }
}
