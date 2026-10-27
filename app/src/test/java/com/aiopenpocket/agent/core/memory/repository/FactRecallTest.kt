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
 * Two separate breakages of long-term memory, because of which it silently degraded the more it was used:
 *
 * 1. **Writing.** `remember_fact` went to `saveFact`, which does an INSERT with a NEW UUID in the primary
 *    key; the index on `key` is not unique. So "my name is X" → "actually, Y" stored TWO rows, both got
 *    into the prompt through `getFactsByCategory`/`getByKeyPrefix`, and `getByKey` (LIMIT 1 without
 *    ORDER BY) returned an arbitrary one — in practice the old one. The agent remembered a contradiction
 *    and preferred the outdated fact.
 *
 * 2. **Reading.** `PrePromptAssembler` called `searchFacts(currentMessage)`, and the DAO put it into
 *    `LIKE '%…%'` AS A WHOLE. Only a message that was entirely a substring of a fact could match — that
 *    is, on any real phrase the "Relevant facts" section was empty.
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

  // --- 1. Writing: a repeated remember_fact updates the fact instead of breeding duplicates -----------

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

  // --- 2. Reading: the search goes by the words of the query, not by the whole phrase ----------------

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

    // "я", "в", "и" are noise: let into LIKE, they would match almost everything.
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

  // --- 3. Russian: stop words and cases -------------------------------------------------------------

  @Test
  fun `question words do not eat the token cap, inflected words are stemmed`() {
    // «как», «мою» used to pass the length filter and take places in the eight-word ceiling.
    assertEquals(listOf("зову", "сест"), repository.tokenize("Как зовут мою сестру?"))
  }

  @Test
  fun `a different case of the same word still matches`() = runTest {
    repository.saveFact("user.relative", "сестра Аня", "profile")

    // «сестру» in the question and «сестра» in the fact do not match as substrings — the stem «сест» does.
    val found = repository.searchFacts("как зовут мою сестру")

    assertEquals(listOf("user.relative"), found.map { it.key })
  }

  @Test
  fun `numbers are not cut by stemming`() {
    assertEquals(listOf("2026"), repository.tokenize("2026"))
  }

  // --- 4. Prompt: a small memory goes in whole -------------------------------------------------------

  @Test
  fun `small memory goes into the prompt whole, even when no word matches`() = runTest {
    // An English key, a Russian question: nothing matches word by word, yet the fact is needed.
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
