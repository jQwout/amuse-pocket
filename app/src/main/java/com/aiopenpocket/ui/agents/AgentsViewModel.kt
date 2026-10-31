package com.aiopenpocket.ui.agents

// @trace multi-agent-orchestration-27b8e3
// @trace user-agent-builder-848153

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiopenpocket.agent.core.agent.AgentDefinition
import com.aiopenpocket.agent.core.agent.AgentResolver
import com.aiopenpocket.agent.core.agent.AgentSource
import com.aiopenpocket.agent.core.agent.BuilderAgent
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
   * Starts a NEW chat with the hidden Builder agent and hands its conversation id to [onReady]: the
   * entry point of "Create agent" (the Agents FAB and the capstone lesson's "Open the builder").
   * Never reuses an earlier builder chat: every agent the person builds gets its own conversation, and
   * the draft is bound to that conversation id (see `AgentDraftTool.draftIdFor`).
   *
   * The first message is written here, not generated: the greeting asks the one thing the builder needs
   * first (what the agent should do), so the chat opens with no model call and no key — the key is only
   * asked for when the person answers.
   */
  fun openBuilderChat(onReady: (String) -> Unit) {
    if (!opening.add(BuilderAgent.ID)) return
    viewModelScope.launch {
      try {
        val builder = agentResolver.resolve(BuilderAgent.ID) ?: return@launch
        val id = conversationRepository.createConversation(
          title = tr("Новый агент", "New agent"),
          personaId = userPreferencesRepository.getDefaultPersonaId(),
          agentId = builder.id,
        )
        conversationRepository.addMessage(id, "assistant", builderGreeting())
        onReady(id)
      } finally {
        opening.remove(BuilderAgent.ID)
      }
    }
  }

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
        conversationRepository.addMessage(id, "assistant", agentGreeting(name, description))
        onReady(id)
      } finally {
        opening.remove(agentId)
      }
    }
  }
}

/** The Builder chat's opening line: a short hello and the one free-text question the whole flow starts from. */
internal fun builderGreeting(): String = tr(
  "Привет! Давай соберём твоего агента. Что он должен делать? Расскажи своими словами, а дальше я задам пару коротких вопросов.",
  "Hi! Let's build your agent. What should it do? Tell me in your own words, then I'll ask a few short questions.",
)

/**
 * The first message of an agent's chat: who this is, what it does, and an offer to start.
 *
 * The three parts used to be glued with a bare space — "…drafts emails about my meetings How can I
 * help?" — because a description is free text its author ends however they like: the builder does not
 * demand a full stop, a preset's description happens to have one, and an empty description left a
 * double space. So the sentence is closed here if it is not closed already, and a part that is empty
 * drops out of the line entirely instead of leaving a gap.
 */
internal fun agentGreeting(name: String, description: String): String {
  val about = description.trim().let {
    when {
      it.isEmpty() -> ""
      // Only real terminators count as "already closed". A description ending in a bracket or a quote
      // gets its own full stop after it — "(fast)." is right, "(fast) How can I help?" is not.
      it.last() in SENTENCE_TERMINATORS -> it
      else -> "$it."
    }
  }
  return listOf(
    tr("Это агент «$name».", "This is the “$name” agent."),
    about,
    tr("Чем помочь?", "How can I help?"),
  ).filter { it.isNotEmpty() }.joinToString(" ")
}

/** Characters after which a description is already a finished sentence. */
private const val SENTENCE_TERMINATORS = ".!?…:;"
