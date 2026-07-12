package com.aiopenpocket.agent.core.agent

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aiopenpocket.agent.core.skill.UserSkillEntity
import com.aiopenpocket.agent.loop.AgentMode
import com.aiopenpocket.data.database.AppDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UserAgentRepositoryTest {

  private lateinit var db: AppDatabase
  private lateinit var repository: UserAgentRepository
  private var now = 1_000_000L

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    repository = UserAgentRepository(db, db.userAgentDao()) { now }
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
}
