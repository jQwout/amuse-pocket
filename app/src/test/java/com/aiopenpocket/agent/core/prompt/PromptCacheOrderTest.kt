package com.aiopenpocket.agent.core.prompt

// @trace the-prompt-cache-keeps-the-whole-chat-history-d25062

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aiopenpocket.agent.core.agent.DefaultAgentResolver
import com.aiopenpocket.agent.core.agent.UserAgentRepository
import com.aiopenpocket.agent.core.hook.AgentHookRepository
import com.aiopenpocket.agent.core.identity.UserPreferencesRepository
import com.aiopenpocket.agent.core.memory.repository.ConversationRepository
import com.aiopenpocket.agent.core.memory.repository.MemoryRepository
import com.aiopenpocket.agent.core.plan.context.PlanContextProvider
import com.aiopenpocket.agent.core.plan.persistence.PlanRepository
import com.aiopenpocket.agent.core.skill.SkillRegistry
import com.aiopenpocket.agent.core.skill.UserSkillRepository
import com.aiopenpocket.data.database.AppDatabase
import com.aiopenpocket.testing.testDataStore
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Meta's prompt cache matches the start of a request token by token. What changes every turn - the minute,
 * the memory - must therefore not sit ahead of the history: the system prompt and every earlier message
 * have to come back byte-for-byte the same in the next turn, or the whole chat is billed at full price again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PromptCacheOrderTest {

  private lateinit var db: AppDatabase
  private lateinit var assembler: PrePromptAssembler
  private lateinit var conversations: ConversationRepository
  private lateinit var memory: MemoryRepository
  private var now = 1_793_829_600_000L

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    conversations = ConversationRepository(db.conversationDao(), db.conversationTagDao(), db.messageDao())
    memory = MemoryRepository(db.factDao(), db.episodeDao())
    assembler = PrePromptAssembler(
      assets = AssetSource { null },
      memoryRepository = memory,
      conversationRepository = conversations,
      skillRegistry = SkillRegistry(emptySet(), UserSkillRepository(db.userSkillDao())),
      userPreferencesRepository = UserPreferencesRepository(testDataStore(context)),
      agentResolver = DefaultAgentResolver(UserAgentRepository(db, db.userAgentDao(), AgentHookRepository(db.agentHookDao()))),
      planContextProvider = PlanContextProvider(PlanRepository(db, db.planDao())),
      clock = { now },
    )
  }

  @After
  fun tearDown() = db.close()

  @Test
  fun `the next turn starts with the same system prompt and the same earlier messages`() = runTest {
    val chat = conversations.createConversation("Chat")
    memory.saveFact("sister", "Masha", "general")
    conversations.addMessage(chat, "user", "hi")
    val first = assembler.assemble(chat, "hi")

    conversations.addMessage(chat, "assistant", "hello")
    conversations.addMessage(chat, "user", "remind me at 19:00")
    now += 7 * 60_000L
    memory.saveFact("dog", "Rex", "general")
    val second = assembler.assemble(chat, "remind me at 19:00")

    assertEquals("the minute and a new fact moved nothing in the system prompt", first.systemPrompt, second.systemPrompt)
    assertEquals("earlier messages come back as the log has them", "hi", second.history[0].content)
    assertEquals("hello", second.history[1].content)
    for (prompt in listOf(first.systemPrompt, second.systemPrompt)) {
      assertFalse(prompt.contains("## Сейчас"))
      assertFalse(prompt.contains("<user_data>"))
    }
  }

  @Test
  fun `the newest message carries the time and the memory after the person's own words`() = runTest {
    val chat = conversations.createConversation("Chat")
    memory.saveFact("sister", "Masha", "general")
    conversations.addMessage(chat, "user", "what is my sister called?")

    val newest = assembler.assemble(chat, "what is my sister called?").history.last().content

    assertTrue(newest.startsWith("what is my sister called?" + PrePromptAssembler.TURN_CONTEXT_SEPARATOR))
    assertTrue(newest.contains("## Сейчас"))
    assertTrue(newest.contains("Masha"))
    assertTrue("the fence still holds the memory", newest.contains("<user_data>") && newest.contains("</user_data>"))
  }

  @Test
  fun `with no user message in the window the context stays in the system prompt`() = runTest {
    val chat = conversations.createConversation("Chat")

    val context = assembler.assemble(chat, "hi")

    assertTrue(context.history.isEmpty())
    assertTrue("the model still knows the time", context.systemPrompt.contains("## Сейчас"))
  }
}
