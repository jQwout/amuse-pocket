package com.aiopenpocket.ui.agents.builder

// @trace user-agent-builder-848153

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aiopenpocket.agent.core.agent.AgentResolver
import com.aiopenpocket.agent.core.agent.AgentStatus
import com.aiopenpocket.agent.core.agent.DefaultAgentResolver
import com.aiopenpocket.agent.core.agent.UserAgentRepository
import com.aiopenpocket.agent.core.hook.AgentHookRepository
import com.aiopenpocket.agent.core.skill.SkillRegistry
import com.aiopenpocket.agent.core.skill.UserSkillRepository
import com.aiopenpocket.data.database.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
  private lateinit var vm: AgentBuilderViewModel

  @Before
  fun setUp() {
    Dispatchers.setMain(testDispatcher)
    val context = ApplicationProvider.getApplicationContext<Context>()
    db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    userAgentRepository = UserAgentRepository(db, db.userAgentDao(), AgentHookRepository(db.agentHookDao()))
    agentResolver = DefaultAgentResolver(userAgentRepository)
    skillRegistry = SkillRegistry(emptySet(), UserSkillRepository(db.userSkillDao()))
    vm = AgentBuilderViewModel(userAgentRepository, agentResolver, skillRegistry)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
    runCatching { db.close() } // one test closes db itself to simulate a save failure - safe to no-op twice
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
    assertEquals(0L, state.draft.lastPublishedConfigVersion)
    assertFalse(state.isLoading)

    // Persisted right away, not left to the debounced autosave.
    val stored = userAgentRepository.get(state.draft.id)
    assertEquals("Копия: Бюджет", stored?.name)
    assertEquals(state.draft.toolNames, stored?.toolNames)
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

    db.close() // forces the NEXT repository call to throw, simulating a real save failure

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
    assertEquals(1L, vm.state.value.draft.lastPublishedConfigVersion)
    assertNull(vm.state.value.saveError)
    val stored = userAgentRepository.get(id)
    assertEquals(AgentStatus.PUBLISHED, stored?.status)
    assertEquals(1L, stored?.configVersion)
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

  // --- restoration after recreation ------------------------------------------------------------------

  @Test
  fun `a freshly recreated ViewModel reloads exactly what an older instance autosaved`() = runTest(testDispatcher) {
    vm.newDraft()
    val id = vm.state.value.draft.id
    vm.updateDraft { it.copy(name = "Переживший", description = "d", iconKey = "sparkles", systemPrompt = "p", toolNames = setOf("memory_tool")) }
    advanceUntilIdle()

    // A brand new instance, as if the old one were destroyed and recreated (rotation / process death) -
    // it must not rely on any in-memory state from `vm`, only on what the repository has stored.
    val recreated = AgentBuilderViewModel(userAgentRepository, agentResolver, skillRegistry)
    recreated.loadExisting(id)
    advanceUntilIdle()

    assertEquals("Переживший", recreated.state.value.draft.name)
    assertEquals(setOf("memory_tool"), recreated.state.value.draft.toolNames)
    assertFalse(recreated.state.value.isLoading)
    assertTrue(recreated.state.value.canPublish)
  }

  // --- CRITICAL: monotonic configVersion across an edit-then-republish cycle -----------------------

  /**
   * Directly exercises the Task 19 brief's flagged correctness rule.
   * [com.aiopenpocket.agent.core.agent.UserAgentRepository.saveDraft] unconditionally resets the
   * STORED row to `DRAFT`/`configVersion=0` on every autosave (proven mid-test below), so a naive
   * single [com.aiopenpocket.agent.core.agent.UserAgentRepository.publish] call after an edit would
   * always report `1`, never the true next version. [AgentBuilderViewModel.publish] must route
   * around that so configVersion is strictly increasing across repeated edit/publish cycles.
   */
  @Test
  fun `editing and republishing an already-published agent yields a strictly increasing configVersion`() = runTest(testDispatcher) {
    vm.newDraft()
    val id = vm.state.value.draft.id
    fillValidFields()
    advanceUntilIdle()

    vm.publish()
    advanceUntilIdle()
    assertEquals(1L, userAgentRepository.get(id)?.configVersion)
    assertEquals(AgentStatus.PUBLISHED, userAgentRepository.get(id)?.status)

    // Edit the published agent - autosave fires and resets the STORED row back to DRAFT/0 well
    // before the user republishes (this is `saveDraft`'s own documented, tested contract).
    vm.updateDraft { it.copy(description = "Обновлённое, но всё ещё валидное описание.") }
    advanceUntilIdle()
    assertEquals("sanity check: autosave really does reset the stored row", AgentStatus.DRAFT, userAgentRepository.get(id)?.status)
    assertEquals(0L, userAgentRepository.get(id)?.configVersion)

    vm.publish()
    advanceUntilIdle()
    assertEquals("must be 2, not a reset-to-1 regression", 2L, userAgentRepository.get(id)?.configVersion)
    assertEquals(AgentStatus.PUBLISHED, userAgentRepository.get(id)?.status)
    assertEquals(2L, vm.state.value.draft.lastPublishedConfigVersion)

    // A third cycle - proves this is a general fix, not a one-off "1 -> 2" coincidence.
    vm.updateDraft { it.copy(description = "Ещё одно валидное описание для третьего цикла.") }
    advanceUntilIdle()
    vm.publish()
    advanceUntilIdle()
    assertEquals(3L, userAgentRepository.get(id)?.configVersion)
  }
}
