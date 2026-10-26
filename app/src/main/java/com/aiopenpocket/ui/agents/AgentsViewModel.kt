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
 * Deterministic roster sections (Task 21): «Шаблоны» (read-only presets) and «Мои агенты» (the
 * caller's own PUBLISHED custom agents) from [AgentResolver.observeRoster]'s single flat list, plus
 * «Черновики» from [AgentResolver.observeDrafts]. Order within each list matches its source flow's
 * own order (presets in catalog order, everything else most-recently-updated first).
 *
 * [drafts] is a separate list, not a third slice of the roster flow: a draft is deliberately NOT
 * startable (it never becomes a chat), it is only resumable in the wizard. But it does need to be
 * SHOWN somewhere — every autosave writes one, and before this section existed nothing listed them,
 * so closing the wizard before «Опубликовать» left the agent in Room with no way back to it.
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

  /** Roster split into «Шаблоны»/«Мои агенты»/«Черновики» — see [AgentRosterSections]. */
  val roster: StateFlow<AgentRosterSections> =
    combine(agentResolver.observeRoster(), agentResolver.observeDrafts()) { all, drafts ->
      AgentRosterSections(
        presets = all.filter { it.source == AgentSource.PRESET },
        userAgents = all.filter { it.source == AgentSource.USER },
        drafts = drafts,
      )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AgentRosterSections())

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
        // Заголовок и приветствие пишутся в базу один раз — на языке интерфейса в момент создания
        // чата (у пресета есть английское имя/описание, свой агент — как его назвал автор).
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
