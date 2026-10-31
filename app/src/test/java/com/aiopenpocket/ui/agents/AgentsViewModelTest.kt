package com.aiopenpocket.ui.agents

// @trace multi-agent-orchestration-27b8e3
// @trace user-agent-builder-848153

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aiopenpocket.agent.core.agent.AgentDefinition
import com.aiopenpocket.agent.core.agent.AgentSource
import com.aiopenpocket.agent.core.agent.AgentStatus
import com.aiopenpocket.agent.core.agent.BuilderAgent
import com.aiopenpocket.agent.core.agent.DefaultAgentResolver
import com.aiopenpocket.agent.core.agent.MiniAgentCatalog
import com.aiopenpocket.agent.core.agent.UserAgentRepository
import com.aiopenpocket.agent.core.agent.toDefinition
import com.aiopenpocket.agent.core.hook.AgentHookRepository
import com.aiopenpocket.agent.core.identity.UserPreferencesRepository
import com.aiopenpocket.agent.core.memory.repository.ConversationRepository
import com.aiopenpocket.data.database.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.aiopenpocket.testing.testDataStore

/**
 * [AgentsViewModel] round-tripped through a real (in-memory) Room database, mirroring
 * `AgentBuilderViewModelTest` — proves the Task 21 roster split ("Templates"/"My agents") against
 * [com.aiopenpocket.agent.core.agent.AgentResolver.observeRoster]'s real published/draft/archived
 * semantics, and [AgentsViewModel.openAgentChat]'s create-or-reuse behavior against the real
 * [ConversationRepository].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentsViewModelTest {

  private val testDispatcher = UnconfinedTestDispatcher()

  private lateinit var db: AppDatabase
  private lateinit var userAgentRepository: UserAgentRepository
  private lateinit var conversationRepository: ConversationRepository
  private lateinit var vm: AgentsViewModel

  @Before
  fun setUp() {
    Dispatchers.setMain(testDispatcher)
    val context = ApplicationProvider.getApplicationContext<Context>()
    db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
      .allowMainThreadQueries()
      // By default Room runs queries on ITS OWN real executors, which runTest's virtual clock does not
      // track: the test would read the state before the ViewModel had received it.
      .setQueryExecutor(testDispatcher.asExecutor())
      .setTransactionExecutor(testDispatcher.asExecutor())
      .build()
    userAgentRepository = UserAgentRepository(db, db.userAgentDao(), AgentHookRepository(db.agentHookDao()))
    conversationRepository = ConversationRepository(db.conversationDao(), db.conversationTagDao(), db.messageDao())
    vm = AgentsViewModel(
      agentResolver = DefaultAgentResolver(userAgentRepository),
      conversationRepository = conversationRepository,
      userPreferencesRepository = UserPreferencesRepository(testDataStore(context)),
    )
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
    db.close()
  }

  /** Forks the "budget" preset into a fresh USER definition under [id] — every AgentValidator rule it needs already holds (known tools/skills, matching memory namespace). */
  private fun customUserAgent(id: String): AgentDefinition {
    val preset = MiniAgentCatalog.get("budget")!!.toDefinition()
    return preset.copy(
      id = id,
      source = AgentSource.USER,
      status = AgentStatus.DRAFT,
      configVersion = 0L,
      memoryPolicy = preset.memoryPolicy.copy(namespace = "agent:$id:"),
    )
  }

  // --- Roster sections --------------------------------------------------------------------------

  @Test
  fun `roster splits presets and published user agents into deterministic sections`() = runTest(testDispatcher) {
    userAgentRepository.saveDraft(customUserAgent("my-custom-agent"))
    userAgentRepository.publish("my-custom-agent")

    // vm.roster is stateIn(..., WhileSubscribed(...)) — reading .value with no subscriber would
    // only ever see its initial (empty) seed; .first() subscribes, which is what actually starts the
    // upstream AgentResolver.observeRoster() collection (mirrors AgentInspectorViewModelTest's
    // vm.state.first { ... } over its own WhileSubscribed state, for the same reason).
    val roster = vm.roster.first()

    assertEquals(MiniAgentCatalog.all.map { it.id }, roster.presets.map { it.id })
    assertTrue(roster.presets.all { it.source == AgentSource.PRESET })
    assertEquals(listOf("my-custom-agent"), roster.userAgents.map { it.id })
    assertTrue(roster.userAgents.all { it.source == AgentSource.USER })
  }

  @Test
  fun `draft and archived user agents never appear in the roster`() = runTest(testDispatcher) {
    userAgentRepository.saveDraft(customUserAgent("draft-agent"))
    userAgentRepository.saveDraft(customUserAgent("to-be-archived"))
    userAgentRepository.publish("to-be-archived")
    userAgentRepository.archive("to-be-archived")

    val roster = vm.roster.first()

    assertTrue("draft agent must not appear", roster.userAgents.none { it.id == "draft-agent" })
    assertTrue("archived agent must not appear", roster.userAgents.none { it.id == "to-be-archived" })
  }

  /**
   * A draft is not startable, so it stays out of "My agents" — but it must still be listed
   * SOMEWHERE, or an agent whose wizard was closed before "Publish" is written to Room by
   * autosave and then shown by nothing at all. Archived agents stay invisible in both sections.
   */
  @Test
  fun `unfinished drafts get their own roster section, archived agents get none`() = runTest(testDispatcher) {
    userAgentRepository.saveDraft(customUserAgent("draft-agent"))
    userAgentRepository.saveDraft(customUserAgent("to-be-archived"))
    userAgentRepository.publish("to-be-archived")
    userAgentRepository.archive("to-be-archived")

    val roster = vm.roster.first()

    assertEquals(listOf("draft-agent"), roster.drafts.map { it.id })
    assertTrue("archived agent is in no section at all", roster.drafts.none { it.id == "to-be-archived" })
  }

  /**
   * Editing a published agent must not drop it out of "My agents". Autosave used to force the row
   * back to DRAFT on every keystroke, so a user who typed one character and backed out lost the
   * agent from the roster entirely.
   */
  @Test
  fun `saving edits to a published agent keeps it in the published section`() = runTest(testDispatcher) {
    userAgentRepository.saveDraft(customUserAgent("live-agent"))
    userAgentRepository.publish("live-agent")

    val edited = userAgentRepository.get("live-agent")!!.copy(name = "Переименованный")
    userAgentRepository.saveDraft(edited)

    val roster = vm.roster.first()
    assertEquals(listOf("live-agent"), roster.userAgents.map { it.id })
    assertEquals("Переименованный", roster.userAgents.single().name)
    assertTrue("and it must NOT show up as an unfinished draft", roster.drafts.isEmpty())
  }

  // --- Open chat ---------------------------------------------------------------------------------

  /**
   * Waits for onReady for real, not with advanceUntilIdle(): openAgentChat reads the persona from
   * UserPreferencesRepository, that is, from DataStore, which lives on ITS OWN Dispatchers.IO — runTest's
   * virtual clock does not track it (unlike Room, its executors cannot be swapped), and
   * advanceUntilIdle() would return before the callback had fired.
   */
  private suspend fun awaitReady(timeoutMs: Long = 5_000, get: () -> String?): String =
    withContext(Dispatchers.Default) {
      withTimeout(timeoutMs) {
        while (get() == null) delay(10)
        get()!!
      }
    }

  @Test
  fun `openAgentChat creates a conversation carrying the agent id`() = runTest(testDispatcher) {
    var readyId: String? = null

    vm.openAgentChat("budget") { id -> readyId = id }

    val conversationId = awaitReady { readyId }
    val conversation = conversationRepository.getConversation(conversationId)
    assertEquals("budget", conversation?.agentId)
    assertNotNull(conversation)
  }

  /**
   * The builder's TEST step creates a conversation bound to the SAME agentId, so without the
   * service tag "Open chat" would hand the user the builder's scratch chat — test messages and
   * all — instead of a fresh one.
   */
  @Test
  fun `openAgentChat never reuses the builder's throwaway test conversation`() = runTest(testDispatcher) {
    val testConversationId = conversationRepository.createConversation(
      title = "Тест агента",
      agentId = "budget",
      tags = listOf(ConversationRepository.BUILDER_TEST_TAG),
    )
    var readyId: String? = null

    vm.openAgentChat("budget") { id -> readyId = id }

    val conversationId = awaitReady { readyId }
    assertTrue("must not land in the builder's test chat", conversationId != testConversationId)
    assertEquals("budget", conversationRepository.getConversation(conversationId)?.agentId)
  }

  /**
   * A builder test conversation that was not cleaned up in time (the process was killed mid-check) must
   * not hang in the chat list: it is a scratch space, not the user's chat.
   */
  @Test
  fun `a leftover builder test conversation is not listed among conversations`() = runTest(testDispatcher) {
    val testConversationId = conversationRepository.createConversation(
      title = "Тест агента",
      agentId = "budget",
      tags = listOf(ConversationRepository.BUILDER_TEST_TAG),
    )
    val realConversationId = conversationRepository.createConversation(title = "Обычный чат")

    val listed = conversationRepository.getAllConversations().first().map { it.id }

    assertTrue("the builder's scratch chat must stay out of the list", testConversationId !in listed)
    assertTrue(realConversationId in listed)
  }

  @Test
  fun `openAgentChat reuses the existing conversation on a repeat call`() = runTest(testDispatcher) {
    var firstId: String? = null
    var secondId: String? = null

    vm.openAgentChat("budget") { id -> firstId = id }
    val first = awaitReady { firstId }
    vm.openAgentChat("budget") { id -> secondId = id }
    val second = awaitReady { secondId }

    assertEquals(first, second)
  }

  // --- "Create agent" opens a builder chat -------------------------------------------------------

  @Test
  fun `openBuilderChat creates a conversation bound to the hidden builder with a greeting`() = runTest(testDispatcher) {
    var readyId: String? = null

    vm.openBuilderChat { id -> readyId = id }

    val conversationId = awaitReady { readyId }
    assertEquals(BuilderAgent.ID, conversationRepository.getConversation(conversationId)?.agentId)
    val messages = conversationRepository.getAllMessages(conversationId)
    assertEquals(1, messages.size)
    assertEquals("assistant", messages.single().role)
    assertEquals(builderGreeting(), messages.single().content)
  }

  /** Every agent the person builds gets its own conversation, and so its own draft: no reuse, unlike an agent's chat. */
  @Test
  fun `openBuilderChat starts a new conversation every time`() = runTest(testDispatcher) {
    var firstId: String? = null
    var secondId: String? = null

    vm.openBuilderChat { id -> firstId = id }
    val first = awaitReady { firstId }
    vm.openBuilderChat { id -> secondId = id }
    val second = awaitReady { secondId }

    assertTrue("a second builder chat must be a new conversation", first != second)
  }

  @Test
  fun `the builder chat does not appear in the agent roster`() = runTest(testDispatcher) {
    var readyId: String? = null
    vm.openBuilderChat { id -> readyId = id }
    awaitReady { readyId }

    val roster = vm.roster.first()

    assertTrue((roster.presets + roster.userAgents + roster.drafts).none { it.id == BuilderAgent.ID })
  }
}
