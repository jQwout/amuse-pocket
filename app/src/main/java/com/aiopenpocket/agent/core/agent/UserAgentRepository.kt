package com.aiopenpocket.agent.core.agent

// @trace user-agent-builder-848153

import androidx.room.withTransaction
import com.aiopenpocket.data.database.AppDatabase
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Transactional Room-backed store for [AgentSource.USER] agent drafts/published/archived agents.
 * Presets never pass through here — they stay in the read-only [MiniAgentCatalog] — so every
 * [AgentDefinition] this repository hands back has [AgentDefinition.source] == [AgentSource.USER].
 *
 * [clock] defaults to the wall clock. It intentionally is not a parameter of the `@Inject`
 * constructor Metro resolves (a bare `() -> Long` binding would be untyped and ambiguous across the
 * whole app graph — see how `TurnManager`'s `monotonicNowMs` is wired by hand in `TurnModule`
 * instead of via `@Inject`, for the same reason). The secondary constructor below lets tests
 * substitute a fixed time source for deterministic createdAt/updatedAt assertions without changing
 * what Metro sees.
 */
@SingleIn(AppScope::class)
class UserAgentRepository @Inject constructor(
  private val database: AppDatabase,
  private val dao: UserAgentDao,
) {
  private var clock: () -> Long = System::currentTimeMillis

  constructor(database: AppDatabase, dao: UserAgentDao, clock: () -> Long) : this(database, dao) {
    this.clock = clock
  }

  /** Published USER agents only, most-recently-updated first — what the roster shows. */
  fun observePublished(): Flow<List<AgentDefinition>> =
    dao.observePublished().map { rows -> rows.map { it.toDefinition() } }

  suspend fun get(id: String): AgentDefinition? = dao.get(id)?.toDefinition()

  /**
   * Upserts [definition] as a DRAFT at configVersion 0, regardless of the status/configVersion the
   * caller passed in — [publish] is the only path that ever advances past DRAFT/0. Preserves the
   * original row's `createdAt` across repeated edits of the same id; only `updatedAt` moves.
   */
  suspend fun saveDraft(definition: AgentDefinition): AgentDefinition {
    val now = clock()
    val existing = dao.get(definition.id)
    val entity = UserAgentEntity.fromDefinition(
      definition = definition.copy(status = AgentStatus.DRAFT, configVersion = 0L),
      createdAt = existing?.createdAt ?: now,
      updatedAt = now,
    )
    dao.upsert(entity)
    return entity.toDefinition()
  }

  /**
   * Validates then publishes [id]'s current row, bumping [AgentDefinition.configVersion] by one.
   * Wrapped in [withTransaction] — not the Room `@Transaction` annotation, which only has an effect
   * on `@Dao` methods — so the validate-then-write stays atomic as this grows to cover child rows
   * (custom skills in Task 4, hooks in Task 8). Today it is a single-row upsert, but the wrapper is
   * already in place for when it is not. Throws (via [AgentValidator.requirePublishable]) and
   * leaves the stored row untouched when the current definition fails validation — validation runs
   * strictly before the write, so a rejected publish never partially applies.
   */
  suspend fun publish(id: String): AgentDefinition = database.withTransaction {
    val current = requireNotNull(dao.get(id)) { "Unknown agent id: $id" }
    AgentValidator.requirePublishable(current.toDefinition())
    val published = current.copy(
      status = AgentStatus.PUBLISHED.name,
      configVersion = current.configVersion + 1,
      updatedAt = clock(),
    )
    dao.upsert(published)
    published.toDefinition()
  }

  /** Archives [id] in place — the row survives (per the [AgentStatus.ARCHIVED] contract), just drops out of [observePublished]. */
  suspend fun archive(id: String) {
    requireNotNull(dao.get(id)) { "Unknown agent id: $id" }
    dao.updateStatus(id, AgentStatus.ARCHIVED.name, clock())
  }

  /** Forks a read-only preset's [AgentDefinition] into a brand new, independently editable USER draft. */
  suspend fun clonePreset(preset: AgentDefinition): AgentDefinition {
    val newId = "${preset.id}-${UUID.randomUUID().toString().take(8)}"
    val cloned = preset.copy(
      id = newId,
      source = AgentSource.USER,
      status = AgentStatus.DRAFT,
      configVersion = 0L,
      memoryPolicy = preset.memoryPolicy.copy(namespace = "agent:$newId:"),
    )
    return saveDraft(cloned)
  }
}
