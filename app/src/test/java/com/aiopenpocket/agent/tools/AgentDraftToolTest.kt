package com.aiopenpocket.agent.tools

// @trace user-agent-builder-848153
// @trace driven-ui-cards-0418d0

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aiopenpocket.agent.core.agent.AgentStatus
import com.aiopenpocket.agent.core.agent.AgentValidator
import com.aiopenpocket.agent.core.agent.BuilderAgent
import com.aiopenpocket.agent.core.agent.MiniAgentCatalog
import com.aiopenpocket.agent.core.agent.UserAgentRepository
import com.aiopenpocket.agent.core.agent.toDefinition
import com.aiopenpocket.agent.core.hook.AgentHookRepository
import com.aiopenpocket.agent.core.mcp.RiskClass
import com.aiopenpocket.agent.core.mcp.ToolResult
import com.aiopenpocket.agent.core.skill.UserSkillEntity
import com.aiopenpocket.agent.loop.AgentMode
import com.aiopenpocket.agent.loop.TurnContextHolder
import com.aiopenpocket.data.database.AppDatabase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `agent_draft` is the only thing the model builds an agent with, so what it will NOT do matters as much as
 * what it does: it must never publish, never touch a row that is not this conversation's draft, and never
 * save a half-applied update. Runs against a real in-memory Room database, like the other repository-backed
 * tool tests, so "persisted" and "still a draft" are read back from SQL, not from a fake.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentDraftToolTest {

  private val testDispatcher = UnconfinedTestDispatcher()

  private lateinit var db: AppDatabase
  private lateinit var repository: UserAgentRepository
  private lateinit var holder: TurnContextHolder
  private lateinit var tool: AgentDraftTool

  private val conversationId = "3f2a9c1e-7b4d-4e8a-9d11-0a1b2c3d4e5f"
  private val draftId get() = AgentDraftTool.draftIdFor(conversationId)!!

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
      .allowMainThreadQueries()
      .setQueryExecutor(testDispatcher.asExecutor())
      .setTransactionExecutor(testDispatcher.asExecutor())
      .build()
    repository = UserAgentRepository(db, db.userAgentDao(), AgentHookRepository(db.agentHookDao()))
    holder = TurnContextHolder()
    holder.set(conversationId, BuilderAgent.agent.toDefinition())
    tool = AgentDraftTool(repository, holder)
  }

  @After
  fun tearDown() = db.close()

  private fun args(action: String, vararg more: Pair<String, JsonElement>): Map<String, JsonElement> =
    mapOf("action" to JsonPrimitive(action)) + more

  private fun list(vararg items: String) = JsonArray(items.map { JsonPrimitive(it) })

  /** A complete, valid update: every field AgentValidator insists on. */
  private suspend fun fillDraft(vararg more: Pair<String, JsonElement>): ToolResult = tool.execute(
    args(
      "update",
      "name" to JsonPrimitive("Budget buddy"),
      "description" to JsonPrimitive("Tracks what I spend."),
      "instructions" to JsonPrimitive("You are Budget buddy. Keep answers short."),
      *more,
    ),
  )

  private fun ToolResult.message(): String = when (this) {
    is ToolResult.Success -> data
    is ToolResult.Error -> message
  }

  // --- update persists ---

  @Test
  fun `update saves the draft as an ordinary DRAFT user agent`() = runTest {
    val result = fillDraft("tools" to list("memory_tool", "heartbeat"))

    assertTrue(result.message(), result is ToolResult.Success)
    val stored = repository.get(draftId)
    assertNotNull(stored)
    assertEquals("Budget buddy", stored!!.name)
    assertEquals("Tracks what I spend.", stored.description)
    assertEquals("You are Budget buddy. Keep answers short.", stored.systemPrompt)
    assertEquals(setOf("memory_tool", "heartbeat"), stored.toolNames)
    assertEquals(AgentStatus.DRAFT, stored.status)
    assertEquals("agent:$draftId:", stored.memoryPolicy.namespace)
  }

  @Test
  fun `a later update changes only the fields it sends`() = runTest {
    fillDraft("tools" to list("memory_tool"))

    tool.execute(args("update", "name" to JsonPrimitive("Spending coach")))

    val stored = repository.get(draftId)!!
    assertEquals("Spending coach", stored.name)
    assertEquals("Tracks what I spend.", stored.description)
    assertEquals(setOf("memory_tool"), stored.toolNames)
  }

  @Test
  fun `model wonder, spark and default are stored as the family key or null`() = runTest {
    fillDraft("model" to JsonPrimitive("wonder"))
    assertEquals("WONDER", repository.get(draftId)!!.model)

    tool.execute(args("update", "model" to JsonPrimitive("Spark")))
    assertEquals("SPARK", repository.get(draftId)!!.model)

    tool.execute(args("update", "model" to JsonPrimitive("default")))
    assertNull(repository.get(draftId)!!.model)
  }

  @Test
  fun `modes keep the default mode among the allowed ones`() = runTest {
    fillDraft("modes" to list("PLAN", "GOAL"))

    val stored = repository.get(draftId)!!
    assertEquals(setOf(AgentMode.PLAN, AgentMode.GOAL), stored.allowedModes)
    assertTrue(stored.defaultMode in stored.allowedModes)
  }

  @Test
  fun `an array sent as a comma separated string is understood`() = runTest {
    fillDraft("tools" to JsonPrimitive("memory_tool, alarm"))

    assertEquals(setOf("memory_tool", "alarm"), repository.get(draftId)!!.toolNames)
  }

  // --- unknown values are rejected, listing the allowed ones ---

  @Test
  fun `an unknown tool is a recoverable error listing the allowed tools and saves nothing`() = runTest {
    val result = fillDraft("tools" to list("memory_tool", "launch_missiles"))

    assertTrue(result is ToolResult.Error)
    result as ToolResult.Error
    assertTrue(result.recoverable)
    assertTrue(result.message, result.message.contains("launch_missiles"))
    assertTrue("must list what is allowed", result.message.contains("heartbeat") && result.message.contains("memory_tool"))
    assertNull("a rejected update must not be half applied", repository.get(draftId))
  }

  @Test
  fun `an unknown mode or model is rejected with the allowed values`() = runTest {
    val badMode = fillDraft("modes" to list("TUTOR"))
    val badModel = fillDraft("model" to JsonPrimitive("gpt-9"))

    assertTrue(badMode.message(), badMode is ToolResult.Error && badMode.message.contains("CHAT, PLAN, GOAL, REFLECT"))
    assertTrue(badModel.message(), badModel is ToolResult.Error && badModel.message.contains("WONDER, SPARK, default"))
    assertNull(repository.get(draftId))
  }

  // --- finish validates ---

  @Test
  fun `finish on an incomplete draft returns the problems and no card`() = runTest {
    tool.execute(args("update", "name" to JsonPrimitive("Budget buddy")))

    val result = tool.execute(args("finish"))

    assertTrue(result is ToolResult.Error)
    result as ToolResult.Error
    assertTrue(result.recoverable)
    assertTrue(result.message, result.message.contains("description") && result.message.contains("systemPrompt"))
    assertFalse(BuilderAgent.isReviewCard(result.message))
  }

  @Test
  fun `finish before any draft exists is a recoverable error`() = runTest {
    val result = tool.execute(args("finish"))

    assertTrue(result is ToolResult.Error && result.recoverable)
  }

  @Test
  fun `finish on a valid draft returns the review card for this conversation's draft`() = runTest {
    fillDraft("tools" to list("memory_tool"))

    val result = tool.execute(args("finish"))

    assertTrue(result.message(), result is ToolResult.Success)
    val data = (result as ToolResult.Success).data
    assertTrue(BuilderAgent.isReviewCard(data))
    assertTrue(data.contains("\"agent_id\":\"$draftId\""))
    assertTrue(data.contains("Budget buddy"))
  }

  // --- it never publishes ---

  @Test
  fun `finish leaves the agent a draft, out of the roster`() = runTest {
    fillDraft()

    tool.execute(args("finish"))

    assertEquals(AgentStatus.DRAFT, repository.get(draftId)!!.status)
    assertTrue(repository.observePublished().first().isEmpty())
  }

  @Test
  fun `there is no publish action`() = runTest {
    fillDraft()

    val result = tool.execute(args("publish"))

    assertTrue(result is ToolResult.Error)
    assertEquals(AgentStatus.DRAFT, repository.get(draftId)!!.status)
    val actions = tool.schema.function.parameters.toString()
    assertFalse("the schema must not offer publish", actions.contains("publish"))
  }

  @Test
  fun `a draft the person has since published can no longer be changed from the chat`() = runTest {
    fillDraft()
    repository.publish(draftId)

    val result = tool.execute(args("update", "name" to JsonPrimitive("Sneaky rename")))

    assertTrue(result is ToolResult.Error)
    assertEquals("Budget buddy", repository.get(draftId)!!.name)
    assertEquals(AgentStatus.PUBLISHED, repository.get(draftId)!!.status)
  }

  // --- binding ---

  @Test
  fun `the draft belongs to the conversation and two conversations get two drafts`() = runTest {
    fillDraft()
    holder.set("11111111-2222-3333-4444-555555555555", BuilderAgent.agent.toDefinition())

    tool.execute(args("update", "name" to JsonPrimitive("Another agent")))

    assertEquals("Budget buddy", repository.get(draftId)!!.name)
    assertEquals("Another agent", repository.get(AgentDraftTool.draftIdFor("11111111-2222-3333-4444-555555555555")!!)!!.name)
  }

  @Test
  fun `the draft id is accepted by the agent id format and cannot be a preset id`() {
    val id = AgentDraftTool.draftIdFor(conversationId)!!

    assertTrue(id, AgentValidator.ID_REGEX.matches(id))
    assertNull(MiniAgentCatalog.get(id))
  }

  @Test
  fun `other agents and plain chat cannot use it`() = runTest {
    holder.set(conversationId, MiniAgentCatalog.get("budget")!!.toDefinition())
    val fromOtherAgent = fillDraft()
    holder.set(conversationId, null)
    val fromPlainChat = fillDraft()

    assertTrue(fromOtherAgent is ToolResult.Error && !fromOtherAgent.recoverable)
    assertTrue(fromPlainChat is ToolResult.Error && !fromPlainChat.recoverable)
    assertNull(repository.get(draftId))
  }

  @Test
  fun `a chat edit keeps the custom skills added on the review screen`() = runTest {
    fillDraft()
    db.userSkillDao().upsert(
      UserSkillEntity(
        id = "my-skill", agentId = draftId, name = "S", description = "d", whenToUse = "w",
        instructionsMarkdown = "do it", createdAt = 1L, updatedAt = 1L,
      ),
    )

    tool.execute(args("update", "name" to JsonPrimitive("Renamed")))

    assertEquals(listOf("my-skill"), db.userSkillDao().getByAgent(draftId).map { it.id })
  }

  // --- risk ---

  @Test
  fun `only update writes, and the tool is classed WRITE overall`() {
    assertEquals(RiskClass.WRITE, tool.riskClass)
    assertEquals(RiskClass.WRITE, tool.riskFor(args("update")))
    assertEquals(RiskClass.READ, tool.riskFor(args("get")))
    assertEquals(RiskClass.READ, tool.riskFor(args("finish")))
    assertEquals(RiskClass.WRITE, tool.riskFor(emptyMap()))
  }
}
