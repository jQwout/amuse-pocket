package com.aiopenpocket.ui.agents.builder

// @trace user-agent-builder-848153

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aiopenpocket.agent.core.agent.AgentResolver
import com.aiopenpocket.agent.core.agent.AgentStatus
import com.aiopenpocket.agent.core.agent.DefaultAgentResolver
import com.aiopenpocket.agent.core.agent.MiniAgentCatalog
import com.aiopenpocket.agent.core.agent.UserAgentRepository
import com.aiopenpocket.agent.core.agent.toDefinition
import com.aiopenpocket.agent.core.hook.AgentHook
import com.aiopenpocket.agent.core.hook.AgentHookRepository
import com.aiopenpocket.agent.core.hook.HookActionType
import com.aiopenpocket.agent.core.hook.HookEvent
import com.aiopenpocket.agent.core.skill.SkillRegistry
import com.aiopenpocket.agent.core.skill.UserSkillRepository
import com.aiopenpocket.data.database.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [AgentBuilderViewModel] round-tripped through a real (in-memory) Room database — like
 * `UserAgentRepositoryTest`/`AgentResolverTest`, not a hand-rolled fake, so autosave/publish/archive
 * are proven against the actual repository transaction semantics, including the configVersion
 * ratchet quirk the ViewModel has to route around (see the last test below).
 *
 * [testDispatcher] is shared between [Dispatchers.setMain] and [runTest] so the debounced autosave
 * coroutine (launched on `viewModelScope` = `Dispatchers.Main`) advances on the SAME virtual clock
 * [kotlinx.coroutines.test.advanceUntilIdle] drives from the test body - the standard
 * kotlinx-coroutines-test pattern for testing `debounce`/`delay` inside a ViewModel.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentBuilderViewModelTest {

  private val testDispatcher = UnconfinedTestDispatcher()

  private lateinit var db: AppDatabase
  private lateinit var userAgentRepository: UserAgentRepository
  private lateinit var agentResolver: AgentResolver
  private lateinit var skillRegistry: SkillRegistry
  private lateinit var userSkillRepository: UserSkillRepository
  private lateinit var vm: AgentBuilderViewModel

  /** Flipped by the autosave-failure test to make the next repository write throw — see [setUp]. */
  private var failSaves = false
  private var ticks = 1_000L

  @Before
  fun setUp() {
    Dispatchers.setMain(testDispatcher)
    val context = ApplicationProvider.getApplicationContext<Context>()
    db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
      .allowMainThreadQueries()
      // By default Room runs queries on ITS OWN real executors, which runTest's virtual clock does not
      // track: advanceUntilIdle() would return before autosave/publish had written, and the test would
      // read an empty database. We put Room on the same test dispatcher.
      .setQueryExecutor(testDispatcher.asExecutor())
      .setTransactionExecutor(testDispatcher.asExecutor())
      .build()
    // The clock doubles as the failure point for the "autosave failed" test: db.close() no longer works for
    // that, Room reopens the in-memory database and the write silently goes into a new empty one (checked).
    // A throw from clock() inside withTransaction is a deterministic failure of exactly the call we care about.
    userAgentRepository = UserAgentRepository(db, db.userAgentDao(), AgentHookRepository(db.agentHookDao())) {
      if (failSaves) error("disk on fire") else ticks++
    }
    agentResolver = DefaultAgentResolver(userAgentRepository)
    userSkillRepository = UserSkillRepository(db.userSkillDao())
    skillRegistry = SkillRegistry(emptySet(), userSkillRepository)
    vm = AgentBuilderViewModel(userAgentRepository, agentResolver, skillRegistry, userSkillRepository)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
    failSaves = false
    runCatching { db.close() }
  }

  /** Fills every field [com.aiopenpocket.agent.core.agent.AgentValidator.validate] requires, leaving defaultMode/allowedModes/memoryPolicy at their already-consistent [AgentDraft] defaults. */
  private fun fillValidFields() {
    vm.updateDraft {
      it.copy(
        name = "Тестовый агент",
        description = "Агент для проверки builder-а.",
        iconKey = "sparkles",
        systemPrompt = "Ты — тестовый агент для unit-тестов.",
        toolNames = setOf("memory_tool"),
      )
    }
  }

  // --- new draft / clone ---------------------------------------------------------------------------

  @Test
  fun `newDraft starts an empty draft that is not yet publishable`() = runTest(testDispatcher) {
    vm.newDraft()

    val state = vm.state.value
    assertTrue(state.draft.id.isNotBlank())
    assertEquals(AgentStatus.DRAFT, state.draft.status)
    assertFalse(state.isLoading)
    assertFalse(state.canPublish)
    assertTrue(state.errors.isNotEmpty())
  }

  @Test
  fun `cloneFrom forks a preset into a new id renamed Копия, persisted immediately`() = runTest(testDispatcher) {
    vm.cloneFrom("budget")
    advanceUntilIdle()

    val state = vm.state.value
    assertTrue(state.draft.id != "budget")
    assertTrue(state.draft.id.startsWith("budget-"))
    assertEquals("Копия: Бюджет", state.draft.name)
    assertEquals(AgentStatus.DRAFT, state.draft.status)
    assertEquals(0L, state.draft.configVersion)
    assertFalse(state.isLoading)

    // Persisted right away, not left to the debounced autosave.
    val stored = userAgentRepository.get(state.draft.id)
    assertEquals("Копия: Бюджет", stored?.name)
    assertEquals(state.draft.toolNames, stored?.toolNames)
  }

  // --- start: a repeated call (screen rotation) -------------------------------------------------------

  /**
   * The route calls start from a LaunchedEffect, which restarts on screen rotation, while the ViewModel
   * survives it. A repeat used to create a new empty draft with a new id on the first step — and for every
   * id the test step created one more «Тест агента» chat.
   */
  @Test
  fun `a repeated start for a new draft keeps the same draft and step`() = runTest(testDispatcher) {
    vm.start(null)
    advanceUntilIdle()
    val id = vm.state.value.draft.id
    fillValidFields()
    advanceUntilIdle()
    assertTrue(vm.nextStep())

    vm.start(null)
    advanceUntilIdle()

    assertEquals("тот же черновик, а не новый пустой", id, vm.state.value.draft.id)
    assertEquals("и тот же шаг, а не возврат на первый", BuilderStep.INSTRUCTIONS, vm.state.value.step)
    assertEquals("правки на месте", "Тестовый агент", vm.state.value.draft.name)
  }

  /** The same for the edit route: a repeated loadExisting threw you back to the first step. */
  @Test
  fun `a repeated start on the edit route does not jump back to the first step`() = runTest(testDispatcher) {
    vm.newDraft()
    val id = vm.state.value.draft.id
    fillValidFields()
    advanceUntilIdle()

    val editing = AgentBuilderViewModel(userAgentRepository, agentResolver, skillRegistry, userSkillRepository)
    editing.start(id)
    advanceUntilIdle()
    assertTrue(editing.nextStep())

    editing.start(id)
    advanceUntilIdle()

    assertEquals(BuilderStep.INSTRUCTIONS, editing.state.value.step)
  }

  // --- validation per step --------------------------------------------------------------------------

  @Test
  fun `errors are sliced to the step that owns them`() = runTest(testDispatcher) {
    vm.newDraft()
    vm.updateDraft { it.copy(toolNames = setOf("not_a_real_tool")) }

    val state = vm.state.value
    val identityErrors = AgentBuilderValidator.errorsForStep(state.errors, BuilderStep.IDENTITY)
    val capabilityErrors = AgentBuilderValidator.errorsForStep(state.errors, BuilderStep.CAPABILITIES)
    val loopErrors = AgentBuilderValidator.errorsForStep(state.errors, BuilderStep.LOOP)

    assertTrue(identityErrors.any { it.code == "blank_name" })
    assertTrue(capabilityErrors.any { it.code == "unknown_tool" })
    assertTrue("defaultMode/allowedModes default to a consistent pair", loopErrors.isEmpty())
    assertFalse(state.canPublish)
    assertFalse(AgentBuilderValidator.canAdvanceFrom(BuilderStep.IDENTITY, state.errors))
  }

  // --- autosave --------------------------------------------------------------------------------------

  @Test
  fun `autosave persists draft edits only after the debounce window elapses`() = runTest(testDispatcher) {
    vm.newDraft()
    val id = vm.state.value.draft.id

    vm.updateDraft { it.copy(name = "Автосейв") }
    assertNull("must not persist before the debounce window elapses", userAgentRepository.get(id))

    advanceUntilIdle()

    val stored = userAgentRepository.get(id)
    assertEquals("Автосейв", stored?.name)
    assertEquals(AgentStatus.DRAFT, stored?.status)
    assertFalse(vm.state.value.isSaving)
    assertNull(vm.state.value.saveError)
  }

  @Test
  fun `switching to a different agent mid-edit discards the stale pending autosave`() = runTest(testDispatcher) {
    vm.newDraft()
    val idA = vm.state.value.draft.id
    vm.updateDraft { it.copy(name = "Агент A - не должен сохраниться") }

    // Switch away BEFORE the 500ms debounce window elapses - must cancel A's pending autosave,
    // not just leave it to fire later and persist stale data for an agent no longer on screen.
    vm.newDraft()
    val idB = vm.state.value.draft.id
    advanceUntilIdle()

    assertNull("stale edit on the previous agent must never land", userAgentRepository.get(idA))
    assertNull("brand new agent B was never edited, so it never autosaves either", userAgentRepository.get(idB))
  }

  @Test
  fun `autosave failure keeps the in-memory draft and reports the error instead of losing it`() = runTest(testDispatcher) {
    vm.newDraft()
    vm.updateDraft { it.copy(name = "До сбоя") }
    advanceUntilIdle()
    assertNull(vm.state.value.saveError)

    failSaves = true // the NEXT repository write throws, simulating a real save failure

    vm.updateDraft { it.copy(name = "После сбоя") }
    advanceUntilIdle()

    assertEquals("in-memory draft must survive a failed autosave", "После сбоя", vm.state.value.draft.name)
    assertTrue("the failure must be surfaced", vm.state.value.saveError != null)
    assertFalse(vm.state.value.isSaving)
  }

  // --- publish / archive --------------------------------------------------------------------------

  @Test
  fun `publish rejects an invalid draft without persisting anything`() = runTest(testDispatcher) {
    vm.newDraft()
    val id = vm.state.value.draft.id

    vm.publish()
    advanceUntilIdle()

    assertFalse(vm.state.value.canPublish)
    assertTrue(vm.state.value.errors.isNotEmpty())
    assertNull("an invalid draft must never even autosave via publish's own flush", userAgentRepository.get(id))
  }

  @Test
  fun `publish succeeds for a fully valid new draft`() = runTest(testDispatcher) {
    vm.newDraft()
    val id = vm.state.value.draft.id
    fillValidFields()
    advanceUntilIdle()

    vm.publish()
    advanceUntilIdle()

    assertEquals(AgentStatus.PUBLISHED, vm.state.value.draft.status)
    assertEquals(1L, vm.state.value.draft.configVersion)
    assertNull(vm.state.value.saveError)
    val stored = userAgentRepository.get(id)
    assertEquals(AgentStatus.PUBLISHED, stored?.status)
    assertEquals(1L, stored?.configVersion)
    assertTrue("успех публикации должен быть виден", vm.state.value.justPublished)
  }

  /** The "Published" badge goes away on the very first edit — from then on the screen shows unpublished content. */
  @Test
  fun `an edit after publish clears the published confirmation`() = runTest(testDispatcher) {
    vm.newDraft()
    fillValidFields()
    advanceUntilIdle()
    vm.publish()
    advanceUntilIdle()
    assertTrue(vm.state.value.justPublished)

    vm.updateDraft { it.copy(description = "Правка после публикации.") }

    assertFalse(vm.state.value.justPublished)
  }

  @Test
  fun `archive flips a published agent to ARCHIVED without deleting it`() = runTest(testDispatcher) {
    vm.newDraft()
    val id = vm.state.value.draft.id
    fillValidFields()
    advanceUntilIdle()
    vm.publish()
    advanceUntilIdle()

    vm.archive()
    advanceUntilIdle()

    assertEquals(AgentStatus.ARCHIVED, vm.state.value.draft.status)
    assertEquals(AgentStatus.ARCHIVED, userAgentRepository.get(id)?.status)
  }

  /**
   * A clone of a preset that relies on prompt state injection ("languages": `goal:`/`lang:`/
   * `grammar:`) has to keep working like its original. These prefixes had no Room column and no
   * field on the draft, so the copy reported an empty set the moment the first autosave ran — the
   * copy quietly stopped getting its own persistent state in the prompt.
   */
  @Test
  fun `a cloned preset keeps its prompt state prefixes across an edit`() = runTest(testDispatcher) {
    val presetPrefixes = MiniAgentCatalog.get("languages")!!.toDefinition().stateKeyPrefixes
    assertTrue("test only means something if the preset actually has prefixes", presetPrefixes.isNotEmpty())

    vm.cloneFrom("languages")
    advanceUntilIdle()
    val id = vm.state.value.draft.id
    assertEquals(presetPrefixes, vm.state.value.draft.stateKeyPrefixes)

    vm.updateDraft { it.copy(description = "Правка, после которой префиксы раньше исчезали.") }
    advanceUntilIdle()

    assertEquals(presetPrefixes, userAgentRepository.get(id)?.stateKeyPrefixes)
  }

  // --- restoration after recreation ------------------------------------------------------------------

  @Test
  fun `a freshly recreated ViewModel reloads exactly what an older instance autosaved`() = runTest(testDispatcher) {
    vm.newDraft()
    val id = vm.state.value.draft.id
    vm.updateDraft { it.copy(name = "Переживший", description = "d", iconKey = "sparkles", systemPrompt = "p", toolNames = setOf("memory_tool")) }
    advanceUntilIdle()

    // A brand new instance, as if the old one were destroyed and recreated (rotation / process death) -
    // it must not rely on any in-memory state from `vm`, only on what the repository has stored.
    val recreated = AgentBuilderViewModel(userAgentRepository, agentResolver, skillRegistry, userSkillRepository)
    recreated.loadExisting(id)
    advanceUntilIdle()

    assertEquals("Переживший", recreated.state.value.draft.name)
    assertEquals(setOf("memory_tool"), recreated.state.value.draft.toolNames)
    assertFalse(recreated.state.value.isLoading)
    assertTrue(recreated.state.value.canPublish)
  }

  // --- CRITICAL: monotonic configVersion across an edit-then-republish cycle -----------------------

  /**
   * Editing a PUBLISHED agent must not quietly unpublish it, and republishing must ratchet the
   * version. Autosave used to force the stored row back to `DRAFT`/`configVersion=0` on every
   * keystroke, which (a) dropped the agent out of the roster the moment the user typed, with no
   * listing surface to find it again, and (b) forced `publish` into a catch-up loop to recover the
   * true next version. Both halves are asserted here.
   */
  @Test
  fun `editing a published agent keeps it published and republishing ratchets configVersion`() = runTest(testDispatcher) {
    vm.newDraft()
    val id = vm.state.value.draft.id
    fillValidFields()
    advanceUntilIdle()

    vm.publish()
    advanceUntilIdle()
    assertEquals(1L, userAgentRepository.get(id)?.configVersion)
    assertEquals(AgentStatus.PUBLISHED, userAgentRepository.get(id)?.status)

    vm.updateDraft { it.copy(description = "Обновлённое, но всё ещё валидное описание.") }
    advanceUntilIdle()
    assertEquals(
      "autosave must NOT unpublish an agent mid-edit — that is what made it vanish from the roster",
      AgentStatus.PUBLISHED,
      userAgentRepository.get(id)?.status,
    )
    assertEquals("autosave must not touch the published version either", 1L, userAgentRepository.get(id)?.configVersion)
    assertTrue("and it stays in the roster while being edited", userAgentRepository.observePublished().first().any { it.id == id })

    vm.publish()
    advanceUntilIdle()
    assertEquals("must be 2, not a reset-to-1 regression", 2L, userAgentRepository.get(id)?.configVersion)
    assertEquals(AgentStatus.PUBLISHED, userAgentRepository.get(id)?.status)
    assertEquals(2L, vm.state.value.draft.configVersion)

    // A third cycle - proves this is a general fix, not a one-off "1 -> 2" coincidence.
    vm.updateDraft { it.copy(description = "Ещё одно валидное описание для третьего цикла.") }
    advanceUntilIdle()
    vm.publish()
    advanceUntilIdle()
    assertEquals(3L, userAgentRepository.get(id)?.configVersion)
  }

  // --- custom skills: authored in the wizard, actually persisted -------------------------------------

  /**
   * The "Custom skill" editor used to hand only the skill's ID to the draft and drop its body, so
   * nothing ever wrote a `user_skills` row: validation then reported `unknown_skill`, which blocked
   * both "Next" and "Publish", and no control could clear it again. The whole draft must
   * survive an autosave, validate, publish, and come back on reload.
   */
  @Test
  fun `an authored custom skill is publishable and survives a reload`() = runTest(testDispatcher) {
    vm.newDraft()
    val id = vm.state.value.draft.id
    fillValidFields()
    vm.updateDraft {
      it.copy(
        customSkills = listOf(
          CustomSkillDraft(id = "running-plan", name = "План бега", description = "d", whenToUse = "w", instructions = "# Как бегать"),
        ),
        skillIds = it.skillIds + "running-plan",
      )
    }
    advanceUntilIdle()

    assertTrue("an authored skill must not read as unknown_skill", vm.state.value.errors.isEmpty())
    assertTrue(vm.state.value.canPublish)

    vm.publish()
    advanceUntilIdle()
    assertNull(vm.state.value.saveError)
    assertEquals(AgentStatus.PUBLISHED, userAgentRepository.get(id)?.status)
    assertEquals("# Как бегать", userSkillRepository.getForAgent(id).single().instructionsMarkdown)

    val recreated = AgentBuilderViewModel(userAgentRepository, agentResolver, skillRegistry, userSkillRepository)
    recreated.loadExisting(id)
    advanceUntilIdle()
    assertEquals("running-plan", recreated.state.value.draft.customSkills.single().id)
    assertEquals("# Как бегать", recreated.state.value.draft.customSkills.single().instructions)
  }

  /** A blank body is a real validation error the wizard has to surface — on the step that owns the editor. */
  @Test
  fun `a custom skill with an empty body blocks publish on the capabilities step`() = runTest(testDispatcher) {
    vm.newDraft()
    fillValidFields()
    vm.updateDraft {
      it.copy(
        customSkills = listOf(CustomSkillDraft(id = "empty-skill", name = "Пустой", instructions = "")),
        skillIds = it.skillIds + "empty-skill",
      )
    }
    advanceUntilIdle()

    assertFalse(vm.state.value.canPublish)
    assertEquals(
      setOf(BuilderStep.CAPABILITIES),
      AgentBuilderValidator.blockingSteps(vm.state.value.errors),
    )
  }

  // --- hooks survive leaving the wizard before publish -----------------------------------------------

  /** Hooks used to live only in memory until publish, so closing the wizard first silently threw them away. */
  @Test
  fun `a hook added to an unpublished draft survives a reload`() = runTest(testDispatcher) {
    vm.newDraft()
    val id = vm.state.value.draft.id
    fillValidFields()
    vm.updateDraft {
      it.copy(
        hooks = listOf(
          AgentHook(
            id = "hook-1",
            agentId = it.id,
            event = HookEvent.BEFORE_TOOL,
            conditionType = "always",
            conditionJson = "{}",
            actionType = HookActionType.REQUIRE_APPROVAL,
            actionJson = "{}",
            priority = 0,
            enabled = true,
          ),
        ),
      )
    }
    advanceUntilIdle()

    val recreated = AgentBuilderViewModel(userAgentRepository, agentResolver, skillRegistry, userSkillRepository)
    recreated.loadExisting(id)
    advanceUntilIdle()
    assertEquals("hook-1", recreated.state.value.draft.hooks.single().id)
    assertEquals(HookActionType.REQUIRE_APPROVAL, recreated.state.value.draft.hooks.single().actionType)
  }

  // --- drafts are reachable --------------------------------------------------------------------------

  /** An unpublished draft has to appear in SOME listing, or the wizard writes rows nothing can ever reopen. */
  @Test
  fun `an unpublished draft is listed by observeDrafts and not by the roster`() = runTest(testDispatcher) {
    vm.newDraft()
    val id = vm.state.value.draft.id
    fillValidFields()
    advanceUntilIdle()

    assertEquals(listOf(id), agentResolver.observeDrafts().first().map { it.id })
    assertFalse(agentResolver.observeRoster().first().any { it.id == id })

    vm.publish()
    advanceUntilIdle()
    assertTrue(agentResolver.observeDrafts().first().isEmpty())
    assertTrue(agentResolver.observeRoster().first().any { it.id == id })
  }

  // --- validation "on the button" (showErrors) -----------------------------------------------------

  /**
   * A "Next" refusal is not silent: it raises [AgentBuilderUiState.showErrors], and only after that does
   * the step draw inline errors. Before the first press a fresh draft does not greet the user in red —
   * that is what the flag exists for. Rendering by the flag is checked in AgentBuilderScreenTest; here,
   * the transition itself.
   */
  @Test
  fun `nextStep on an invalid step refuses, stays put and raises showErrors`() = runTest(testDispatcher) {
    vm.newDraft()
    advanceUntilIdle()
    assertFalse("на свежем черновике ошибки ещё молчат", vm.state.value.showErrors)

    val advanced = vm.nextStep()

    assertFalse("шаг невалиден — уйти нельзя", advanced)
    assertEquals("шаг остался прежним", BuilderStep.IDENTITY, vm.state.value.step)
    assertTrue("но теперь ошибки показаны", vm.state.value.showErrors)
    assertTrue("и они действительно есть", vm.state.value.errors.isNotEmpty())
  }

  /** A successful transition moves to the next step and clears the flag — the new step starts from a clean slate. */
  @Test
  fun `nextStep on a valid step advances and clears showErrors`() = runTest(testDispatcher) {
    vm.newDraft()
    vm.nextStep() // stuck, the flag is raised
    fillValidFields()
    advanceUntilIdle()
    assertTrue(vm.state.value.showErrors)

    val advanced = vm.nextStep()

    assertTrue(advanced)
    assertEquals(BuilderStep.INSTRUCTIONS, vm.state.value.step)
    assertFalse("на новом шаге ошибки снова молчат", vm.state.value.showErrors)
  }

  /**
   * Back is never blocked and clears the flag too — otherwise the previous step would greet the user in
   * red because of another step's error.
   *
   * The step is broken EXPLICITLY: [fillValidFields] fills in the system prompt among other things, so
   * "Instructions" is valid on its own and there would be no refusal.
   */
  @Test
  fun `previousStep clears showErrors`() = runTest(testDispatcher) {
    vm.newDraft()
    fillValidFields()
    advanceUntilIdle()
    assertTrue("IDENTITY валиден -> уходим на INSTRUCTIONS", vm.nextStep())
    assertEquals(BuilderStep.INSTRUCTIONS, vm.state.value.step)

    vm.updateDraft { it.copy(systemPrompt = "") } // break the current step
    advanceUntilIdle()
    assertFalse("пустой промпт -> уйти нельзя", vm.nextStep())
    assertTrue(vm.state.value.showErrors)

    assertTrue(vm.previousStep())

    assertEquals(BuilderStep.IDENTITY, vm.state.value.step)
    assertFalse(vm.state.value.showErrors)
  }
}
