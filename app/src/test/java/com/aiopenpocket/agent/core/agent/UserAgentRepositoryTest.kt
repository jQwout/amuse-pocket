package com.aiopenpocket.agent.core.agent

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aiopenpocket.agent.core.hook.AgentHook
import com.aiopenpocket.agent.core.hook.AgentHookRepository
import com.aiopenpocket.agent.core.hook.HookActionType
import com.aiopenpocket.agent.core.hook.HookEvent
import com.aiopenpocket.agent.core.skill.UserSkillEntity
import com.aiopenpocket.agent.loop.AgentMode
import com.aiopenpocket.data.database.AppDatabase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [UserAgentRepository] round-tripped through a real (in-memory) Room database — proves the JSON
 * collection columns, enum-as-name columns and the publish/archive transitions all survive a real
 * SQLite write+read, not just an in-memory fake.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UserAgentRepositoryTest {

  private val testDispatcher = UnconfinedTestDispatcher()

  private lateinit var db: AppDatabase
  private lateinit var repository: UserAgentRepository
  private var now = 1_000_000L

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
      .allowMainThreadQueries()
      .setQueryExecutor(testDispatcher.asExecutor())
      .setTransactionExecutor(testDispatcher.asExecutor())
      .build()
    repository = UserAgentRepository(db, db.userAgentDao(), AgentHookRepository(db.agentHookDao())) { now }
  }

  @After
  fun tearDown() = db.close()

  private fun draft(
    id: String = "my-test-agent",
    toolNames: Set<String> = setOf("memory_tool"),
  ) = AgentDefinition(
    id = id,
    source = AgentSource.USER,
    name = "Тестовый агент",
    description = "Агент для проверки repository.",
    iconKey = "sparkles",
    systemPrompt = "Ты — тестовый агент для unit-тестов.",
    toolNames = toolNames,
    skillIds = emptySet(),
    defaultMode = AgentMode.CHAT,
    allowedModes = setOf(AgentMode.CHAT),
    memoryPolicy = AgentMemoryPolicy(namespace = "agent:$id:", includeSharedProfile = false),
    allowedWidgetTemplates = emptySet(),
    hooks = emptyList(),
    configVersion = 0L,
    status = AgentStatus.DRAFT,
  )

  @Test
  fun `observePublished skips an agent row whose enum no longer decodes instead of crashing the roster (D-tail)`() = runTest {
    repository.saveDraft(draft(id = "good"))
    repository.publish("good")
    // A PUBLISHED row with a defaultMode this build no longer knows (renamed enum): toDefinition's
    // AgentMode.valueOf throws. Before quarantine this crashes the roster Flow AgentsViewModel collects
    // -> the agents screen crash-loops on every open.
    db.userAgentDao().upsert(
      UserAgentEntity(
        id = "bad", name = "Битый", description = "d", iconKey = "sparkles", systemPrompt = "s",
        toolNames = emptyList(), skillIds = emptyList(), allowedModes = listOf("CHAT"),
        allowedWidgetTemplates = emptyList(), defaultMode = "RENAMED_MODE", memoryNamespace = "agent:bad:",
        includeSharedProfile = false, status = "PUBLISHED", configVersion = 1L, createdAt = 1L, updatedAt = 2L,
      ),
    )

    val agents = repository.observePublished().first()

    assertEquals(listOf("good"), agents.map { it.id })
  }

  @Test
  fun `full draft to publish to archive lifecycle`() = runTest {
    val draft = draft()

    repository.saveDraft(draft)
    assertTrue(repository.observePublished().first().isEmpty())

    val published = repository.publish(draft.id)
    assertEquals(AgentStatus.PUBLISHED, published.status)
    assertEquals(1L, published.configVersion)
    assertEquals(listOf(draft.id), repository.observePublished().first().map { it.id })

    repository.archive(draft.id)
    assertNotNull(repository.get(draft.id)) // archived definition survives, not deleted
    assertEquals(AgentStatus.ARCHIVED, repository.get(draft.id)?.status)
    assertTrue("archived agents drop out of observePublished", repository.observePublished().first().isEmpty())
  }

  @Test
  fun `saveDraft round-trips every JSON and enum column through Room`() = runTest {
    val original = draft(id = "round-trip-agent", toolNames = setOf("memory_tool", "render_card")).copy(
      skillIds = setOf("budget"),
      allowedModes = setOf(AgentMode.CHAT, AgentMode.PLAN, AgentMode.GOAL),
      allowedWidgetTemplates = setOf("reminder", "table"),
      defaultMode = AgentMode.PLAN,
    )

    val saved = repository.saveDraft(original)

    assertEquals(original, saved)
    assertEquals(original, repository.get(original.id))
  }

  @Test
  fun `saveDraft forces DRAFT status and zero configVersion regardless of caller input`() = runTest {
    val lookingAlreadyPublished = draft().copy(status = AgentStatus.PUBLISHED, configVersion = 5L)

    val saved = repository.saveDraft(lookingAlreadyPublished)

    assertEquals(AgentStatus.DRAFT, saved.status)
    assertEquals(0L, saved.configVersion)
  }

  @Test
  fun `saveDraft preserves the original createdAt across edits but refreshes updatedAt`() = runTest {
    now = 1_000L
    repository.saveDraft(draft())
    now = 5_000L
    repository.saveDraft(draft().copy(name = "Обновлённое имя"))

    val entity = db.userAgentDao().get("my-test-agent")
    assertEquals(1_000L, entity?.createdAt)
    assertEquals(5_000L, entity?.updatedAt)
    assertEquals("Обновлённое имя", entity?.name)
  }

  @Test
  fun `publish throws and leaves the stored draft untouched when validation fails`() {
    val invalid = draft(toolNames = setOf("not_a_real_tool"))
    runBlocking { repository.saveDraft(invalid) }

    assertThrows(IllegalArgumentException::class.java) {
      runBlocking { repository.publish(invalid.id) }
    }

    val stored = runBlocking { repository.get(invalid.id) }
    assertEquals(AgentStatus.DRAFT, stored?.status)
    assertEquals(0L, stored?.configVersion)
    assertTrue(runBlocking { repository.observePublished().first() }.isEmpty())
  }

  @Test
  fun `publish throws for an unknown id`() {
    assertThrows(IllegalArgumentException::class.java) {
      runBlocking { repository.publish("does-not-exist") }
    }
  }

  @Test
  fun `get returns null for an unknown id`() = runTest {
    assertNull(repository.get("nope"))
  }

  @Test
  fun `observePublished orders by most recently updated first`() = runTest {
    repository.saveDraft(draft(id = "agent-a"))
    repository.saveDraft(draft(id = "agent-b"))
    repository.publish("agent-a")
    now += 1_000
    repository.publish("agent-b")

    assertEquals(listOf("agent-b", "agent-a"), repository.observePublished().first().map { it.id })
  }

  @Test
  fun `clonePreset forks a new USER draft with its own id and memory namespace`() = runTest {
    val preset = MiniAgentCatalog.get("budget")!!.toDefinition()

    val cloned = repository.clonePreset(preset)

    assertTrue(cloned.id != preset.id)
    assertTrue(cloned.id.startsWith("budget-"))
    assertEquals(AgentSource.USER, cloned.source)
    assertEquals(AgentStatus.DRAFT, cloned.status)
    assertEquals(0L, cloned.configVersion)
    assertEquals("agent:${cloned.id}:", cloned.memoryPolicy.namespace)
    assertEquals(preset.systemPrompt, cloned.systemPrompt)
    assertEquals(preset.toolNames, cloned.toolNames)
    assertTrue(repository.observePublished().first().none { it.id == cloned.id })
  }

  // --- publish(id, skills) atomically writes custom skills (Task 4) --------------------------

  private fun customSkill(
    id: String,
    agentId: String,
    instructions: String = "Делай так: ...",
  ) = UserSkillEntity(
    id = id,
    agentId = agentId,
    name = "Мой навык",
    description = "d",
    whenToUse = "w",
    instructionsMarkdown = instructions,
    createdAt = now,
    updatedAt = now,
  )

  @Test
  fun `publish writes custom skills atomically with the agent`() = runTest {
    val agentDraft = draft().copy(skillIds = setOf("my-skill"))
    repository.saveDraft(agentDraft)
    val skill = customSkill(id = "my-skill", agentId = agentDraft.id)

    val published = repository.publish(agentDraft.id, listOf(skill))

    assertEquals(AgentStatus.PUBLISHED, published.status)
    assertEquals(skill, db.userSkillDao().get("my-skill"))
  }

  @Test
  fun `publish rolls back the whole transaction when a custom skill has blank instructions`() = runTest {
    val agentDraft = draft().copy(skillIds = setOf("bad-skill"))
    repository.saveDraft(agentDraft)
    val blankSkill = customSkill(id = "bad-skill", agentId = agentDraft.id, instructions = "   ")

    assertThrows(IllegalArgumentException::class.java) {
      runBlocking { repository.publish(agentDraft.id, listOf(blankSkill)) }
    }

    assertNull("invalid skill must not be persisted", db.userSkillDao().get("bad-skill"))
    assertEquals("agent publish must roll back too", AgentStatus.DRAFT, repository.get(agentDraft.id)?.status)
    assertEquals(0L, repository.get(agentDraft.id)?.configVersion)
  }

  @Test
  fun `publish rolls back when a custom skill id collides with a built-in skill`() = runTest {
    val agentDraft = draft().copy(skillIds = setOf("budget"))
    repository.saveDraft(agentDraft)
    val collidingSkill = customSkill(id = "budget", agentId = agentDraft.id)

    assertThrows(IllegalArgumentException::class.java) {
      runBlocking { repository.publish(agentDraft.id, listOf(collidingSkill)) }
    }

    assertEquals(AgentStatus.DRAFT, repository.get(agentDraft.id)?.status)
  }

  @Test
  fun `publish rejects a custom skill id already owned by a different agent`() = runTest {
    db.userSkillDao().upsert(customSkill(id = "shared-id", agentId = "other-agent"))
    val agentDraft = draft().copy(skillIds = setOf("shared-id"))
    repository.saveDraft(agentDraft)
    val hijackAttempt = customSkill(id = "shared-id", agentId = agentDraft.id)

    assertThrows(IllegalArgumentException::class.java) {
      runBlocking { repository.publish(agentDraft.id, listOf(hijackAttempt)) }
    }

    assertEquals("other-agent", db.userSkillDao().get("shared-id")?.agentId)
    assertEquals(AgentStatus.DRAFT, repository.get(agentDraft.id)?.status)
  }

  // --- publish(id, skills, hooks) atomically writes hooks (Task 8) ---------------------------

  private fun customHook(
    id: String,
    agentId: String,
    event: HookEvent = HookEvent.BEFORE_TOOL,
    actionType: HookActionType = HookActionType.DENY,
    priority: Int = 0,
  ) = AgentHook(
    id = id,
    agentId = agentId,
    event = event,
    conditionType = "always",
    conditionJson = "{}",
    actionType = actionType,
    actionJson = "{}",
    priority = priority,
    enabled = true,
  )

  @Test
  fun `a brand new agent carries no hooks`() = runTest {
    val saved = repository.saveDraft(draft())

    assertTrue(saved.hooks.isEmpty())
    assertTrue(repository.get(draft().id)!!.hooks.isEmpty())
  }

  @Test
  fun `publish writes hooks atomically with the agent`() = runTest {
    val agentDraft = draft()
    repository.saveDraft(agentDraft)
    val hook = customHook(id = "h1", agentId = agentDraft.id)

    val published = repository.publish(agentDraft.id, hooks = listOf(hook))

    assertEquals(AgentStatus.PUBLISHED, published.status)
    assertEquals(listOf(hook), published.hooks)
    assertEquals(listOf(hook), repository.get(agentDraft.id)?.hooks)
  }

  @Test
  fun `get and observePublished return hooks sorted by priority then id`() = runTest {
    val agentDraft = draft()
    repository.saveDraft(agentDraft)
    val second = customHook(id = "b", agentId = agentDraft.id, priority = 1)
    val first = customHook(id = "a", agentId = agentDraft.id, priority = 0)

    repository.publish(agentDraft.id, hooks = listOf(second, first))

    assertEquals(listOf("a", "b"), repository.get(agentDraft.id)?.hooks?.map { it.id })
    assertEquals(listOf("a", "b"), repository.observePublished().first().single().hooks.map { it.id })
  }

  @Test
  fun `republishing with a different hook set fully replaces the old one`() = runTest {
    val agentDraft = draft()
    repository.saveDraft(agentDraft)
    repository.publish(agentDraft.id, hooks = listOf(customHook(id = "old", agentId = agentDraft.id)))

    repository.saveDraft(agentDraft)
    repository.publish(agentDraft.id, hooks = listOf(customHook(id = "new", agentId = agentDraft.id)))

    assertEquals(listOf("new"), repository.get(agentDraft.id)?.hooks?.map { it.id })
  }

  @Test
  fun `publish rolls back the whole transaction when a hook's action is invalid for its event`() = runTest {
    val agentDraft = draft()
    repository.saveDraft(agentDraft)
    // DENY is BEFORE_TOOL-only per the action/event matrix - invalid for BEFORE_TURN.
    val invalidHook = customHook(id = "bad", agentId = agentDraft.id, event = HookEvent.BEFORE_TURN, actionType = HookActionType.DENY)

    assertThrows(IllegalArgumentException::class.java) {
      runBlocking { repository.publish(agentDraft.id, hooks = listOf(invalidHook)) }
    }

    assertTrue("invalid hook must not be persisted", repository.get(agentDraft.id)?.hooks.isNullOrEmpty())
    assertEquals("agent publish must roll back too", AgentStatus.DRAFT, repository.get(agentDraft.id)?.status)
  }

  @Test
  fun `publish rejects a hook id already owned by a different agent`() = runTest {
    val otherAgent = draft(id = "other-agent")
    repository.saveDraft(otherAgent)
    repository.publish(otherAgent.id, hooks = listOf(customHook(id = "shared-id", agentId = otherAgent.id)))

    val agentDraft = draft()
    repository.saveDraft(agentDraft)
    val hijackAttempt = customHook(id = "shared-id", agentId = agentDraft.id)

    assertThrows(IllegalArgumentException::class.java) {
      runBlocking { repository.publish(agentDraft.id, hooks = listOf(hijackAttempt)) }
    }

    assertEquals(AgentStatus.DRAFT, repository.get(agentDraft.id)?.status)
    assertEquals(otherAgent.id, repository.get(otherAgent.id)?.hooks?.single()?.agentId)
  }
}
