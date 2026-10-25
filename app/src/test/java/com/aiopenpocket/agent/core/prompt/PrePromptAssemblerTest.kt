package com.aiopenpocket.agent.core.prompt

// @trace structured-plan-engine-907066

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aiopenpocket.agent.core.agent.AgentDefinition
import com.aiopenpocket.agent.core.agent.AgentMemoryPolicy
import com.aiopenpocket.agent.core.agent.AgentSource
import com.aiopenpocket.agent.core.agent.AgentStatus
import com.aiopenpocket.agent.core.agent.DefaultAgentResolver
import com.aiopenpocket.agent.core.agent.UserAgentRepository
import com.aiopenpocket.agent.core.hook.AgentHookRepository
import com.aiopenpocket.agent.loop.AgentMode
import com.aiopenpocket.agent.core.identity.UserPreferencesRepository
import com.aiopenpocket.agent.core.memory.entity.ConversationEntity
import com.aiopenpocket.agent.core.memory.repository.ConversationRepository
import com.aiopenpocket.agent.core.memory.repository.MemoryRepository
import com.aiopenpocket.agent.core.plan.context.PlanContextProvider
import com.aiopenpocket.agent.core.plan.model.AgentPlan
import com.aiopenpocket.agent.core.plan.model.CriterionVerifier
import com.aiopenpocket.agent.core.plan.model.PlanAuthor
import com.aiopenpocket.agent.core.plan.model.PlanCriterion
import com.aiopenpocket.agent.core.plan.model.PlanHorizon
import com.aiopenpocket.agent.core.plan.model.PlanStatus
import com.aiopenpocket.agent.core.plan.model.PlanStep
import com.aiopenpocket.agent.core.plan.model.PlanVersion
import com.aiopenpocket.agent.core.plan.model.VerifierType
import com.aiopenpocket.agent.core.plan.persistence.PlanRepository
import com.aiopenpocket.agent.core.skill.Skill
import com.aiopenpocket.agent.core.skill.SkillManifest
import com.aiopenpocket.agent.core.skill.SkillRegistry
import com.aiopenpocket.agent.core.skill.UserSkillRepository
import com.aiopenpocket.data.database.AppDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.aiopenpocket.testing.testDataStore

/**
 * [PrePromptAssembler] Task 6 coverage: the compact `<plan_data>` section
 * ([com.aiopenpocket.agent.core.plan.context.PlanContextProvider]) appears exactly for a
 * plan-bound conversation with an APPROVED plan, and is entirely absent otherwise — a plain chat's
 * system prompt is unaffected by this task (see [PrePromptAssembler.assemble]'s own comment: "diалог
 * без плана НЕ получает эту секцию вообще"). Real in-memory Room throughout, mirroring
 * [com.aiopenpocket.agent.loop.ReflectionOrchestratorTest]'s own [PrePromptAssembler] object graph —
 * no hand-rolled fakes for the repositories.
 *
 * Task 15 (structured-plan-engine-907066): [PrePromptAssembler.assemble] no longer takes a
 * `promptMode` parameter — the `PromptMode`/`prompts/modes` mode-prompt mechanism it used to
 * drive belonged to the old prompt-only PLAN/GOAL contract Task 15 removed, so the
 * `PrePromptAssemblerModeAssetsTest` suite that once covered that mechanism's asset existence is gone
 * with it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PrePromptAssemblerTest {

  private lateinit var db: AppDatabase
  private lateinit var assembler: PrePromptAssembler
  private lateinit var planRepository: PlanRepository

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    planRepository = PlanRepository(db, db.planDao())
    assembler = PrePromptAssembler(
      assets = AssetSource { null },
      memoryRepository = MemoryRepository(db.factDao(), db.episodeDao()),
      conversationRepository = ConversationRepository(db.conversationDao(), db.conversationTagDao(), db.messageDao()),
      skillRegistry = SkillRegistry(setOf(FakeSkill("budget"), FakeSkill("diary")), UserSkillRepository(db.userSkillDao())),
      userPreferencesRepository = UserPreferencesRepository(testDataStore(context)),
      agentResolver = DefaultAgentResolver(UserAgentRepository(db, db.userAgentDao(), AgentHookRepository(db.agentHookDao()))),
      planContextProvider = PlanContextProvider(planRepository),
    )
  }

  @After
  fun tearDown() = db.close()

  // --- fixtures ---------------------------------------------------------------------------------------

  private fun plan(id: String = "plan-1", conversationId: String = "c1") = AgentPlan(
    id = id,
    agentId = null,
    conversationId = conversationId,
    horizon = PlanHorizon.SINGLE_RUN,
    status = PlanStatus.DRAFT,
    approvedVersion = null,
    activeStepId = null,
    createdAt = 1_000L,
    updatedAt = 1_000L,
  )

  private fun version(planId: String, objective: String) = PlanVersion(
    planId = planId,
    version = 1,
    objective = objective,
    assumptions = emptyList(),
    constraints = listOf("не превышать бюджет"),
    steps = listOf(
      PlanStep(
        id = "step-1",
        title = "Собрать расходы",
        instruction = "Записать все траты за месяц",
        dependsOn = emptySet(),
        expectedTools = emptySet(),
        schedule = null,      ),
    ),
    criteria = listOf(
      PlanCriterion(
        id = "criterion-1",
        description = "Критерий готовности",
        verifier = CriterionVerifier(type = VerifierType.MEMORY_FACT),
        required = true,
      ),
    ),
    createdBy = PlanAuthor.AGENT,
    approvedAt = null,
  )

  // --- tests --------------------------------------------------------------------------------------------

  @Test
  fun `assemble adds the compact plan section for a conversation with an active approved plan`() = runTest {
    db.conversationDao().insert(ConversationEntity(id = "c1", title = "T"))
    planRepository.saveProposal(plan(), version("plan-1", "Собрать бюджет на месяц"))
    planRepository.approve("plan-1", 1)

    val result = assembler.assemble("c1", "привет")

    assertTrue(result.systemPrompt.contains("## Активный утверждённый план"))
    assertTrue(result.systemPrompt.contains("<plan_data trust=\"structured\">"))
    assertTrue(result.systemPrompt.contains("Собрать бюджет на месяц"))
  }

  @Test
  fun `assemble omits the plan section entirely for a plain chat with no plan`() = runTest {
    db.conversationDao().insert(ConversationEntity(id = "c1", title = "T"))

    val result = assembler.assemble("c1", "привет")

    assertFalse(result.systemPrompt.contains("Активный утверждённый план"))
    assertFalse(result.systemPrompt.contains("plan_data"))
  }

  @Test
  fun `assemble omits the plan section for a conversation whose only plan was never approved`() = runTest {
    db.conversationDao().insert(ConversationEntity(id = "c1", title = "T"))
    planRepository.saveProposal(plan(), version("plan-1", "Ещё не одобренный план"))

    val result = assembler.assemble("c1", "привет")

    assertFalse(result.systemPrompt.contains("Активный утверждённый план"))
    assertFalse(result.systemPrompt.contains("Ещё не одобренный план"))
  }

  // --- tool scoping (B2): a bound agent's empty tool set means "no tools", never "all tools" ----------

  private fun userAgent(id: String, toolNames: Set<String>, skillIds: Set<String> = emptySet()) = AgentDefinition(
    id = id,
    source = AgentSource.USER,
    name = "Свой агент",
    description = "Пользовательский агент.",
    iconKey = "sparkles",
    systemPrompt = "Ты — пользовательский агент.",
    toolNames = toolNames,
    skillIds = skillIds,
    defaultMode = AgentMode.CHAT,
    allowedModes = setOf(AgentMode.CHAT),
    memoryPolicy = AgentMemoryPolicy(namespace = "agent:$id:", includeSharedProfile = false),
    allowedWidgetTemplates = emptySet(),
    hooks = emptyList(),
    configVersion = 0L,
    status = AgentStatus.DRAFT,
  )

  @Test
  fun `bound user agent with an empty tool set scopes to zero tools, never the whole registry`() = runTest {
    val userAgents = UserAgentRepository(db, db.userAgentDao(), AgentHookRepository(db.agentHookDao()))
    userAgents.saveDraft(userAgent("no-tools-agent", emptySet()))
    userAgents.publish("no-tools-agent")
    db.conversationDao().insert(ConversationEntity(id = "c1", title = "T", agentId = "no-tools-agent"))

    val result = assembler.assemble("c1", "привет")

    // null would fall through to registry.getAllSchemas() at the loop — the bug. An agent that chose
    // no tools must get an EMPTY scope, not every tool on the device.
    assertNotNull("a bound agent must scope tools, not inherit the full registry", result.toolNames)
    assertTrue(result.toolNames!!.isEmpty())
  }

  @Test
  fun `bound user agent keeps exactly its own tool set`() = runTest {
    val userAgents = UserAgentRepository(db, db.userAgentDao(), AgentHookRepository(db.agentHookDao()))
    userAgents.saveDraft(userAgent("scoped-agent", setOf("memory_tool")))
    userAgents.publish("scoped-agent")
    db.conversationDao().insert(ConversationEntity(id = "c1", title = "T", agentId = "scoped-agent"))

    val result = assembler.assemble("c1", "привет")

    assertEquals(setOf("memory_tool"), result.toolNames)
  }

  // --- skill catalog: full only for a plain chat; a bound agent sees its own skills or none --------

  private class FakeSkill(private val id: String) : Skill {
    override val manifest = SkillManifest(id = id, description = "Навык $id", whenToUse = "по делу")
    override suspend fun instructions() = "Инструкция $id"
  }

  @Test
  fun `plain chat without an agent gets the full skill catalog`() = runTest {
    db.conversationDao().insert(ConversationEntity(id = "c1", title = "T"))

    val result = assembler.assemble("c1", "привет")

    assertTrue(result.systemPrompt.contains("## Доступные навыки"))
    assertTrue(result.systemPrompt.contains("- budget:"))
    assertTrue(result.systemPrompt.contains("- diary:"))
  }

  @Test
  fun `bound agent without load_skill gets no skill catalog at all`() = runTest {
    val userAgents = UserAgentRepository(db, db.userAgentDao(), AgentHookRepository(db.agentHookDao()))
    userAgents.saveDraft(userAgent("no-skill-tool-agent", setOf("memory_tool")))
    userAgents.publish("no-skill-tool-agent")
    db.conversationDao().insert(ConversationEntity(id = "c1", title = "T", agentId = "no-skill-tool-agent"))

    val result = assembler.assemble("c1", "привет")

    // Раньше здесь был ВЕСЬ каталог — и совет вызвать load_skill, которого у агента нет.
    assertFalse(result.systemPrompt.contains("## Доступные навыки"))
    assertFalse(result.systemPrompt.contains("- budget:"))
  }

  // --- memory section: bounded, no raw tool output ---------------------------------------------------

  @Test
  fun `a recent tool episode is shown as an action label plus a short outcome, not the raw output`() = runTest {
    db.conversationDao().insert(ConversationEntity(id = "c1", title = "T"))
    val memory = MemoryRepository(db.factDao(), db.episodeDao())
    // Прочитанный файл на 40 тыс. символов раньше ехал целиком в каждый следующий системный промпт.
    memory.saveEpisode(
      type = "tool_call",
      payload = """{"id":"1","name":"file_tool","risk":"READ","arguments":{"action":"read","path":"a.txt"}}""",
      outcome = "Б".repeat(40_000),
      conversationId = "c1",
    )

    val prompt = assembler.assemble("c1", "привет").systemPrompt

    assertTrue("нет метки действия:\n${prompt.takeLast(2_000)}", prompt.contains("[file_tool read]"))
    assertFalse("сырой вывод инструмента попал в промпт целиком", prompt.contains("Б".repeat(EpisodeFormat.OUTCOME_MAX_CHARS + 1)))
  }

  @Test
  fun `small memory reaches the prompt even when no word of the message matches`() = runTest {
    db.conversationDao().insert(ConversationEntity(id = "c1", title = "T"))
    MemoryRepository(db.factDao(), db.episodeDao()).saveFact("family.sister", "Аня", "general")

    val prompt = assembler.assemble("c1", "как зовут мою сестру").systemPrompt

    assertTrue("факт о сестре не попал в промпт", prompt.contains("family.sister: Аня"))
  }

  @Test
  fun `the memory facts section is capped by characters`() = runTest {
    db.conversationDao().insert(ConversationEntity(id = "c1", title = "T"))
    val memory = MemoryRepository(db.factDao(), db.episodeDao())
    repeat(30) { memory.saveFact("note.$it", "В".repeat(1_000), "general") }

    val prompt = assembler.assemble("c1", "привет").systemPrompt

    val factChars = prompt.lines().filter { it.startsWith("- note.") }.sumOf { it.length }
    assertTrue("секция фактов без потолка: $factChars символов", factChars <= 3_000)
  }

  @Test
  fun `bound agent with load_skill sees only its own skills`() = runTest {
    val userAgents = UserAgentRepository(db, db.userAgentDao(), AgentHookRepository(db.agentHookDao()))
    userAgents.saveDraft(userAgent("skilled-agent", setOf("load_skill"), skillIds = setOf("budget")))
    userAgents.publish("skilled-agent")
    db.conversationDao().insert(ConversationEntity(id = "c1", title = "T", agentId = "skilled-agent"))

    val result = assembler.assemble("c1", "привет")

    assertTrue(result.systemPrompt.contains("- budget:"))
    assertFalse(result.systemPrompt.contains("- diary:"))
  }
}
