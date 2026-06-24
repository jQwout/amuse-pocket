package com.aiopenpocket.agent.core.memory.repository

// @trace agent-memory-05e545

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
class MemoryRepository @Inject constructor(
  private val factDao: FactDao,
  private val episodeDao: EpisodeDao,
) {
  suspend fun searchFacts(query: String): List<Fact> {
    // Экранируем спецсимволы LIKE, иначе '%'/'_' в запросе работают как wildcard.
    val escaped = query
      .replace("\\", "\\\\")
      .replace("%", "\\%")
      .replace("_", "\\_")
    return factDao.search(escaped).map { it.toDomain() }
  }

  suspend fun getFactsByCategory(category: String): List<Fact> =
    factDao.getByCategory(category).map { it.toDomain() }

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
