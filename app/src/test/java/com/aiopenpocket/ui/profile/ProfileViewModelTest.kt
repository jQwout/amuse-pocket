package com.aiopenpocket.ui.profile

// @trace profile-screen-7354a7

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aiopenpocket.agent.core.llm.usage.LlmUsageEntity
import com.aiopenpocket.agent.core.memory.model.UserProfile
import com.aiopenpocket.agent.core.memory.repository.MemoryRepository
import com.aiopenpocket.data.database.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
 * The Profile screen's logic against a REAL in-memory Room (like MemoryToolsNamespaceTest): the claim "agents'
 * private facts are not listed" is a claim about rows in the facts table, so it is proven on rows, not on a fake
 * repository that would answer whatever the test told it to.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ProfileViewModelTest {

  private val testDispatcher = UnconfinedTestDispatcher()
  private lateinit var db: AppDatabase
  private lateinit var memory: MemoryRepository
  private val viewModelStore = ViewModelStore()
  private var seq = 0

  @Before
  fun setUp() {
    Dispatchers.setMain(testDispatcher)
    val context = ApplicationProvider.getApplicationContext<Context>()
    db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    memory = MemoryRepository(db.factDao(), db.episodeDao())
  }

  @After
  fun tearDown() {
    // The store first: onCleared cancels viewModelScope before the database it reads is closed.
    viewModelStore.clear()
    db.close()
    Dispatchers.resetMain()
  }

  private fun viewModel(): ProfileViewModel {
    val vm = ProfileViewModel(memoryRepository = memory, llmUsageDao = db.llmUsageDao())
    ViewModelProvider(
      viewModelStore,
      object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = vm as T
      },
    )["vm-${seq++}", ProfileViewModel::class.java]
    return vm
  }

  /** Waits for state in REAL time: Room works on its own executor, which the test scheduler does not track. */
  private suspend fun ProfileViewModel.await(predicate: (ProfileUiState) -> Boolean): ProfileUiState =
    withContext(Dispatchers.Default) { withTimeout(10_000) { state.first(predicate) } }

  private suspend fun savedProfile(name: String = "Анна", religion: String = "") = memory.saveUserProfile(
    UserProfile(name = name, preferredAddress = "Аня", age = "31", gender = "женский", religion = religion, extraInfo = "Люблю горы"),
  )

  private fun ProfileUiState.sectionKeys(): Map<FactGroup, List<String>> =
    factSections.associate { section -> section.group to section.facts.map { it.key } }

  @Test
  fun `loads the profile onboarding saved`() = runTest {
    savedProfile()

    val state = viewModel().await { it.loaded }

    assertEquals("Анна", state.draft.name)
    assertEquals("Аня", state.draft.preferredAddress)
    assertEquals("31", state.draft.age)
    assertEquals("женский", state.draft.gender)
    assertEquals("Люблю горы", state.draft.extraInfo)
    assertFalse("a freshly loaded profile has nothing to save", state.dirty)
  }

  @Test
  fun `an empty profile is a normal state, not an error`() = runTest {
    val state = viewModel().await { it.loaded }

    assertEquals("", state.draft.name)
    assertFalse(state.showReligion)
    assertFalse(state.dirty)
  }

  @Test
  fun `religion is shown only when it was set`() = runTest {
    savedProfile(religion = "")
    assertFalse(viewModel().await { it.loaded }.showReligion)

    savedProfile(religion = "буддизм")
    assertTrue(viewModel().await { it.loaded }.showReligion)
  }

  @Test
  fun `the religion field stays when the person clears it to retype`() = runTest {
    savedProfile(religion = "буддизм")
    val vm = viewModel()
    vm.await { it.loaded }

    vm.onFieldChange(ProfileField.RELIGION, "")

    assertTrue(vm.state.value.showReligion)
  }

  @Test
  fun `edits are saved trimmed through the onboarding path`() = runTest {
    savedProfile()
    val vm = viewModel()
    vm.await { it.loaded }

    vm.onFieldChange(ProfileField.NAME, "  Мария  ")
    vm.onFieldChange(ProfileField.ABOUT, "\n Бегаю по утрам \n")
    assertTrue(vm.state.value.dirty)
    vm.save()
    val after = vm.await { it.justSaved }

    // What the next reader (onboarding, the prompt) finds in storage:
    val stored = memory.getUserProfile()
    assertEquals("Мария", stored.name)
    assertEquals("Бегаю по утрам", stored.extraInfo)
    // And the screen itself shows the trimmed text and has nothing left to save:
    assertEquals("Мария", after.draft.name)
    assertFalse(after.dirty)
  }

  @Test
  fun `a cleared field is cleared in storage`() = runTest {
    savedProfile()
    val vm = viewModel()
    vm.await { it.loaded }

    vm.onFieldChange(ProfileField.AGE, "   ")
    vm.save()
    vm.await { it.justSaved }

    assertEquals("", memory.getUserProfile().age)
    assertEquals("Анна", memory.getUserProfile().name)
  }

  @Test
  fun `an edit clears the saved confirmation`() = runTest {
    savedProfile()
    val vm = viewModel()
    vm.await { it.loaded }
    vm.onFieldChange(ProfileField.NAME, "Мария")
    vm.save()
    vm.await { it.justSaved }

    vm.onFieldChange(ProfileField.NAME, "Мария Ивановна")

    assertFalse(vm.state.value.justSaved)
  }

  @Test
  fun `only the user's facts are listed, not agents' private ones nor the profile rows`() = runTest {
    savedProfile()
    memory.upsertFact("family.sister", "Аня", "general")
    memory.upsertFact("diet.style", "вегетарианец", "preference")
    memory.upsertFact("user.city", "Белград", "profile")
    memory.upsertFact("agent:budget:rent", "500 евро", "general")
    memory.upsertFact("agent:diary:2026-10-30", "ходил в горы", "general")

    val state = viewModel().await { it.loaded }

    assertEquals(
      mapOf(
        FactGroup.ABOUT_YOU to listOf("user.city"),
        FactGroup.PREFERENCES to listOf("diet.style"),
        FactGroup.OTHER to listOf("family.sister"),
      ),
      state.sectionKeys(),
    )
  }

  @Test
  fun `asking to forget deletes nothing until it is confirmed`() = runTest {
    val id = memory.upsertFact("family.sister", "Аня", "general")!!
    val vm = viewModel()
    vm.await { it.loaded }
    val fact = vm.state.value.factSections.single().facts.single()

    vm.requestDelete(fact)

    assertEquals(fact, vm.state.value.pendingDelete)
    assertEquals(listOf(id), memory.getSharedFacts().map { it.id })

    vm.cancelDelete()

    assertNull(vm.state.value.pendingDelete)
    assertEquals(listOf(id), memory.getSharedFacts().map { it.id })
  }

  @Test
  fun `a confirmed fact is removed, its neighbours stay`() = runTest {
    memory.upsertFact("family.sister", "Аня", "general")
    memory.upsertFact("family.brother", "Пётр", "general")
    val vm = viewModel()
    vm.await { it.loaded }
    val sister = vm.state.value.factSections.single().facts.single { it.key == "family.sister" }

    vm.requestDelete(sister)
    vm.confirmDelete()
    val after = vm.await { it.pendingDelete == null }

    assertEquals(listOf("family.brother"), after.factSections.single().facts.map { it.key })
    assertEquals(listOf("family.brother"), memory.getSharedFacts().map { it.key })
  }

  @Test
  fun `forgetting a fact never touches an agent's private memory`() = runTest {
    memory.upsertFact("family.sister", "Аня", "general")
    memory.upsertFact("agent:budget:rent", "500 евро", "general")
    val vm = viewModel()
    vm.await { it.loaded }

    vm.requestDelete(vm.state.value.factSections.single().facts.single())
    vm.confirmDelete()
    vm.await { it.pendingDelete == null }

    assertEquals(1, memory.getFactsByNamespace("agent:budget:").size)
  }

  @Test
  fun `spend over the week reaches the screen`() = runTest {
    db.llmUsageDao().insert(
      LlmUsageEntity(
        at = System.currentTimeMillis(),
        provider = "META",
        model = "muse-spark-1.3",
        conversationId = null,
        agentId = null,
        promptTokens = 900,
        completionTokens = 100,
        costUsd = null,
        estimated = false,
        hadTools = false,
      ),
    )

    val usage = viewModel().await { it.usage != null }.usage

    assertNotNull(usage)
    assertEquals(1, usage!!.calls)
    assertEquals(1_000L, usage.totalTokens)
  }
}
