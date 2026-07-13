package com.aiopenpocket.ui.agents

// @trace multi-agent-orchestration-27b8e3
// @trace user-agent-builder-848153

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiopenpocket.agent.core.agent.AgentDefinition
import com.aiopenpocket.agent.core.agent.AgentResolver
import com.aiopenpocket.agent.core.agent.AgentSource
import com.aiopenpocket.agent.core.identity.UserPreferencesRepository
import com.aiopenpocket.agent.core.memory.repository.ConversationRepository
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import dev.zacsweers.metro.binding
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Deterministic roster sections (Task 21) over [AgentResolver.observeRoster]'s single flat list —
 * «Шаблоны» (read-only presets) and «Мои агенты» (the caller's own PUBLISHED custom agents; drafts
 * and archived agents never reach [AgentResolver.observeRoster] in the first place, so there is
 * nothing further to filter out here). Order within each list matches [AgentResolver.observeRoster]'s
 * own order (presets in catalog order, published customs most-recently-updated first).
 */
data class AgentRosterSections(
  val presets: List<AgentDefinition> = emptyList(),
  val userAgents: List<AgentDefinition> = emptyList(),
)

@Inject
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey(AgentsViewModel::class)
class AgentsViewModel(
  private val agentResolver: AgentResolver,
  private val conversationRepository: ConversationRepository,
  private val userPreferencesRepository: UserPreferencesRepository,
) : ViewModel() {

  /** Roster split into «Шаблоны»/«Мои агенты» — see [AgentRosterSections]. */
  val roster: StateFlow<AgentRosterSections> = agentResolver.observeRoster()
    .map { all ->
      AgentRosterSections(
        presets = all.filter { it.source == AgentSource.PRESET },
        userAgents = all.filter { it.source == AgentSource.USER },
      )
    }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AgentRosterSections())

  /**
   * Агенты, для которых прямо сейчас идёт открытие чата. Защита от быстрого двойного тапа: без неё
   * два тапа по агенту без существующего чата оба увидели бы «чата нет» (первый insert ещё не
   * закоммичен) и создали бы два дубля — тот самый баг, который чинит переиспользование.
   * Доступ только с главного потока (viewModelScope), поэтому обычного set достаточно.
   */
  private val opening = mutableSetOf<String>()

  /**
   * Открывает чат агента [agentId] (скоуп инструментов + роль) и отдаёт его id через [onReady].
   * Переиспользует существующий чат этого агента, если он есть, — иначе каждый повторный вход
   * плодил бы новый пустой чат агента в списке диалогов («агенты плодят чаты»).
   */
  fun openAgentChat(agentId: String, onReady: (String) -> Unit) {
    // add() == false → открытие этого агента уже идёт: игнорируем повторный тап.
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
        val id = conversationRepository.createConversation(
          title = agent.name,
          personaId = personaId,
          agentId = agent.id,
        )
        val greeting = "Это агент «${agent.name}». ${agent.description} Чем помочь?"
        conversationRepository.addMessage(id, "assistant", greeting)
        onReady(id)
      } finally {
        opening.remove(agentId)
      }
    }
  }
}
