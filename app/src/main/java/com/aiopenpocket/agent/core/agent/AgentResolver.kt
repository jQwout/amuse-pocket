package com.aiopenpocket.agent.core.agent

// @trace user-agent-builder-848153

import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Single lookup API over both agent stores — the read-only [MiniAgentCatalog] presets and the
 * Room-backed [UserAgentRepository] customs — so the runtime (`PrePromptAssembler`, background
 * `HeartbeatRunner`) and the UI (roster screens, inspector) never call either store directly and
 * never have to know which one a given id came from.
 *
 * Every [resolve]/[observeRoster] result is an immutable [AgentDefinition] snapshot: nothing here
 * is mutated in place, so a turn that resolved its agent once (see
 * `PrePromptAssembler.PromptContext.agent`) can keep passing that same instance downstream instead
 * of re-resolving mid-turn and possibly observing a concurrent edit/publish.
 */
interface AgentResolver {
  /**
   * Looks up [id] regardless of [AgentStatus] — a preset always wins a same-id collision with a
   * custom agent ([AgentValidator] already keeps a colliding custom agent from ever reaching
   * [AgentStatus.PUBLISHED], but a [AgentStatus.DRAFT] row could still share an id before that
   * check runs, so [resolve] itself does not trust ordering of the two stores alone).
   *
   * Deliberately NOT filtered to [AgentStatus.PUBLISHED]: the agent builder UI (later tasks) needs
   * to resolve a draft to preview/edit it, and a chat already bound to a since-archived agent still
   * needs its name/prompt to render its history. Roster membership (started via [observeRoster]) is
   * the only place PUBLISHED-only filtering applies.
   */
  suspend fun resolve(id: String?): AgentDefinition?

  /** Every agent a user can start a NEW chat with: every preset, then every PUBLISHED custom agent. */
  fun observeRoster(): Flow<List<AgentDefinition>>
}

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DefaultAgentResolver(
  private val userAgentRepository: UserAgentRepository,
) : AgentResolver {

  override suspend fun resolve(id: String?): AgentDefinition? {
    if (id == null) return null
    return MiniAgentCatalog.get(id)?.toDefinition() ?: userAgentRepository.get(id)
  }

  override fun observeRoster(): Flow<List<AgentDefinition>> =
    userAgentRepository.observePublished().map { published ->
      // distinctBy id + presets FIRST = preset wins a same-id collision, matching resolve()'s
      // precedence. Без этого пара с одинаковым id (напр. будущий preset-id, уже занятый старым
      // published-агентом) дала бы дубликат и уронила LazyColumn(items, key = { it.id }).
      (PRESET_DEFINITIONS + published).distinctBy { it.id }
    }

  companion object {
    /** Presets are read-only Kotlin, computed once — only the Room half of the roster is reactive. */
    private val PRESET_DEFINITIONS: List<AgentDefinition> = MiniAgentCatalog.all.map { it.toDefinition() }
  }
}
