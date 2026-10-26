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
   * `search_memory` for a bound agent (the prompt's own memory section goes through
   * [factsForPrompt], same namespace scoping), so it never sees another agent's (or the global
   * chat's) facts — enforced here at the DAO/WHERE-clause level, not by trusting the caller (model)
   * to only ask for its own namespace.
   */
  suspend fun searchFactsInNamespace(namespace: String, query: String): List<Fact> =
    rankByQuery(getFactsByKeyPrefix(namespace), query)

  /** Every fact stored under an agent's namespace — same shape as a prefix search, named for callers that want "this agent's whole memory" rather than a query match. */
  suspend fun getFactsByNamespace(namespace: String): List<Fact> = getFactsByKeyPrefix(namespace)

  /**
   * Facts for the memory section of the system prompt: under the agent's [namespace], and without an
   * agent — the user's shared memory (without the agents' working notes `agent:…`: budget expenses or
   * diary entries in every turn of a plain chat are noise; `search_memory` still finds them on demand).
   *
   * While there are few facts (≤ [SMALL_MEMORY_FACTS]), ALL of them are returned, freshest first —
   * without a word search. Word-by-word search in Russian is weak even with stemming: the model writes
   * the fact, often with an English key ("family.sister = Аня"), and the question "what is my sister's
   * name" does not share a single word with it. For a small memory it is cheaper to show everything
   * (the caller cuts the length) than to guess. Beyond that — a ranked search, no more than [limit].
   */
  suspend fun factsForPrompt(namespace: String?, message: String, limit: Int): List<Fact> {
    val pool = if (namespace != null) {
      getFactsByKeyPrefix(namespace)
    } else {
      factDao.getAll().map { it.toDomain() }.filterNot { it.key.startsWith(AGENT_NAMESPACE_ROOT) }
    }
    if (pool.size <= SMALL_MEMORY_FACTS) return pool.sortedByDescending { it.updatedAt }
    return rankByQuery(pool, message).take(limit)
  }

  /** Escapes LIKE metacharacters (`\`, `%`, `_`) so user-supplied text can't smuggle in wildcards. */
  private fun escapeLike(raw: String): String = raw
    .replace("\\", "\\\\")
    .replace("%", "\\%")
    .replace("_", "\\_")

  /**
   * Stems of the words of [query]: lowercased, without stop words and words shorter than
   * [MIN_TOKEN_LENGTH], without repeats and no more than [MAX_TOKENS].
   *
   * The absence of exactly this step is what broke "relevant facts": `PrePromptAssembler` passes the
   * WHOLE user message here, and the old query put it into `LIKE '%…%'` as is — only a phrase that was
   * entirely a substring of a fact could match, i.e. practically never.
   *
   * Short words are dropped on purpose: "я", "в", "и", "на" (I, in, and, on) match almost any fact and
   * would turn the search into "return the whole memory". Stop words — for the same reason and one
   * more: "как", "мою", "что" (how, my, what) passed the length filter and ate the [MAX_TOKENS] ceiling,
   * pushing out meaningful words. The stem ([stem]) — because Russian is inflected: "сестру" in the
   * question and "сестра" in the fact (sister, in two cases) do not match as substrings, while their
   * common start "сест" does.
   */
  internal fun tokenize(query: String): List<String> =
    query.split(NON_WORD_REGEX)
      .map { it.lowercase() }
      .filter { it.length >= MIN_TOKEN_LENGTH && it !in STOP_WORDS }
      .map { stem(it) }
      .distinct()
      .take(MAX_TOKENS)

  /**
   * A rough stem: the word without its last two letters (usually the ending there), but not shorter than
   * [MIN_STEM_LENGTH] — otherwise "дом"/"домик" (house/little house) would become one-letter garbage
   * matching everything. Numbers are not cut: "2026" without "26" is a different number. A real stemmer
   * would be more accurate, but on a memory of hundreds of rows the error "an extra fact in the results"
   * is cheaper than a dependency.
   */
  private fun stem(word: String): String {
    if (word.length <= MIN_STEM_LENGTH || word.all { it.isDigit() }) return word
    return word.take(maxOf(MIN_STEM_LENGTH, word.length - 2))
  }

  /**
   * [facts] filtered down to those matching [query], in descending order of the number of matched
   * words (on a tie — fresher first). Ranking makes sense here: the caller cuts the result to
   * `LlmContextConfig.RELEVANT_FACTS_LIMIT`, and without an order that slice would get whatever.
   *
   * Matching happens in Kotlin, not in SQL, on purpose — for the same reason chat search lives in
   * memory (see `ChatViewModel`): `contains(ignoreCase = true)` folds Cyrillic case correctly, while
   * SQL `LIKE` folds only ASCII, so the query "казань" would not find the fact "Казань". The facts table
   * is a personal memory of hundreds of rows, not a log.
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
   * Writes a fact BY KEY: one key — exactly one row. Returns the id of the written fact, or null if an
   * empty value erased the fact.
   *
   * This is the only correct write path for repeated keys, and hence the path of `remember_fact` (see
   * [com.aiopenpocket.agent.tools.MemoryTools]). Its sibling [saveFact] does an INSERT with a new UUID,
   * and the index on `key` is not unique: two `remember_fact("user.name", …)` in a row put TWO rows,
   * both went into the prompt, and [FactDao.getByKey] (LIMIT 1 without ORDER BY) returned an arbitrary
   * one — in practice the old one. The longer the memory was used, the more contradictory it got.
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

  suspend fun getRecentEpisodesForConversation(conversationId: String, limit: Int): List<Episode> =
    episodeDao.getRecentForConversation(conversationId, limit).map { it.toDomain() }

  /**
   * Episodes of ONE conversation in the window [since, until), freshest first, no more than [limit].
   * The old `getEpisodesSince/Between` read the log of all conversations and all agents without a
   * ceiling — so `recall_episodes` in one agent's chat brought back other chats' tool calls, whole.
   */
  suspend fun getEpisodesForConversationBetween(
    conversationId: String,
    since: Long,
    until: Long,
    limit: Int,
  ): List<Episode> =
    episodeDao.getForConversationBetween(conversationId, since, until, limit).map { it.toDomain() }

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
    /** Everything that is not a letter or a digit is a word boundary (the commas/periods of a live message). */
    val NON_WORD_REGEX = Regex("[^\\p{L}\\p{N}]+")

    /** Shorter is noise ("я", "в", "на" — I, in, on) that matches almost any fact. */
    const val MIN_TOKEN_LENGTH = 3

    /** A stem is never cut shorter than this — see [stem]. */
    const val MIN_STEM_LENGTH = 4

    /** The ceiling for a long message: more words no longer make the query more precise. */
    const val MAX_TOKENS = 8

    /** Up to this many facts the memory is shown in the prompt whole — see [factsForPrompt]. */
    const val SMALL_MEMORY_FACTS = 40

    /** The root of agents' namespaces (`agent:{id}:`, see AgentMemoryPolicy). */
    const val AGENT_NAMESPACE_ROOT = "agent:"

    /**
     * Function words of a question: they pass the length filter but say nothing about a fact's content.
     * Only pronouns, question words, particles and addressing verbs ("скажи", "напомни" — tell, remind)
     * — nouns do not go here, even frequent ones: "имя" (name) or "город" (city) are exactly what is
     * searched for.
     */
    val STOP_WORDS = setOf(
      "как", "что", "это", "эта", "этот", "эти", "тот", "так", "там", "тут", "где", "кто", "чем",
      "чего", "кого", "кому", "когда", "какой", "какая", "какое", "какие", "каком", "какую", "каких",
      "сколько", "почему", "зачем", "или", "для", "при", "над", "под", "без", "про", "через", "после",
      "все", "всё", "вот", "уже", "ещё", "еще", "был", "была", "было", "были", "есть", "будет", "нет",
      "мой", "моя", "мою", "моё", "мое", "мои", "моих", "моей", "моего", "мне", "меня", "мной",
      "твой", "твоя", "твою", "твои", "тебя", "тебе", "его", "её", "нее", "неё", "них", "она", "они",
      "оно", "нам", "нас", "наш", "наша", "вам", "вас", "ваш", "себя", "свой", "свою", "свои",
      "если", "чтобы", "тоже", "также", "можно", "нужно", "надо", "очень", "пожалуйста", "привет",
      "скажи", "напомни", "расскажи", "подскажи", "помнишь", "знаешь", "покажи", "найди",
      "the", "and", "what", "who", "how", "where", "when", "you", "your", "are",
    )
  }
}
