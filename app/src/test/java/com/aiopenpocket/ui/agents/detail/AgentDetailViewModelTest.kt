package com.aiopenpocket.ui.agents.detail

// @trace user-agent-builder-848153
// @trace structured-plan-engine-907066

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aiopenpocket.agent.core.agent.AgentDefinition
import com.aiopenpocket.agent.core.agent.AgentSource
import com.aiopenpocket.agent.core.agent.AgentStatus
import com.aiopenpocket.agent.core.agent.DefaultAgentResolver
import com.aiopenpocket.agent.core.agent.MiniAgentCatalog
import com.aiopenpocket.agent.core.agent.UserAgentRepository
import com.aiopenpocket.agent.core.agent.toDefinition
import com.aiopenpocket.agent.core.hook.AgentHook
import com.aiopenpocket.agent.core.hook.AgentHookRepository
import com.aiopenpocket.agent.core.hook.AgentHookRunEntity
import com.aiopenpocket.agent.core.hook.HookActionType
import com.aiopenpocket.agent.core.hook.HookEvent
import com.aiopenpocket.agent.core.mcp.RiskClass
import com.aiopenpocket.agent.core.mcp.Tool
import com.aiopenpocket.agent.core.mcp.ToolRegistry
import com.aiopenpocket.agent.core.mcp.ToolResult
import com.aiopenpocket.agent.core.mcp.toolSchema
import com.aiopenpocket.agent.core.memory.dao.HeartbeatDao
import com.aiopenpocket.agent.core.memory.entity.ConversationEntity
import com.aiopenpocket.agent.core.memory.entity.HeartbeatEntity
import com.aiopenpocket.agent.core.plan.model.AgentPlan
import com.aiopenpocket.agent.core.plan.model.PlanAuthor
import com.aiopenpocket.agent.core.plan.model.PlanHorizon
import com.aiopenpocket.agent.core.plan.model.PlanStatus
import com.aiopenpocket.agent.core.plan.model.PlanStep
import com.aiopenpocket.agent.core.plan.model.PlanVersion
import com.aiopenpocket.agent.core.plan.persistence.PlanRepository
import com.aiopenpocket.agent.core.skill.SkillRegistry
import com.aiopenpocket.agent.core.skill.UserSkillEntity
import com.aiopenpocket.agent.core.skill.UserSkillRepository
import com.aiopenpocket.data.database.AppDatabase
import com.aiopenpocket.data.repository.AgentFilesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonElement
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [AgentDetailViewModel] round-tripped through a real (in-memory) Room database, mirroring
 * `AgentBuilderViewModelTest`/`ToolApprovalFlowTest` — proves state aggregation (tools/skills/hooks+
 * journal/heartbeats/files), the unknown-tool-never-crashes contract, and the editable/duplicable/
 * toggle/archive action gating by [AgentDefinition.source]/[AgentDefinition.status].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentDetailViewModelTest {

  private val testDispatcher = UnconfinedTestDispatcher()

  /** Real (empty-args) schema; matches the fake-tool recipe already established in ToolApprovalFlowTest. */
  private class FakeTool(
    override val name: String,
    override val riskClass: RiskClass = RiskClass.READ,
  ) : Tool {
    override val description = "fake tool $name"
    override val schema = toolSchema(name, description) {}
    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult = ToolResult.Success("ok:$name")
  }

  private lateinit var db: AppDatabase
  private lateinit var userAgentRepository: UserAgentRepository
  private lateinit var agentHookRepository: AgentHookRepository
  private lateinit var heartbeatDao: HeartbeatDao
  private lateinit var agentFilesRepository: AgentFilesRepository
  private lateinit var planRepository: PlanRepository
  private lateinit var vm: AgentDetailViewModel
  private var now = 10_000L

  @Before
  fun setUp() {
    Dispatchers.setMain(testDispatcher)
    val context = ApplicationProvider.getApplicationContext<Context>()
    db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    agentHookRepository = AgentHookRepository(db.agentHookDao())
    userAgentRepository = UserAgentRepository(db, db.userAgentDao(), agentHookRepository)
    heartbeatDao = db.heartbeatDao()
    agentFilesRepository = AgentFilesRepository(RuntimeEnvironment.getApplication())
    planRepository = PlanRepository(db, db.planDao()) { now }
    vm = AgentDetailViewModel(
      agentResolver = DefaultAgentResolver(userAgentRepository),
      userAgentRepository = userAgentRepository,
      toolRegistry = ToolRegistry(setOf(FakeTool("memory_tool", RiskClass.WRITE))),
      skillRegistry = SkillRegistry(emptySet(), UserSkillRepository(db.userSkillDao())),
      agentHookRepository = agentHookRepository,
      heartbeatDao = heartbeatDao,
      agentFilesRepository = agentFilesRepository,
      planRepository = planRepository,
    )
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
    db.close()
  }

  private fun customUserAgent(id: String, toolNames: Set<String>, skillIds: Set<String>): AgentDefinition {
    val preset = MiniAgentCatalog.get("budget")!!.toDefinition()
    return preset.copy(
      id = id,
      source = AgentSource.USER,
      status = AgentStatus.DRAFT,
      configVersion = 0L,
      toolNames = toolNames,
      skillIds = skillIds,
      memoryPolicy = preset.memoryPolicy.copy(namespace = "agent:$id:"),
    )
  }

  private fun agentSandboxDir(agentId: String): File =
    File(File(RuntimeEnvironment.getApplication().filesDir, "agents"), agentId).apply { mkdirs() }

  // --- State aggregation ---------------------------------------------------------------------------

  @Test
  fun `select aggregates definition, tools, skills, hooks plus journal, heartbeats and files`() = runTest(testDispatcher) {
    val id = "full-agent"
    userAgentRepository.saveDraft(customUserAgent(id, toolNames = setOf("memory_tool"), skillIds = setOf("custom-skill-1")))
    val hook = AgentHook(
      id = "hook1",
      agentId = id,
      event = HookEvent.BEFORE_TOOL,
      conditionType = "always",
      conditionJson = "{}",
      actionType = HookActionType.LOG,
      actionJson = """{"text":"logged"}""",
      priority = 0,
      enabled = true,
    )
    val customSkill = UserSkillEntity(
      id = "custom-skill-1",
      agentId = id,
      name = "Мой навык",
      description = "Тестовый кастомный навык",
      whenToUse = "когда пишем тест",
      instructionsMarkdown = "Делай X.",
      createdAt = 0L,
      updatedAt = 0L,
    )
    userAgentRepository.publish(id, skills = listOf(customSkill), hooks = listOf(hook))
    agentHookRepository.recordRun(
      AgentHookRunEntity(
        id = "run1",
        hookId = "hook1",
        conversationId = "conv1",
        runId = "runA",
        timestamp = 1_700_000_000_000L,
        status = "LOG",
        summary = "logged",
        errorMessage = null,
      ),
    )
    heartbeatDao.insert(
      HeartbeatEntity(
        id = "hb1",
        title = "Проверка",
        mode = "agent",
        agentId = id,
        conversationId = "conv1",
        kind = "periodic",
        intervalMinutes = 30,
        status = "scheduled",
      ),
    )
    File(agentSandboxDir(id), "note.txt").writeText("data")

    vm.select(id)
    val state = vm.state.first {
      it.definition != null && it.tools.isNotEmpty() && it.hooks.isNotEmpty() && it.activeHeartbeats.isNotEmpty()
    }

    assertEquals(id, state.definition?.id)
    assertEquals(listOf("memory_tool"), state.tools.map { it.name })
    assertEquals(RiskClass.WRITE, state.tools.single().riskClass)
    assertTrue(state.tools.single().available)
    assertEquals(listOf("custom-skill-1"), state.skills.map { it.id })
    assertTrue(state.skills.single().available)
    assertEquals("Тестовый кастомный навык", state.skills.single().description)
    assertEquals(listOf("hook1"), state.hooks.map { it.hook.id })
    assertEquals(listOf("run1"), state.hooks.single().recentRuns.map { it.id })
    assertEquals(listOf("hb1"), state.activeHeartbeats.map { it.id })
    assertEquals(listOf("note.txt"), state.files.map { it.name })
    assertFalse(state.hasInvalidCapabilities)
  }

  @Test
  fun `an unregistered tool name resolves to an unavailable row and marks the agent invalid, never crashes`() = runTest(testDispatcher) {
    val id = "downgraded-agent"
    // "step_counter" is a real AgentValidator.KNOWN_TOOL_NAMES entry (so publish succeeds, as it did
    // when this agent was authored) but this test's ToolRegistry never registers it — simulating an
    // APK downgrade that dropped the tool's DI binding while the persisted definition still names it.
    userAgentRepository.saveDraft(customUserAgent(id, toolNames = setOf("memory_tool", "step_counter"), skillIds = emptySet()))
    userAgentRepository.publish(id)

    vm.select(id)
    val state = vm.state.first { it.definition != null && !it.isLoading }

    val unavailable = state.tools.single { it.name == "step_counter" }
    assertFalse(unavailable.available)
    assertEquals(null, unavailable.riskClass)
    assertTrue(state.tools.single { it.name == "memory_tool" }.available)
    assertTrue(state.hasInvalidCapabilities)
  }

  // --- Editable / duplicable gating ----------------------------------------------------------------

  @Test
  fun `a preset is not editable but is duplicable`() = runTest(testDispatcher) {
    vm.select("budget")
    val state = vm.state.first { it.definition != null }

    assertFalse(state.canEdit)
    assertTrue(state.canDuplicate)
    assertFalse(state.canToggleEnabled)
    assertFalse(state.canArchive)
  }

  @Test
  fun `a user agent is editable`() = runTest(testDispatcher) {
    val id = "editable-agent"
    userAgentRepository.saveDraft(customUserAgent(id, toolNames = setOf("memory_tool"), skillIds = emptySet()))
    userAgentRepository.publish(id)

    vm.select(id)
    val state = vm.state.first { it.definition != null }

    assertTrue(state.canEdit)
    assertTrue(state.canDuplicate)
    assertTrue(state.canToggleEnabled)
    assertTrue(state.canArchive)
  }

  // --- Actions --------------------------------------------------------------------------------------

  @Test
  fun `toggleEnabled on a published agent demotes it to draft`() = runTest(testDispatcher) {
    val id = "toggle-agent"
    userAgentRepository.saveDraft(customUserAgent(id, toolNames = setOf("memory_tool"), skillIds = emptySet()))
    userAgentRepository.publish(id)
    vm.select(id)
    vm.state.first { it.definition?.status == AgentStatus.PUBLISHED }

    vm.toggleEnabled()
    val state = vm.state.first { it.definition?.status == AgentStatus.DRAFT }

    assertEquals(AgentStatus.DRAFT, state.definition?.status)
    assertFalse(state.isEnabled)
  }

  @Test
  fun `archive on a user agent moves it to archived`() = runTest(testDispatcher) {
    val id = "archive-agent"
    userAgentRepository.saveDraft(customUserAgent(id, toolNames = setOf("memory_tool"), skillIds = emptySet()))
    userAgentRepository.publish(id)
    vm.select(id)
    vm.state.first { it.definition?.status == AgentStatus.PUBLISHED }

    vm.archive()
    val state = vm.state.first { it.definition?.status == AgentStatus.ARCHIVED }

    assertEquals(AgentStatus.ARCHIVED, state.definition?.status)
    assertFalse(state.canArchive)
  }

  // --- Планы section (Task 12, structured-plan-engine-907066) ----------------------------------------

  private suspend fun insertConversation(id: String) {
    db.conversationDao().insert(ConversationEntity(id = id, title = "Test conversation $id"))
  }

  private fun planFixture(id: String, agentId: String, conversationId: String = id) = AgentPlan(
    id = id,
    agentId = agentId,
    conversationId = conversationId,
    horizon = PlanHorizon.SINGLE_RUN,
    status = PlanStatus.DRAFT,
    approvedVersion = null,
    activeStepId = null,
    createdAt = 1_000L,
    updatedAt = 1_000L,
  )

  private fun versionFixture(planId: String) = PlanVersion(
    planId = planId,
    version = 1,
    objective = "Objective $planId",
    assumptions = emptyList(),
    constraints = emptyList(),
    steps = listOf(
      PlanStep(
        id = "step-1",
        title = "Step 1",
        instruction = "Do it",
        dependsOn = emptySet(),
        expectedTools = emptySet(),
        schedule = null,      ),
    ),
    criteria = emptyList(),
    createdBy = PlanAuthor.AGENT,
    approvedAt = null,
  )

  @Test
  fun `plans section shows counts and rows bucketed by status for this agent only`() = runTest(testDispatcher) {
    insertConversation("plan-pending")
    insertConversation("plan-active")
    insertConversation("plan-done")
    insertConversation("plan-other-agent")

    // Same agent: one AWAITING_APPROVAL (counts as active), one ACTIVE, one CANCELLED (completed).
    planRepository.saveProposal(planFixture("plan-pending", agentId = "budget"), versionFixture("plan-pending"))
    planRepository.saveProposal(planFixture("plan-active", agentId = "budget"), versionFixture("plan-active"))
    planRepository.approve("plan-active", 1)
    planRepository.saveProposal(planFixture("plan-done", agentId = "budget"), versionFixture("plan-done"))
    planRepository.approve("plan-done", 1)
    planRepository.cancel("plan-done")
    // A different agent's plan must never leak into "budget"'s counts.
    planRepository.saveProposal(planFixture("plan-other-agent", agentId = "other-agent"), versionFixture("plan-other-agent"))

    vm.select("budget")
    val state = vm.state.first { it.plans.size == 3 }

    assertEquals(setOf("plan-pending", "plan-active", "plan-done"), state.plans.map { it.planId }.toSet())
    assertEquals(2, state.activePlansCount)
    assertEquals(0, state.pausedPlansCount)
    assertEquals(1, state.completedPlansCount)
  }

  @Test
  fun `a preset agent with no plans gets an empty plans list`() = runTest(testDispatcher) {
    vm.select("budget")
    val state = vm.state.first { it.definition != null }

    assertTrue(state.plans.isEmpty())
    assertEquals(0, state.activePlansCount)
    assertEquals(0, state.pausedPlansCount)
    assertEquals(0, state.completedPlansCount)
  }
}
