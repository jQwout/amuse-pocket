package com.aiopenpocket.agent.core.hook

// @trace user-agent-builder-848153

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [HookEngine] is a pure function — no Robolectric, no I/O, no coroutines needed. Covers exactly the
 * rules the design spec (`2026-07-12-user-agent-builder-design.md` section 10) and the brief pin
 * down: `(priority, id)` ordering, the per-event action matrix, `DENY > ALLOW`, condition mismatch,
 * the per-run execution limit, and `ON_ERROR` non-recursion.
 */
class HookEngineTest {

  private fun hook(
    id: String,
    event: HookEvent,
    actionType: HookActionType,
    priority: Int = 0,
    enabled: Boolean = true,
    conditionType: String = "always",
    conditionJson: String = "{}",
    actionJson: String = "{}",
    agentId: String = "agent-1",
  ) = AgentHook(
    id = id,
    agentId = agentId,
    event = event,
    conditionType = conditionType,
    conditionJson = conditionJson,
    actionType = actionType,
    actionJson = actionJson,
    priority = priority,
    enabled = enabled,
  )

  private fun instructionHook(id: String, priority: Int, text: String, event: HookEvent = HookEvent.BEFORE_TURN) =
    hook(id = id, event = event, actionType = HookActionType.ADD_INSTRUCTION, priority = priority, actionJson = """{"text":"$text"}""")

  // --- ordering ----------------------------------------------------------------------------

  @Test
  fun `hooks apply in priority-then-id order`() {
    val hooks = listOf(
      instructionHook(id = "b", priority = 1, text = "B"),
      instructionHook(id = "a", priority = 1, text = "A"),
      instructionHook(id = "z", priority = 0, text = "Z"),
    )

    val decision = HookEngine.evaluate(hooks, HookEvent.BEFORE_TURN, HookContext())

    // priority 0 ("z") first, then priority 1 hooks tie-broken by id ascending ("a" before "b").
    assertEquals(listOf("Z", "A", "B"), decision.addedInstructions)
  }

  // --- action/event matrix -------------------------------------------------------------------

  @Test
  fun `action-event matrix matches the design spec exactly`() {
    assertEquals(
      setOf(HookActionType.ADD_INSTRUCTION, HookActionType.LOG, HookActionType.STOP),
      HookEngine.actionsAllowedFor(HookEvent.BEFORE_TURN),
    )
    assertEquals(setOf(HookActionType.LOG, HookActionType.STOP), HookEngine.actionsAllowedFor(HookEvent.AFTER_TURN))
    assertEquals(
      setOf(HookActionType.REQUIRE_APPROVAL, HookActionType.ALLOW, HookActionType.DENY, HookActionType.LOG, HookActionType.STOP),
      HookEngine.actionsAllowedFor(HookEvent.BEFORE_TOOL),
    )
    assertEquals(setOf(HookActionType.LOG, HookActionType.STOP), HookEngine.actionsAllowedFor(HookEvent.AFTER_TOOL))
    assertEquals(setOf(HookActionType.LOG, HookActionType.STOP), HookEngine.actionsAllowedFor(HookEvent.ON_ERROR))
    assertEquals(setOf(HookActionType.LOG), HookEngine.actionsAllowedFor(HookEvent.ON_COMPLETE))
  }

  @Test
  fun `an action not valid for its own event never fires`() {
    // DENY is BEFORE_TOOL-only per the matrix - a hook that (mis)declares it for BEFORE_TURN must be
    // inert, not silently deny every turn.
    val invalid = hook(id = "h1", event = HookEvent.BEFORE_TURN, actionType = HookActionType.DENY)

    val decision = HookEngine.evaluate(listOf(invalid), HookEvent.BEFORE_TURN, HookContext())

    assertEquals(HookDecision(), decision)
  }

  @Test
  fun `only hooks declared for the event being evaluated are considered`() {
    val beforeToolHook = hook(id = "h1", event = HookEvent.BEFORE_TOOL, actionType = HookActionType.DENY)

    // Same hook list, but we're evaluating AFTER_TOOL - the BEFORE_TOOL-scoped hook must not apply.
    val decision = HookEngine.evaluate(listOf(beforeToolHook), HookEvent.AFTER_TOOL, HookContext(toolName = "sms"))

    assertEquals(HookDecision(), decision)
  }

  // --- DENY > ALLOW ----------------------------------------------------------------------------

  @Test
  fun `DENY beats ALLOW regardless of priority order`() {
    val denyFirst = listOf(
      hook(id = "d", event = HookEvent.BEFORE_TOOL, actionType = HookActionType.DENY, priority = 0),
      hook(id = "a", event = HookEvent.BEFORE_TOOL, actionType = HookActionType.ALLOW, priority = 1),
    )
    assertTrue(HookEngine.evaluate(denyFirst, HookEvent.BEFORE_TOOL, HookContext(toolName = "sms")).denied)

    val allowFirst = listOf(
      hook(id = "a", event = HookEvent.BEFORE_TOOL, actionType = HookActionType.ALLOW, priority = 0),
      hook(id = "d", event = HookEvent.BEFORE_TOOL, actionType = HookActionType.DENY, priority = 1),
    )
    assertTrue(HookEngine.evaluate(allowFirst, HookEvent.BEFORE_TOOL, HookContext(toolName = "sms")).denied)
  }

  // --- condition mismatch ------------------------------------------------------------------

  @Test
  fun `condition mismatch skips the hook, a match applies it`() {
    val hooks = listOf(
      hook(
        id = "h1",
        event = HookEvent.BEFORE_TOOL,
        actionType = HookActionType.DENY,
        conditionType = "tool_name_in",
        conditionJson = """{"names":["sms"]}""",
      ),
    )

    val miss = HookEngine.evaluate(hooks, HookEvent.BEFORE_TOOL, HookContext(toolName = "email"))
    assertEquals(HookDecision(), miss)

    val hit = HookEngine.evaluate(hooks, HookEvent.BEFORE_TOOL, HookContext(toolName = "sms"))
    assertTrue(hit.denied)
  }

  @Test
  fun `an unrecognized condition type fails closed - never matches`() {
    val hooks = listOf(hook(id = "h1", event = HookEvent.BEFORE_TOOL, actionType = HookActionType.DENY, conditionType = "made_up_type"))

    val decision = HookEngine.evaluate(hooks, HookEvent.BEFORE_TOOL, HookContext(toolName = "sms"))

    assertEquals(HookDecision(), decision)
  }

  @Test
  fun `a disabled hook never fires even when everything else matches`() {
    val hooks = listOf(hook(id = "h1", event = HookEvent.BEFORE_TOOL, actionType = HookActionType.DENY, enabled = false))

    val decision = HookEngine.evaluate(hooks, HookEvent.BEFORE_TOOL, HookContext(toolName = "sms"))

    assertEquals(HookDecision(), decision)
  }

  // --- per-run execution limit --------------------------------------------------------------

  @Test
  fun `per-run execution limit caps how many soft hooks fire in one call`() {
    val hooks = listOf(
      hook(id = "h1", event = HookEvent.BEFORE_TOOL, actionType = HookActionType.LOG, priority = 0, actionJson = """{"text":"one"}"""),
      hook(id = "h2", event = HookEvent.BEFORE_TOOL, actionType = HookActionType.LOG, priority = 1, actionJson = """{"text":"two"}"""),
      // 3rd hook, lowest priority (fires last if budget allowed it) - a LOG makes the cap's effect observable.
      hook(id = "h3", event = HookEvent.BEFORE_TOOL, actionType = HookActionType.LOG, priority = 2, actionJson = """{"text":"three"}"""),
    )

    val decision = HookEngine.evaluate(hooks, HookEvent.BEFORE_TOOL, HookContext(toolName = "sms"), executedSoFar = 0, maxExecutionsPerRun = 2)

    assertEquals("3rd LOG hook must not fire once the per-run budget is spent", 2, decision.logs.size)
  }

  @Test
  fun `a budget already spent by earlier events in the run means no soft hook fires now`() {
    val hooks = listOf(
      hook(id = "h1", event = HookEvent.BEFORE_TOOL, actionType = HookActionType.LOG, actionJson = """{"text":"x"}"""),
      hook(id = "h2", event = HookEvent.BEFORE_TURN, actionType = HookActionType.ADD_INSTRUCTION, actionJson = """{"text":"y"}"""),
    )

    assertEquals(
      HookDecision(),
      HookEngine.evaluate(hooks, HookEvent.BEFORE_TOOL, HookContext(toolName = "sms"), executedSoFar = 50, maxExecutionsPerRun = 50),
    )
    assertEquals(HookDecision(), HookEngine.evaluate(hooks, HookEvent.BEFORE_TURN, HookContext(), executedSoFar = 50, maxExecutionsPerRun = 50))
  }

  @Test
  fun `an exhausted budget never fails open - DENY, REQUIRE_APPROVAL and STOP still apply`() {
    // Регрессия: раньше matched() делал break на исчерпанном бюджете, и после 50 срабатываний (их
    // легко набрать LOG-хуком на каждый вызов) запрет агента на тул просто переставал действовать.
    val deny = listOf(hook(id = "d", event = HookEvent.BEFORE_TOOL, actionType = HookActionType.DENY))
    val approval = listOf(hook(id = "r", event = HookEvent.BEFORE_TOOL, actionType = HookActionType.REQUIRE_APPROVAL))
    val stop = listOf(hook(id = "s", event = HookEvent.BEFORE_TOOL, actionType = HookActionType.STOP))
    val spent = HookContext(toolName = "sms")

    assertTrue(HookEngine.evaluate(deny, HookEvent.BEFORE_TOOL, spent, executedSoFar = 50, maxExecutionsPerRun = 50).denied)
    assertTrue(HookEngine.evaluate(approval, HookEvent.BEFORE_TOOL, spent, executedSoFar = 50, maxExecutionsPerRun = 50).approvalRequired)
    assertTrue(HookEngine.evaluate(stop, HookEvent.BEFORE_TOOL, spent, executedSoFar = 50, maxExecutionsPerRun = 50).stopped)
  }

  @Test
  fun `a guard hook ranked after budget-eating LOG hooks still fires in the same call`() {
    val hooks = listOf(
      hook(id = "h1", event = HookEvent.BEFORE_TOOL, actionType = HookActionType.LOG, priority = 0, actionJson = """{"text":"one"}"""),
      hook(id = "h2", event = HookEvent.BEFORE_TOOL, actionType = HookActionType.LOG, priority = 1, actionJson = """{"text":"two"}"""),
      hook(id = "h3", event = HookEvent.BEFORE_TOOL, actionType = HookActionType.DENY, priority = 2),
    )

    val decision = HookEngine.evaluate(hooks, HookEvent.BEFORE_TOOL, HookContext(toolName = "sms"), executedSoFar = 0, maxExecutionsPerRun = 1)

    assertTrue("DENY ranked last must not be crowded out by LOG hooks spending the budget", decision.denied)
    assertEquals("only one LOG fits the budget; the DENY is journaled on top of it", 2, decision.logs.size)
  }

  @Test
  fun `a condition mismatch still applies to an exempt guard - an over-budget DENY for another tool does not fire`() {
    val hooks = listOf(
      hook(
        id = "d", event = HookEvent.BEFORE_TOOL, actionType = HookActionType.DENY,
        conditionType = "tool_name_in", conditionJson = """{"names":["sms"]}""",
      ),
    )

    val decision = HookEngine.evaluate(hooks, HookEvent.BEFORE_TOOL, HookContext(toolName = "email"), executedSoFar = 50, maxExecutionsPerRun = 50)

    assertFalse(decision.denied)
  }

  // --- ON_ERROR non-recursion ----------------------------------------------------------------

  @Test
  fun `a malformed hook payload during ON_ERROR fails closed without throwing or duplicating`() {
    // Malformed actionJson would throw while decoding if not caught internally. HookEngine has no
    // code path that re-enters evaluate()/matched() to "handle" that failure by re-firing ON_ERROR -
    // exactly one log entry for the broken hook proves it was processed once, not zero (silently
    // swallowed) or 2+ (a recursive retry/duplicate-firing bug).
    val broken = hook(id = "broken", event = HookEvent.ON_ERROR, actionType = HookActionType.LOG, actionJson = "not valid json")

    val decision = HookEngine.evaluate(listOf(broken), HookEvent.ON_ERROR, HookContext(errorMessage = "boom"))

    assertEquals(1, decision.logs.size)
    assertTrue(decision.logs.single().contains("broken"))
    assertFalse(decision.denied)
    assertFalse(decision.stopped)
  }

  // --- misc ------------------------------------------------------------------------------------

  @Test
  fun `no hooks means the neutral default decision`() {
    assertEquals(HookDecision(), HookEngine.evaluate(emptyList(), HookEvent.BEFORE_TURN, HookContext()))
  }

  @Test
  fun `STOP is recorded and does not itself deny or require approval`() {
    val hooks = listOf(hook(id = "s1", event = HookEvent.BEFORE_TOOL, actionType = HookActionType.STOP))

    val decision = HookEngine.evaluate(hooks, HookEvent.BEFORE_TOOL, HookContext(toolName = "sms"))

    assertTrue(decision.stopped)
    assertFalse(decision.denied)
  }

  @Test
  fun `REQUIRE_APPROVAL is recorded distinctly from DENY`() {
    val hooks = listOf(hook(id = "r1", event = HookEvent.BEFORE_TOOL, actionType = HookActionType.REQUIRE_APPROVAL))

    val decision = HookEngine.evaluate(hooks, HookEvent.BEFORE_TOOL, HookContext(toolName = "sms"))

    assertTrue(decision.approvalRequired)
    assertFalse(decision.denied)
  }

  @Test
  fun `matched exposes per-hook firings for journaling`() {
    val hooks = listOf(hook(id = "h1", event = HookEvent.BEFORE_TOOL, actionType = HookActionType.DENY))

    val firings = HookEngine.matched(hooks, HookEvent.BEFORE_TOOL, HookContext(toolName = "sms"))

    assertEquals(listOf(HookEngine.Firing("h1", HookActionType.DENY, "")), firings)
  }
}
