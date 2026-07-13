package com.aiopenpocket.agent.core.policy

// @trace user-agent-builder-848153

import com.aiopenpocket.agent.core.hook.HookDecision
import com.aiopenpocket.agent.core.mcp.RiskClass
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ExecutionPolicy] is a pure function — no Robolectric, no I/O, no coroutines needed. Covers
 * exactly the table task-9-brief.md Step 1 pins down: PREVIEW+READ/WRITE/DESTRUCTIVE/EXTERNAL,
 * LIVE+READ/risky, and hook DENY/REQUIRE_APPROVAL winning unconditionally over mode/risk.
 */
class ExecutionPolicyTest {

  private val noHook = HookDecision()
  private val sampleArgs = mapOf("path" to JsonPrimitive("notes.txt"))

  private fun evaluate(
    mode: ExecutionMode,
    risk: RiskClass,
    hookDecision: HookDecision = noHook,
    toolName: String = "file_tool",
  ) = ExecutionPolicy.evaluate(
    mode = mode,
    risk = risk,
    hookDecision = hookDecision,
    toolName = toolName,
    arguments = sampleArgs,
    runId = "run-1",
    requestId = "req-1",
    conversationId = "conv-1",
  )

  // --- PREVIEW + risk --------------------------------------------------------------------------

  @Test
  fun `PREVIEW plus READ executes`() {
    assertEquals(PolicyDecision.Execute, evaluate(ExecutionMode.PREVIEW, RiskClass.READ))
  }

  @Test
  fun `PREVIEW plus WRITE simulates`() {
    assertTrue(evaluate(ExecutionMode.PREVIEW, RiskClass.WRITE) is PolicyDecision.Simulate)
  }

  @Test
  fun `PREVIEW plus DESTRUCTIVE simulates`() {
    assertTrue(evaluate(ExecutionMode.PREVIEW, RiskClass.DESTRUCTIVE) is PolicyDecision.Simulate)
  }

  @Test
  fun `PREVIEW plus EXTERNAL simulates`() {
    assertTrue(evaluate(ExecutionMode.PREVIEW, RiskClass.EXTERNAL) is PolicyDecision.Simulate)
  }

  @Test
  fun `PREVIEW Simulate reason names the tool and its risk`() {
    val decision = evaluate(ExecutionMode.PREVIEW, RiskClass.DESTRUCTIVE, toolName = "calendar") as PolicyDecision.Simulate
    assertTrue(decision.reason.contains("calendar"))
    assertTrue(decision.reason.contains("DESTRUCTIVE"))
  }

  // --- LIVE + risk (the "agent rule, never weaker than the floor" row: absent a hook, LIVE's
  // un-configurable floor is simply Execute - identical to every pre-Task-9 call) -----------------

  @Test
  fun `LIVE plus READ executes`() {
    assertEquals(PolicyDecision.Execute, evaluate(ExecutionMode.LIVE, RiskClass.READ))
  }

  @Test
  fun `LIVE plus WRITE executes when no hook says otherwise`() {
    assertEquals(PolicyDecision.Execute, evaluate(ExecutionMode.LIVE, RiskClass.WRITE))
  }

  @Test
  fun `LIVE plus DESTRUCTIVE executes when no hook says otherwise`() {
    assertEquals(PolicyDecision.Execute, evaluate(ExecutionMode.LIVE, RiskClass.DESTRUCTIVE))
  }

  @Test
  fun `LIVE plus EXTERNAL executes when no hook says otherwise`() {
    assertEquals(PolicyDecision.Execute, evaluate(ExecutionMode.LIVE, RiskClass.EXTERNAL))
  }

  // --- hook DENY wins unconditionally -----------------------------------------------------------

  @Test
  fun `hook DENY denies in LIVE regardless of risk`() {
    val denied = HookDecision(denied = true)
    assertTrue(evaluate(ExecutionMode.LIVE, RiskClass.READ, denied) is PolicyDecision.Deny)
    assertTrue(evaluate(ExecutionMode.LIVE, RiskClass.DESTRUCTIVE, denied) is PolicyDecision.Deny)
  }

  @Test
  fun `hook DENY denies in PREVIEW too - never silently downgraded to a Simulate`() {
    val denied = HookDecision(denied = true)
    val decision = evaluate(ExecutionMode.PREVIEW, RiskClass.WRITE, denied)
    assertTrue("a hook-denied call must surface as Deny, not a misleading preview_only Simulate", decision is PolicyDecision.Deny)
  }

  @Test
  fun `Deny reason names the tool`() {
    val decision = evaluate(ExecutionMode.LIVE, RiskClass.READ, HookDecision(denied = true), toolName = "sms") as PolicyDecision.Deny
    assertTrue(decision.reason.contains("sms"))
  }

  // --- hook REQUIRE_APPROVAL wins unconditionally ------------------------------------------------

  @Test
  fun `hook REQUIRE_APPROVAL awaits approval in LIVE regardless of risk`() {
    val requiresApproval = HookDecision(approvalRequired = true)
    assertTrue(evaluate(ExecutionMode.LIVE, RiskClass.READ, requiresApproval) is PolicyDecision.AwaitApproval)
    assertTrue(evaluate(ExecutionMode.LIVE, RiskClass.DESTRUCTIVE, requiresApproval) is PolicyDecision.AwaitApproval)
  }

  @Test
  fun `hook REQUIRE_APPROVAL awaits approval in PREVIEW too`() {
    val requiresApproval = HookDecision(approvalRequired = true)
    assertTrue(evaluate(ExecutionMode.PREVIEW, RiskClass.WRITE, requiresApproval) is PolicyDecision.AwaitApproval)
  }

  @Test
  fun `AwaitApproval request carries every field the coordinator and UI need, untouched`() {
    val requiresApproval = HookDecision(approvalRequired = true)
    val decision = ExecutionPolicy.evaluate(
      mode = ExecutionMode.LIVE,
      risk = RiskClass.DESTRUCTIVE,
      hookDecision = requiresApproval,
      toolName = "calendar",
      arguments = sampleArgs,
      runId = "run-42",
      requestId = "req-99",
      conversationId = "conv-7",
    ) as PolicyDecision.AwaitApproval

    assertEquals("req-99", decision.request.requestId)
    assertEquals("run-42", decision.request.runId)
    assertEquals("conv-7", decision.request.conversationId)
    assertEquals("calendar", decision.request.toolName)
    assertEquals(RiskClass.DESTRUCTIVE, decision.request.riskClass)
    assertEquals(sampleArgs, decision.request.arguments)
  }

  @Test
  fun `DENY wins over REQUIRE_APPROVAL when a hook decision somehow carries both`() {
    // Mirrors HookEngine.toDecision's own precedence (a matching DENY always wins over any other
    // action) - ExecutionPolicy must never disagree with the engine that produced hookDecision.
    val both = HookDecision(denied = true, approvalRequired = true)
    assertTrue(evaluate(ExecutionMode.LIVE, RiskClass.READ, both) is PolicyDecision.Deny)
  }
}
