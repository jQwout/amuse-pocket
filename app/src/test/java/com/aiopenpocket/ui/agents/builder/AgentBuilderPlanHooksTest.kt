package com.aiopenpocket.ui.agents.builder

// @trace structured-plan-engine-907066

import com.aiopenpocket.agent.core.hook.HookActionType
import com.aiopenpocket.agent.core.hook.HookEngine
import com.aiopenpocket.agent.core.hook.HookEvent
import com.aiopenpocket.ui.agents.builder.components.hookActionLabel
import com.aiopenpocket.ui.agents.builder.components.hookEventLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [com.aiopenpocket.ui.agents.builder.components.HookEditorDialog]'s `hookEventLabel`/`hookActionLabel`
 * are plain (non-`@Composable`) functions, so this is a plain JVM test — no Robolectric/Compose test
 * rule needed, mirroring [com.aiopenpocket.agent.core.hook.HookEngineTest]'s own "pure function, plain
 * JUnit" style. Covers Task 9 plan Step 4's "the existing declarative hook editor lists the six new
 * plan events. Its action picker is derived from the same action matrix as AgentValidator": the
 * dialog's `HookEvent.entries.forEach { ... }`/`HookEngine.actionsAllowedFor(event)` calls already
 * generically support any [HookEvent], so the only thing that needed a hand-written addition was
 * `hookEventLabel`'s exhaustive `when` — this pins that it covers every entry and returns a
 * non-blank, distinct label for each.
 */
class AgentBuilderPlanHooksTest {

  @Test
  fun `hookEventLabel returns a non-blank label for every HookEvent, including the six plan events`() {
    val labels = HookEvent.entries.map { hookEventLabel(it) }

    assertEquals(HookEvent.entries.size, labels.size)
    assertTrue("every label must be non-blank", labels.all { it.isNotBlank() })
    assertEquals("every HookEvent must get its OWN distinct label", HookEvent.entries.size, labels.toSet().size)
  }

  @Test
  fun `hookEventLabel covers the six new plan-lifecycle events specifically`() {
    assertEquals("План создан", hookEventLabel(HookEvent.ON_PLAN_CREATED))
    assertEquals("План одобрен", hookEventLabel(HookEvent.ON_PLAN_APPROVED))
    assertEquals("Перед шагом плана", hookEventLabel(HookEvent.BEFORE_PLAN_STEP))
    assertEquals("После шага плана", hookEventLabel(HookEvent.AFTER_PLAN_STEP))
    assertEquals("План заблокирован", hookEventLabel(HookEvent.ON_PLAN_BLOCKED))
    assertEquals("План завершён", hookEventLabel(HookEvent.ON_PLAN_COMPLETED))
  }

  @Test
  fun `hookActionLabel still covers every HookActionType unchanged (no new action types added by Task 9)`() {
    val labels = HookActionType.entries.map { hookActionLabel(it) }

    assertEquals(HookActionType.entries.size, labels.size)
    assertTrue(labels.all { it.isNotBlank() })
  }

  @Test
  fun `the dialog's action picker (HookEngine actionsAllowedFor) is non-empty for every HookEvent, including plan events`() {
    // Mirrors the dialog's own `val allowedActions = HookEngine.actionsAllowedFor(event).toList()` -
    // an event with zero allowed actions would leave the picker with nothing selectable at all.
    for (event in HookEvent.entries) {
      assertTrue("HookEvent.$event must allow at least one action in the builder's picker", HookEngine.actionsAllowedFor(event).isNotEmpty())
    }
  }
}
