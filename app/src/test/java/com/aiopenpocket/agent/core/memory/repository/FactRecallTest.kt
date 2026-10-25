package com.aiopenpocket.agent.core.memory.repository

// @trace agent-memory-05e545

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aiopenpocket.agent.loop.TurnContextHolder
import com.aiopenpocket.agent.tools.MemoryTools
import com.aiopenpocket.data.database.AppDatabase
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Две отдельные поломки долговременной памяти, из-за которых она молча деградировала тем сильнее, чем
 * дольше ей пользуются:
 *
 * 1. **Запись.** `remember_fact` шёл в `saveFact`, а тот делает INSERT с НОВЫМ UUID в первичном ключе;
 *    индекс по `key` не уникальный. Поэтому «меня зовут X» → «вообще-то Y» клало ДВЕ строки, обе
 *    попадали в промпт через `getFactsByCategory`/`getByKeyPrefix`, а `getByKey` (LIMIT 1 без ORDER BY)
 *    возвращал произвольную — на практике старую. Агент помнил противоречие и предпочитал устаревшее.
 *
 * 2. **Чтение.** `PrePromptAssembler` звал `searchFacts(currentMessage)`, а DAO подставлял это в
 *    `LIKE '%…%'` ЦЕЛИКОМ. Совпасть могло только сообщение, целиком являющееся подстрокой факта, —
 *    то есть на любой живой фразе секция «Релевантные факты» была пуста.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FactRecallTest {

  private lateinit var db: AppDatabase
  private lateinit var repository: MemoryRepository
  private lateinit var tool: MemoryTools

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    repository = MemoryRepository(db.factDao(), db.episodeDao())
    tool = MemoryTools(repository, TurnContextHolder().also { it.set("conv1", null) })
  }

  @After
  fun tearDown() = db.close()

  private fun remember(key: String, value: String): Map<String, JsonElement> = mapOf(
    "action" to JsonPrimitive("remember_fact"),
    "key" to JsonPrimitive(key),
    "value" to JsonPrimitive(value),
    "category" to JsonPrimitive("profile"),
  )

  // --- 1. Запись: повторный remember_fact обновляет факт, а не плодит дубликаты ------------------

  @Test
  fun `remembering the same key twice keeps exactly one fact`() = runTest {
    tool.execute(remember("user.name", "Иван"))
    tool.execute(remember("user.name", "Пётр"))

    val stored = repository.getFactsByCategory("profile").filter { it.key == "user.name" }

    assertEquals("дубликат факта вместо обновления: $stored", 1, stored.size)
  }

  @Test
  fun `remembering the same key twice keeps the NEW value, not the stale one`() = runTest {
    tool.execute(remember("user.name", "Иван"))
    tool.execute(remember("user.name", "Пётр"))

    assertEquals("Пётр", repository.getFactsByCategory("profile").single { it.key == "user.name" }.value)
  }

  @Test
  fun `two different keys still coexist`() = runTest {
    tool.execute(remember("user.name", "Иван"))
    tool.execute(remember("user.city", "Казань"))

    assertEquals(2, repository.getFactsByCategory("profile").size)
  }

  // --- 2. Чтение: поиск идёт по словам запроса, а не по фразе целиком ----------------------------

  @Test
  fun `a natural-language question finds a fact matching one of its words`() = runTest {
    repository.saveFact("user.city", "Казань", "profile")

    val found = repository.searchFacts("напомни, в каком городе Казань я живу")

    assertTrue("живая фраза не нашла факт: $found", found.any { it.key == "user.city" })
  }

  @Test
  fun `a question matching nothing still returns nothing`() = runTest {
    repository.saveFact("user.city", "Казань", "profile")

    assertTrue(repository.searchFacts("расскажи про квантовую хромодинамику").isEmpty())
  }

  @Test
  fun `short noise words do not drag in every fact`() = runTest {
    repository.saveFact("user.city", "Казань", "profile")
    repository.saveFact("user.pet", "кот", "profile")

    // "я", "в", "и" — шум: если их пускать в LIKE, они совпадут почти со всем подряд.
    val found = repository.searchFacts("я в и")

    assertTrue("шумовые слова вытащили всю память: $found", found.isEmpty())
  }

  @Test
  fun `the namespaced search behaves the same way for a bound agent`() = runTest {
    repository.saveFact("agent:budget:limit", "30000 рублей в месяц", "general")
    repository.saveFact("agent:other:limit", "чужой лимит", "general")

    val found = repository.searchFactsInNamespace("agent:budget:", "какой у меня лимит на месяц")

    assertEquals(listOf("agent:budget:limit"), found.map { it.key })
  }

  // --- 3. Русский: стоп-слова и падежи -----------------------------------------------------------

  @Test
  fun `question words do not eat the token cap, inflected words are stemmed`() {
    // «как», «мою» раньше проходили фильтр длины и занимали места в потолке из восьми слов.
    assertEquals(listOf("зову", "сест"), repository.tokenize("Как зовут мою сестру?"))
  }

  @Test
  fun `a different case of the same word still matches`() = runTest {
    repository.saveFact("user.relative", "сестра Аня", "profile")

    // «сестру» в вопросе и «сестра» в факте как подстроки не совпадают — совпадает основа «сест».
    val found = repository.searchFacts("как зовут мою сестру")

    assertEquals(listOf("user.relative"), found.map { it.key })
  }

  @Test
  fun `numbers are not cut by stemming`() {
    assertEquals(listOf("2026"), repository.tokenize("2026"))
  }

  // --- 4. Промпт: маленькая память целиком ---------------------------------------------------------

  @Test
  fun `small memory goes into the prompt whole, even when no word matches`() = runTest {
    // Английский ключ, русский вопрос: пословно не совпадает ничего, а факт нужен.
    repository.saveFact("family.sister", "Аня", "general")

    val facts = repository.factsForPrompt(namespace = null, message = "как зовут мою сестру", limit = 5)

    assertEquals(listOf("family.sister"), facts.map { it.key })
  }

  @Test
  fun `plain chat prompt memory leaves out agents' working notes`() = runTest {
    repository.saveFact("user.city", "Казань", "profile")
    repository.saveFact("agent:budget:expense:1", "кофе 300", "general")

    val facts = repository.factsForPrompt(namespace = null, message = "привет", limit = 5)

    assertEquals(listOf("user.city"), facts.map { it.key })
  }

  @Test
  fun `large memory falls back to ranked search with a limit`() = runTest {
    repeat(45) { repository.saveFact("note.$it", "заметка номер $it", "general") }
    repository.saveFact("user.city", "Казань", "profile")

    val facts = repository.factsForPrompt(namespace = null, message = "я живу в Казани", limit = 5)

    assertEquals(listOf("user.city"), facts.map { it.key })
  }

  @Test
  fun `bound agent prompt memory is its own namespace only`() = runTest {
    repository.saveFact("agent:budget:limit", "30000", "general")
    repository.saveFact("agent:other:limit", "чужой", "general")
    repository.saveFact("user.city", "Казань", "profile")

    val facts = repository.factsForPrompt(namespace = "agent:budget:", message = "привет", limit = 5)

    assertEquals(listOf("agent:budget:limit"), facts.map { it.key })
  }
}
