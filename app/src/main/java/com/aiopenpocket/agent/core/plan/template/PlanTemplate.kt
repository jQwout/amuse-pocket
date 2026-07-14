package com.aiopenpocket.agent.core.plan.template

// @trace structured-plan-engine-907066

import com.aiopenpocket.agent.core.plan.model.PlanHorizon
import com.aiopenpocket.agent.core.plan.model.PlanSchedule
import com.aiopenpocket.agent.core.plan.model.VerifierType
import kotlinx.serialization.Serializable

/**
 * Domain-specific planning guidance for one [com.aiopenpocket.agent.core.agent.AgentDefinition]
 * (design spec `2026-07-12-structured-plan-engine-design.md` section 13: budget/nutrition/
 * languages). The Structured Plan Engine itself stays generic — everything domain-flavored (which
 * horizons make sense, which safety constraints must survive every revision, which tools a plan for
 * this agent must never use, and example steps/criteria to steer the planner LLM) lives here instead
 * of being hard-coded into [com.aiopenpocket.agent.core.plan.planner.PlanPlanner] or
 * [com.aiopenpocket.agent.core.plan.validation.PlanValidator].
 *
 * [PlanTemplateRegistry.getForAgent] is the only way this type is looked up — by
 * [agentId], never by [id] — and Task 13's three initial assets (budget/nutrition/languages) are the
 * only producers of real instances; this task only defines the frozen shape they (and
 * [PlanTemplateRegistry]'s asset-backed implementation) must agree on.
 *
 * @property id This template's own identifier (e.g. `"budget-v1"`) — distinct from [agentId] so a
 *   template can be revised/renamed independently of the agent id it targets.
 * @property agentId The [com.aiopenpocket.agent.core.agent.AgentDefinition.id] this template applies
 *   to. [PlanTemplateRegistry.getForAgent] looks up by this field.
 * @property supportedHorizons Which [PlanHorizon] values a plan for this agent may legally use —
 *   [com.aiopenpocket.agent.core.plan.planner.PlanPlanner] refuses (fails closed) a planning turn
 *   whose caller-chosen horizon is not in this set rather than silently coercing it (see that class's
 *   own kdoc for why horizon is a caller input, not something `submit_plan` lets the model choose).
 * @property requiredConstraints Domain safety/policy constraints (design spec section 7: "medical/
 *   financial safety constraints не удалены domain policy") that MUST survive in every proposed
 *   [com.aiopenpocket.agent.core.plan.model.PlanVersion.constraints] for this agent.
 *   [com.aiopenpocket.agent.core.plan.planner.PlanPlanner] merges these into every candidate before
 *   validating (the model does not have to perfectly restate them for them to end up on the plan),
 *   and the frozen [com.aiopenpocket.agent.core.plan.validation.PlanValidator.validate] independently
 *   re-checks their presence as a defense-in-depth backstop.
 * @property suggestedSteps Example steps shown to the planner LLM as domain guidance (design spec
 *   section 13's budget/nutrition/languages step outlines) — never copied verbatim into a submitted
 *   plan by engine code; purely prompt content the model may draw on.
 * @property suggestedCriteria Example completion criteria, same "prompt guidance only" status as
 *   [suggestedSteps].
 * @property forbiddenToolNames Tool names this domain's plans must never use, even if the bound
 *   agent otherwise has them in its
 *   [com.aiopenpocket.agent.core.agent.AgentDefinition.toolNames] (e.g. a domain that should never
 *   automate a destructive/financial action). [com.aiopenpocket.agent.core.plan.planner.PlanPromptBuilder]
 *   hides these names from the planner's "available tools" section, AND
 *   [com.aiopenpocket.agent.core.plan.planner.PlanControlReducer] independently rejects any step that
 *   still declares one — this repo's house rule is to never trust a safety property to prompt text
 *   alone (mirrors e.g. `MemoryTools`' per-agent namespace isolation being enforced in code, not by
 *   asking the model nicely).
 */
@Serializable
data class PlanTemplate(
  val id: String,
  val agentId: String,
  val supportedHorizons: Set<PlanHorizon>,
  val requiredConstraints: List<String>,
  val suggestedSteps: List<PlanStepTemplate>,
  val suggestedCriteria: List<PlanCriterionTemplate>,
  val forbiddenToolNames: Set<String>,
)

/**
 * One example step a [PlanTemplate] suggests to the planner LLM (design spec section 13, e.g.
 * budget's "еженедельная сверка расходов"). Deliberately NOT shaped like
 * [com.aiopenpocket.agent.core.plan.model.PlanStep]: a template has no concrete step [id]s or
 * [com.aiopenpocket.agent.core.plan.model.PlanStep.dependsOn] graph to offer — those only exist once
 * the LLM actually drafts a real, agent/conversation-specific plan. This is prompt guidance only,
 * never mapped into a submitted [com.aiopenpocket.agent.core.plan.model.PlanVersion] by engine code.
 *
 * UNSURE (flagged in the Task 4 report): the design doc never gives this type a literal shape —
 * only that `PlanTemplate.suggestedSteps` exists. This is this task's own minimal, defensible
 * reading of what "example step" needs to carry to be useful prompt content; Task 13 (the first real
 * consumer) should confirm or extend it before relying on more than [title]/[instruction].
 */
@Serializable
data class PlanStepTemplate(
  val title: String,
  val instruction: String,
  val suggestedExpectedTools: Set<String> = emptySet(),
  val suggestedSchedule: PlanSchedule? = null,
)

/**
 * One example completion criterion a [PlanTemplate] suggests to the planner LLM — the criterion
 * analogue of [PlanStepTemplate]; same "prompt guidance only, no concrete id" status. See
 * [PlanStepTemplate]'s kdoc for the same UNSURE flag: this shape is this task's own reading, not a
 * literal spec quote.
 */
@Serializable
data class PlanCriterionTemplate(
  val description: String,
  val suggestedVerifierType: VerifierType,
  val required: Boolean = true,
)
