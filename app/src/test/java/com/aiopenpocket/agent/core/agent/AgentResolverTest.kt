package com.aiopenpocket.agent.core.agent

// @trace user-agent-builder-848153

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aiopenpocket.agent.loop.AgentMode
import com.aiopenpocket.data.database.AppDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [AgentResolver] is the single lookup API the runtime (`PrePromptAssembler`, `HeartbeatRunner`)
 * and the UI (`AgentsViewModel`, chat) use instead of reaching into [MiniAgentCatalog] or
 * [UserAgentRepository] directly. Backed by a real in-memory Room DB (like [UserAgentRepositoryTest])
 * rather than a fake, so the roster/collision behavior is proven against the real published/draft/
 * archived state machine, not a hand-rolled stand-in for it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentResolverTest {

  private lateinit var db: AppDatabase
  private lateinit var userAgentRepository: UserAgentRepository
  private lateinit var resolver: AgentResolver

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    userAgentRepository = UserAgentRepository(db, db.userAgentDao())
    resolver = DefaultAgentResolver(userAgentRepository)
  }

  @After
  fun tearDown() = db.close()

  private fun draft(id: String, name: String = "Тестовый агент") = AgentDefinition(
    id = id,
    source = AgentSource.USER,
    name = name,
    description = "Агент для проверки resolver.",
    iconKey = "sparkles",
    systemPrompt = "Ты — тестовый агент для unit-тестов.",
    toolNames = setOf("memory_tool"),
    skillIds = emptySet(),
    defaultMode = AgentMode.CHAT,
    allowedModes = setOf(AgentMode.CHAT),
    memoryPolicy = AgentMemoryPolicy(namespace = "agent:$id:", includeSharedProfile = false),
    allowedWidgetTemplates = emptySet(),
    hooks = emptyList(),
    configVersion = 0L,
    status = AgentStatus.DRAFT,
  )

  /** Saves a draft under [id] and publishes it, returning the published snapshot. */
  private suspend fun publishedCustom(id: String = "my-test-agent"): AgentDefinition {
    userAgentRepository.saveDraft(draft(id))
    return userAgentRepository.publish(id)
  }

  @Test
  fun `resolve finds a preset by id`() = runTest {
    val resolved = resolver.resolve("budget")

    assertEquals(AgentSource.PRESET, resolved!!.source)
    assertEquals("Бюджет", resolved.name)
  }

  @Test
  fun `resolve finds a published custom agent by id`() = runTest {
    val custom = publishedCustom()

    val resolved = resolver.resolve(custom.id)

    assertEquals(AgentSource.USER, resolved!!.source)
    assertEquals(custom.id, resolved.id)
  }

  @Test
  fun `resolve returns null for an unknown id`() = runTest {
    assertNull(resolver.resolve("missing"))
  }

  @Test
  fun `resolve returns null for a null id`() = runTest {
    assertNull(resolver.resolve(null))
  }

  @Test
  fun `resolve finds an unpublished draft too - status filtering is a roster-only rule`() = runTest {
    userAgentRepository.saveDraft(draft("draft-only"))

    val resolved = resolver.resolve("draft-only")

    assertEquals(AgentSource.USER, resolved!!.source)
    assertEquals(AgentStatus.DRAFT, resolved.status)
  }

  @Test
  fun `resolve prefers the preset when a custom agent id collides with a preset id`() = runTest {
    // saveDraft doesn't validate collisions (only publish() does, via AgentValidator) - this proves
    // resolve() itself refuses to let a same-id custom row shadow a preset, not just the validator.
    userAgentRepository.saveDraft(draft(id = "budget", name = "Поддельный Бюджет"))

    val resolved = resolver.resolve("budget")

    assertEquals(AgentSource.PRESET, resolved!!.source)
    assertEquals("Бюджет", resolved.name)
  }

  @Test
  fun `observeRoster lists every preset then published custom agents`() = runTest {
    val custom = publishedCustom()

    val roster = resolver.observeRoster().first()

    assertEquals(MiniAgentCatalog.all.map { it.id } + custom.id, roster.map { it.id })
  }

  @Test
  fun `observeRoster excludes draft and archived custom agents`() = runTest {
    userAgentRepository.saveDraft(draft("still-draft"))
    val archived = publishedCustom(id = "to-archive")
    userAgentRepository.archive(archived.id)

    val roster = resolver.observeRoster().first()

    assertEquals(MiniAgentCatalog.all.map { it.id }, roster.map { it.id })
  }

  @Test
  fun `observeRoster reflects newly published agents reactively`() = runTest {
    val presetOnlyCount = resolver.observeRoster().first().size

    publishedCustom()

    assertEquals(presetOnlyCount + 1, resolver.observeRoster().first().size)
  }

  @Test
  fun `observeRoster dedupes a preset-id collision, keeping the preset`() = runTest {
    // Future-proofing the same way resolve() does: a custom agent published BEFORE a colliding preset
    // id ever shipped (the validator can't reject that retroactively). Force a PUBLISHED row under a
    // preset id via the DAO — publish() would reject it — then the roster must collapse to one entry,
    // the preset, or AgentsScreen's LazyColumn(items, key = { it.id }) would crash on a duplicate key.
    userAgentRepository.saveDraft(draft(id = "budget", name = "Поддельный Бюджет"))
    db.userAgentDao().updateStatus("budget", AgentStatus.PUBLISHED.name, 0L)

    val roster = resolver.observeRoster().first()

    assertEquals(1, roster.count { it.id == "budget" })
    assertEquals(AgentSource.PRESET, roster.single { it.id == "budget" }.source)
  }
}
