package com.aiopenpocket.agent.core.prompt

// @trace structured-plan-engine-907066

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aiopenpocket.agent.core.agent.DefaultAgentResolver
import com.aiopenpocket.agent.core.agent.UserAgentRepository
import com.aiopenpocket.agent.core.hook.AgentHookRepository
import com.aiopenpocket.agent.core.identity.UserPreferencesRepository
import com.aiopenpocket.agent.core.memory.entity.ConversationEntity
import com.aiopenpocket.agent.core.memory.repository.ConversationRepository
import com.aiopenpocket.agent.core.memory.repository.MemoryRepository
import com.aiopenpocket.agent.core.plan.context.PlanContextProvider
import com.aiopenpocket.agent.core.plan.model.AgentPlan
import com.aiopenpocket.agent.core.plan.model.CompletionRule
import com.aiopenpocket.agent.core.plan.model.CompletionRuleType
import com.aiopenpocket.agent.core.plan.model.CriterionVerifier
import com.aiopenpocket.agent.core.plan.model.PlanAuthor
import com.aiopenpocket.agent.core.plan.model.PlanCriterion
import com.aiopenpocket.agent.core.plan.model.PlanHorizon
import com.aiopenpocket.agent.core.plan.model.PlanStatus
import com.aiopenpocket.agent.core.plan.model.PlanStep
import com.aiopenpocket.agent.core.plan.model.PlanVersion
import com.aiopenpocket.agent.core.plan.model.VerifierType
import com.aiopenpocket.agent.core.plan.persistence.PlanRepository
import com.aiopenpocket.agent.core.skill.SkillRegistry
import com.aiopenpocket.agent.core.skill.UserSkillRepository
import com.aiopenpocket.data.database.AppDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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
      skillRegistry = SkillRegistry(emptySet(), UserSkillRepository(db.userSkillDao())),
      userPreferencesRepository = UserPreferencesRepository(context),
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
        schedule = null,
        completionRule = CompletionRule(type = CompletionRuleType.TOOL_SUCCEEDED),
      ),
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
}
