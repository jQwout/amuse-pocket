package com.aiopenpocket.agent.core.memory.repository

// @trace agent-memory-05e545
// @trace user-agent-builder-848153

import com.aiopenpocket.agent.core.memory.dao.EpisodeDao
import com.aiopenpocket.agent.core.memory.dao.FactDao
import com.aiopenpocket.agent.core.memory.entity.EpisodeEntity
import com.aiopenpocket.agent.core.memory.entity.FactEntity
import com.aiopenpocket.agent.core.memory.model.Episode
import com.aiopenpocket.agent.core.memory.model.Fact
import com.aiopenpocket.agent.core.memory.model.UserProfile
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import com.aiopenpocket.di.AppScope
import java.util.UUID

@SingleIn(AppScope::class)
@Inject
class MemoryRepository(
  private val factDao: FactDao,
  private val episodeDao: EpisodeDao,
) {
  suspend fun searchFacts(query: String): List<Fact> =
    factDao.search(escapeLike(query)).map { it.toDomain() }

  suspend fun getFactsByCategory(category: String): List<Fact> =
    factDao.getByCategory(category).map { it.toDomain() }

  suspend fun getFactsByKeyPrefix(prefix: String): List<Fact> =
    factDao.getByKeyPrefix(escapeLike(prefix)).map { it.toDomain() }

  /**
   * [query]-matched facts confined to [namespace] (an agent's `agent:{id}:` key prefix) — the
   * per-agent counterpart of [searchFacts]. Used by [com.aiopenpocket.agent.tools.MemoryTools]'s
   * `search_memory` for a bound agent and by `PrePromptAssembler`'s relevant-facts section, so
   * neither ever sees another agent's (or the global chat's) facts — enforced here at the
   * DAO/WHERE-clause level, not by trusting the caller (model) to only ask for its own namespace.
   */
  suspend fun searchFactsInNamespace(namespace: String, query: String): List<Fact> =
    factDao.searchInNamespace(escapeLike(namespace), escapeLike(query)).map { it.toDomain() }

  /** Every fact stored under an agent's namespace — same shape as a prefix search, named for callers that want "this agent's whole memory" rather than a query match. */
  suspend fun getFactsByNamespace(namespace: String): List<Fact> = getFactsByKeyPrefix(namespace)

  /** Escapes LIKE metacharacters (`\`, `%`, `_`) so user-supplied text can't smuggle in wildcards. */
  private fun escapeLike(raw: String): String = raw
    .replace("\\", "\\\\")
    .replace("%", "\\%")
    .replace("_", "\\_")

  suspend fun saveFact(key: String, value: String, category: String): String {
    val id = UUID.randomUUID().toString()
    val now = System.currentTimeMillis()
    factDao.insert(
      FactEntity(
        id = id,
        key = key,
        value = value,
        category = category,
        createdAt = now,
        updatedAt = now,
      ),
    )
    return id
  }

  suspend fun upsertFact(key: String, value: String, category: String) {
    if (value.isBlank()) {
      factDao.deleteByKey(key)
      return
    }
    val existing = factDao.getByKey(key)
    val now = System.currentTimeMillis()
    factDao.insert(
      FactEntity(
        id = existing?.id ?: UUID.randomUUID().toString(),
        key = key,
        value = value,
        category = category,
        createdAt = existing?.createdAt ?: now,
        updatedAt = now,
      ),
    )
  }

  suspend fun saveUserProfile(profile: UserProfile) {
    upsertFact("user.name", profile.name.trim(), "profile")
    upsertFact("user.preferred_address", profile.preferredAddress.trim(), "profile")
    upsertFact("user.age", profile.age.trim(), "profile")
    upsertFact("user.gender", profile.gender.trim(), "profile")
    upsertFact("user.religion", profile.religion.trim(), "profile")
    upsertFact("user.extra_info", profile.extraInfo.trim(), "profile")
  }

  suspend fun deleteFact(id: String) {
    factDao.delete(id)
  }

  suspend fun getRecentEpisodes(limit: Int = 10): List<Episode> =
    episodeDao.getRecent(limit).map { it.toDomain() }

  suspend fun getRecentEpisodesForConversation(conversationId: String, limit: Int): List<Episode> =
    episodeDao.getRecentForConversation(conversationId, limit).map { it.toDomain() }

  suspend fun getEpisodesSince(since: Long): List<Episode> =
    episodeDao.getSince(since).map { it.toDomain() }

  suspend fun getEpisodesBetween(since: Long, until: Long): List<Episode> =
    episodeDao.getBetween(since, until).map { it.toDomain() }

  suspend fun saveEpisode(
    type: String,
    payload: String,
    outcome: String,
    conversationId: String? = null,
  ): String {
    val id = UUID.randomUUID().toString()
    episodeDao.insert(
      EpisodeEntity(
        id = id,
        type = type,
        payload = payload,
        outcome = outcome,
        conversationId = conversationId,
      ),
    )
    return id
  }

  suspend fun deleteEpisode(id: String) {
    episodeDao.delete(id)
  }

  private fun FactEntity.toDomain() = Fact(
    id = id,
    key = key,
    value = value,
    category = category,
    createdAt = createdAt,
    updatedAt = updatedAt,
  )

  private fun EpisodeEntity.toDomain() = Episode(
    id = id,
    type = type,
    payload = payload,
    outcome = outcome,
    timestamp = timestamp,
    conversationId = conversationId,
  )
}
