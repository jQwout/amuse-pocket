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
    rankByQuery(factDao.getAll().map { it.toDomain() }, query)

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
    rankByQuery(getFactsByKeyPrefix(namespace), query)

  /** Every fact stored under an agent's namespace — same shape as a prefix search, named for callers that want "this agent's whole memory" rather than a query match. */
  suspend fun getFactsByNamespace(namespace: String): List<Fact> = getFactsByKeyPrefix(namespace)

  /** Escapes LIKE metacharacters (`\`, `%`, `_`) so user-supplied text can't smuggle in wildcards. */
  private fun escapeLike(raw: String): String = raw
    .replace("\\", "\\\\")
    .replace("%", "\\%")
    .replace("_", "\\_")

  /**
   * Слова [query] длиннее [MIN_TOKEN_LENGTH], без повторов и не более [MAX_TOKENS] штук.
   *
   * Именно отсутствие этого шага и ломало «релевантные факты»: `PrePromptAssembler` передаёт сюда
   * ЦЕЛОЕ сообщение пользователя, а прежний запрос подставлял его в `LIKE '%…%'` целиком — совпасть
   * могла только фраза, целиком являющаяся подстрокой факта, то есть практически никогда.
   *
   * Короткие слова отбрасываются намеренно: «я», «в», «и», «на» совпадают почти с любым фактом и
   * превратили бы поиск в «отдай всю память».
   */
  internal fun tokenize(query: String): List<String> =
    query.split(NON_WORD_REGEX)
      .filter { it.length >= MIN_TOKEN_LENGTH }
      .distinctBy { it.lowercase() }
      .take(MAX_TOKENS)

  /**
   * [facts], оставленные только совпавшие с [query], в порядке убывания числа совпавших слов (при
   * равенстве — свежие раньше). Ранжирование здесь имеет смысл: вызывающий обрезает результат до
   * `LlmContextConfig.RELEVANT_FACTS_LIMIT`, и без порядка в этот срез попадало бы что попало.
   *
   * Сопоставление идёт в Kotlin, а не в SQL, сознательно — по той же причине, по которой поиск по
   * чату живёт в памяти (см. `ChatViewModel`): `contains(ignoreCase = true)` корректно сворачивает
   * регистр кириллицы, а SQL `LIKE` — только ASCII, поэтому запрос «казань» не нашёл бы факт
   * «Казань». Таблица фактов — это личная память масштаба сотен строк, не лог.
   */
  private fun rankByQuery(facts: List<Fact>, query: String): List<Fact> {
    val tokens = tokenize(query)
    if (tokens.isEmpty()) return emptyList()
    return facts
      .map { fact -> fact to tokens.count { fact.key.contains(it, true) || fact.value.contains(it, true) } }
      .filter { (_, matched) -> matched > 0 }
      .sortedWith(compareByDescending<Pair<Fact, Int>> { it.second }.thenByDescending { it.first.updatedAt })
      .map { (fact, _) -> fact }
  }

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

  /**
   * Пишет факт ПО КЛЮЧУ: один ключ — ровно одна строка. Возвращает id записанного факта, либо null,
   * если пустое значение стёрло факт.
   *
   * Это единственный корректный путь записи для повторяющихся ключей, и потому — путь `remember_fact`
   * (см. [com.aiopenpocket.agent.tools.MemoryTools]). Родственный [saveFact] делает INSERT с новым
   * UUID, а индекс по `key` не уникальный: два `remember_fact("user.name", …)` подряд клали ДВЕ
   * строки, обе уезжали в промпт, а [FactDao.getByKey] (LIMIT 1 без ORDER BY) отдавал произвольную —
   * на практике старую. Память тем противоречивее, чем дольше ей пользуются.
   */
  suspend fun upsertFact(key: String, value: String, category: String): String? {
    if (value.isBlank()) {
      factDao.deleteByKey(key)
      return null
    }
    val existing = factDao.getByKey(key)
    val now = System.currentTimeMillis()
    val id = existing?.id ?: UUID.randomUUID().toString()
    factDao.insert(
      FactEntity(
        id = id,
        key = key,
        value = value,
        category = category,
        createdAt = existing?.createdAt ?: now,
        updatedAt = now,
      ),
    )
    return id
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

  private companion object {
    /** Всё, что не буква и не цифра, — граница слова (запятые/точки живого сообщения). */
    val NON_WORD_REGEX = Regex("[^\\p{L}\\p{N}]+")

    /** Короче — шум («я», «в», «на»), совпадающий почти с любым фактом. */
    const val MIN_TOKEN_LENGTH = 3

    /** Потолок на длинное сообщение: больше слов запрос точнее уже не делают. */
    const val MAX_TOKENS = 8
  }
}
