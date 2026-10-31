package com.aiopenpocket.agent.core.agent

// @trace user-agent-builder-848153

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aiopenpocket.agent.core.hook.AgentHookRepository
import com.aiopenpocket.agent.loop.AgentMode
import com.aiopenpocket.data.database.AppDatabase
import com.aiopenpocket.i18n.AppLanguage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The hidden Builder agent: invisible in every list a person picks agents from, still resolvable for the chat
 * bound to it, and told (in both languages) exactly what the validator will accept.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BuilderAgentTest {

  private val testDispatcher = UnconfinedTestDispatcher()
  private lateinit var db: AppDatabase
  private lateinit var resolver: AgentResolver

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
      .allowMainThreadQueries()
      .setQueryExecutor(testDispatcher.asExecutor())
      .setTransactionExecutor(testDispatcher.asExecutor())
      .build()
    resolver = DefaultAgentResolver(UserAgentRepository(db, db.userAgentDao(), AgentHookRepository(db.agentHookDao())))
  }

  @After
  fun tearDown() {
    AppLanguage.code = AppLanguage.RU
    db.close()
  }

  // --- hidden ---

  @Test
  fun `the roster does not show the builder`() = runTest {
    val roster = resolver.observeRoster().first()

    assertTrue("builder leaked into the roster", roster.none { it.id == BuilderAgent.ID })
    assertEquals(MiniAgentCatalog.all.map { it.id }, roster.map { it.id })
  }

  @Test
  fun `the preset catalog list does not contain the builder`() {
    assertTrue(MiniAgentCatalog.all.none { it.id == BuilderAgent.ID })
  }

  // --- but resolvable ---

  @Test
  fun `a chat bound to the builder resolves to its definition`() = runTest {
    val builder = resolver.resolve(BuilderAgent.ID)

    assertNotNull(builder)
    builder!!
    assertEquals(setOf("agent_draft", "render_card"), builder.toolNames)
    assertEquals(setOf(AgentMode.CHAT), builder.allowedModes)
    assertEquals(setOf("multi_choice", "choice"), builder.allowedWidgetTemplates)
    assertEquals(AgentSource.PRESET, builder.source)
    assertEquals(AgentStatus.PUBLISHED, builder.status)
  }

  @Test
  fun `a user agent cannot take the builder's id`() {
    val clash = MiniAgentCatalog.get("budget")!!.toDefinition().copy(
      id = BuilderAgent.ID,
      source = AgentSource.USER,
      memoryPolicy = AgentMemoryPolicy("agent:${BuilderAgent.ID}:", false),
    )

    assertTrue(AgentValidator.validate(clash).any { it.code == "preset_id_collision" })
  }

  @Test
  fun `the builder has a name in both languages`() {
    AppLanguage.code = AppLanguage.EN
    assertEquals("Agent builder", BuilderAgent.agent.displayName)
    AppLanguage.code = AppLanguage.RU
    assertEquals("Конструктор агентов", BuilderAgent.agent.displayName)
  }

  // --- the prompt ---

  @Test
  fun `every tool the validator allows has a plain-words line for the prompt`() {
    assertEquals(AgentValidator.KNOWN_TOOL_NAMES, BuilderAgent.TOOL_PURPOSES.keys)
  }

  @Test
  fun `the prompt names every allowed tool, mode and model in both languages`() {
    for (prompt in listOf(BuilderAgent.agent.systemPromptEn, BuilderAgent.agent.systemPrompt)) {
      AgentValidator.KNOWN_TOOL_NAMES.forEach { assertTrue("tool $it missing", prompt.contains(it)) }
      BuilderAgent.ALLOWED_MODES.forEach { assertTrue("mode $it missing", prompt.contains(it.name)) }
      BuilderAgent.ALLOWED_MODELS.forEach { assertTrue("model $it missing", prompt.contains(it)) }
    }
  }

  @Test
  fun `the prompt does not offer tools the chat should not suggest`() {
    val lines = BuilderAgent.agent.systemPromptEn.lines()
    BuilderAgent.ADVANCED_TOOLS.forEach { tool ->
      val line = lines.first { it.startsWith("- $tool:") }
      assertTrue(line, line.contains("do not suggest"))
    }
  }

  @Test
  fun `the prompt carries the rules of the flow`() {
    val en = BuilderAgent.agent.systemPromptEn
    assertTrue(en.contains("agent_draft"))
    assertTrue(en.contains("multi_choice"))
    assertTrue(en.contains("finish"))
    assertTrue("must forbid publishing", en.contains("never publish", ignoreCase = true))
    assertTrue("one question per card", en.contains("ONE question per card"))
    assertTrue("option cap", en.contains("4 at most"))
  }

  @Test
  fun `the prompt follows the interface language`() {
    AppLanguage.code = AppLanguage.EN
    assertEquals(BuilderAgent.agent.systemPromptEn, BuilderAgent.agent.localizedSystemPrompt)
    AppLanguage.code = AppLanguage.RU
    assertEquals(BuilderAgent.agent.systemPrompt, BuilderAgent.agent.localizedSystemPrompt)
  }
}
