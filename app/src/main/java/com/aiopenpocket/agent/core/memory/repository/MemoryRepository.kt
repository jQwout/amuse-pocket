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
   * Факты для секции памяти системного промпта: под [namespace] агента, а без агента — общая память
   * пользователя (без рабочих заметок агентов `agent:…`: расходы бюджета или записи дневника в
   * каждом ходе обычного чата — шум; по запросу их всё равно найдёт `search_memory`).
   *
   * Пока фактов немного (≤ [SMALL_MEMORY_FACTS]), отдаём ВСЕ, свежие первыми, — без поиска по
   * словам. Пословный поиск по-русски слаб даже со стеммингом: факт пишет модель, часто с
   * английским ключом («family.sister = Аня»), и вопрос «как зовут мою сестру» не совпадает с ним
   * ни одним словом. На маленькой памяти дешевле показать всё (длину режет вызывающий), чем угадывать.
   * Дальше — ранжированный поиск, не больше [limit] штук.
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
   * Основы слов [query]: в нижнем регистре, без стоп-слов и слов короче [MIN_TOKEN_LENGTH], без
   * повторов и не более [MAX_TOKENS] штук.
   *
   * Именно отсутствие этого шага и ломало «релевантные факты»: `PrePromptAssembler` передаёт сюда
   * ЦЕЛОЕ сообщение пользователя, а прежний запрос подставлял его в `LIKE '%…%'` целиком — совпасть
   * могла только фраза, целиком являющаяся подстрокой факта, то есть практически никогда.
   *
   * Короткие слова отбрасываются намеренно: «я», «в», «и», «на» совпадают почти с любым фактом и
   * превратили бы поиск в «отдай всю память». Стоп-слова — по той же причине и ещё по одной: «как»,
   * «мою», «что» проходили фильтр длины и съедали потолок [MAX_TOKENS], вытесняя значимые слова.
   * Основа ([stem]) — потому что русский падежный: «сестру» в вопросе и «сестра» в факте как
   * подстроки не совпадают, а их общее начало «сест» — совпадает.
   */
  internal fun tokenize(query: String): List<String> =
    query.split(NON_WORD_REGEX)
      .map { it.lowercase() }
      .filter { it.length >= MIN_TOKEN_LENGTH && it !in STOP_WORDS }
      .map { stem(it) }
      .distinct()
      .take(MAX_TOKENS)

  /**
   * Грубая основа: слово без двух последних букв (обычно там окончание), но не короче
   * [MIN_STEM_LENGTH] — иначе «дом»/«домик» превращались бы в однобуквенный мусор, совпадающий со
   * всем подряд. Числа не режем: «2026» без «26» — уже другое число. Настоящий стеммер был бы
   * точнее, но на памяти из сотен строк ошибка «лишний факт в выдаче» дешевле зависимости.
   */
  private fun stem(word: String): String {
    if (word.length <= MIN_STEM_LENGTH || word.all { it.isDigit() }) return word
    return word.take(maxOf(MIN_STEM_LENGTH, word.length - 2))
  }

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

  /**
   * Эпизоды ОДНОГО диалога в окне [since, until), свежие первыми, не больше [limit]. Прежние
   * `getEpisodesSince/Between` читали журнал всех диалогов и всех агентов без потолка — так
   * `recall_episodes` в чате одного агента приносил вызовы инструментов чужих чатов, целиком.
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

    /** Короче основу не режем — см. [stem]. */
    const val MIN_STEM_LENGTH = 4

    /** Потолок на длинное сообщение: больше слов запрос точнее уже не делают. */
    const val MAX_TOKENS = 8

    /** До стольких фактов память показывается в промпте целиком — см. [factsForPrompt]. */
    const val SMALL_MEMORY_FACTS = 40

    /** Корень пространств имён агентов (`agent:{id}:`, см. AgentMemoryPolicy). */
    const val AGENT_NAMESPACE_ROOT = "agent:"

    /**
     * Служебные слова вопроса: проходят фильтр длины, но о содержании факта не говорят ничего.
     * Только местоимения, вопросительные слова, частицы и глаголы-обращения («скажи», «напомни») —
     * существительные сюда не попадают, даже частые: «имя» или «город» как раз и ищутся.
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
