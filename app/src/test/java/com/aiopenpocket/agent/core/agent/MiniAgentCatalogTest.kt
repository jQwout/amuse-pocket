package com.aiopenpocket.agent.core.agent

import org.junit.Assert.assertTrue
import org.junit.Test

class MiniAgentCatalogTest {

  @Test
  fun `every roster agent can set heartbeats`() {
    for (agent in MiniAgentCatalog.all) {
      assertTrue("agent ${agent.id} lacks heartbeat tool", "heartbeat" in agent.toolNames)
      assertTrue("agent ${agent.id} prompt omits heartbeat", agent.systemPrompt.contains("heartbeat"))
    }
  }

  @Test
  fun `heartbeat supersedes work_manager in scoped agents`() {
    // heartbeat is built on top of work_manager; the agents' scope no longer needs the old tool.
    for (agent in MiniAgentCatalog.all) {
      assertTrue("agent ${agent.id} still scopes work_manager", "work_manager" !in agent.toolNames)
    }
  }
}
