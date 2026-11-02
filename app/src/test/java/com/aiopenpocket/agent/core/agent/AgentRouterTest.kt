package com.aiopenpocket.agent.core.agent

// @trace agent-router-dc2233

import com.aiopenpocket.agent.core.llm.ChatMessage
import com.aiopenpocket.agent.core.llm.FunctionSchema
import com.aiopenpocket.agent.core.llm.LlmClient
import com.aiopenpocket.agent.core.llm.LlmProvider
import com.aiopenpocket.agent.core.llm.StreamEvent
import com.aiopenpocket.agent.loop.AgentMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRouterTest {

  private val ids = setOf("reminders", "budget", "agent-1a2b3c")

  @Test
  fun `the bare id is read`() {
    assertEquals("reminders", parseRoute("reminders", ids))
  }

  @Test
  fun `quotes, case and a short sentence around the id are tolerated`() {
    assertEquals("budget", parseRoute("\"Budget\".", ids))
    assertEquals("agent-1a2b3c", parseRoute("The answer is agent-1a2b3c", ids))
  }

  @Test
  fun `none, an unknown id and two ids all mean no agent`() {
    assertNull(parseRoute("none", ids))
    assertNull(parseRoute("calendar", ids))
    assertNull(parseRoute("reminders or budget", ids))
  }

  @Test
  fun `a part of a longer word is not an id`() {
    assertNull(parseRoute("budgeting", ids))
  }

  @Test
  fun `the prompt lists every candidate with its tools`() {
    val prompt = routerSystemPrompt(listOf(agent("reminders", tools = setOf("heartbeat", "alarm"))))

    assertTrue(prompt, prompt.contains("id: reminders"))
    assertTrue(prompt, prompt.contains("tools: alarm, heartbeat"))
  }

  @Test
  fun `auto hands the message to the agent the model names`() = runTest {
    val router = router(answer = listOf(StreamEvent.TextDelta("reminders")))

    assertEquals("reminders", router.pick("remind me at 6", RouteChoice.Auto)?.id)
  }

  @Test
  fun `the model's thinking is not taken for its answer`() = runTest {
    val router = router(
      answer = listOf(StreamEvent.TextDelta("maybe budget", reasoning = true), StreamEvent.TextDelta("none")),
    )

    assertNull(router.pick("hi there", RouteChoice.Auto))
  }

  @Test
  fun `a failed call leaves the message to the plain assistant`() = runTest {
    val router = router(answer = listOf(StreamEvent.TextDelta("reminders"), StreamEvent.Error("503")))

    assertNull(router.pick("remind me at 6", RouteChoice.Auto))
  }

  @Test
  fun `a fixed pick skips the model and none never routes`() = runTest {
    val llm = FakeLlm(listOf(StreamEvent.TextDelta("reminders")))
    val router = AgentRouter(llm, { LlmProvider.META }, FakeResolver(listOf(agent("reminders"), agent("budget"))))

    assertEquals("budget", router.pick("remind me at 6", RouteChoice.Fixed("budget"))?.id)
    assertNull(router.pick("remind me at 6", RouteChoice.None))
    assertEquals(0, llm.calls)
  }

  private fun router(answer: List<StreamEvent>) =
    AgentRouter(FakeLlm(answer), { LlmProvider.META }, FakeResolver(listOf(agent("reminders"), agent("budget"))))

  private class FakeLlm(private val events: List<StreamEvent>) : LlmClient {
    var calls = 0
      private set

    override fun streamChat(messages: List<ChatMessage>, tools: List<FunctionSchema>, provider: LlmProvider): Flow<StreamEvent> {
      calls++
      return flowOf(*events.toTypedArray())
    }
  }

  private class FakeResolver(private val agents: List<AgentDefinition>) : AgentResolver {
    override suspend fun resolve(id: String?): AgentDefinition? = agents.firstOrNull { it.id == id }
    override fun observeRoster(): Flow<List<AgentDefinition>> = flowOf(agents)
    override fun observeDrafts(): Flow<List<AgentDefinition>> = flowOf(emptyList())
  }

  private fun agent(id: String, tools: Set<String> = setOf("memory_tool")) = AgentDefinition(
    id = id,
    source = AgentSource.USER,
    name = id,
    description = "Agent $id.",
    iconKey = "sparkles",
    systemPrompt = "You are $id.",
    toolNames = tools,
    skillIds = emptySet(),
    defaultMode = AgentMode.CHAT,
    allowedModes = setOf(AgentMode.CHAT),
    memoryPolicy = AgentMemoryPolicy(namespace = "agent:$id:", includeSharedProfile = true),
    allowedWidgetTemplates = emptySet(),
    hooks = emptyList(),
    configVersion = 1,
    status = AgentStatus.PUBLISHED,
  )
}
