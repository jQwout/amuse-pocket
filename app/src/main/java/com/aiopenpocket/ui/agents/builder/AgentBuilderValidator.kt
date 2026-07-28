package com.aiopenpocket.ui.agents.builder

// @trace user-agent-builder-848153

import com.aiopenpocket.agent.core.agent.AgentValidator
import com.aiopenpocket.agent.core.agent.ValidationIssue
import com.aiopenpocket.agent.core.skill.validateUserSkill

/**
 * Slices [AgentValidator]'s domain rules across the six [BuilderStep]s so the wizard can show each
 * step only the errors it owns and gate "Next"/"Publish" accordingly. Never reimplements a domain
 * rule itself: [validate] is a pure pass-through to [AgentValidator.validate] over
 * [AgentDraft.toDefinition]; everything else here only routes a [ValidationIssue.field] to the
 * [BuilderStep] whose UI surfaces that field.
 */
object AgentBuilderValidator {

  /**
   * [ValidationIssue.field] -> the step that field's control lives on. Every issue
   * [AgentValidator.validate] can currently produce sets one of these exact field names
   * (`id`/`name`/`description`/`iconKey`/`memoryPolicy.namespace`/`systemPrompt`/`toolNames`/
   * `skillIds`/`allowedWidgetTemplates`/`defaultMode`/`hooks` — verified against that function
   * 2026-07-13); [stepFor]'s fallback to [BuilderStep.IDENTITY] for anything else is a defensive
   * floor for a future rule this map hasn't been updated for, not a reachable case today.
   */
  private val FIELD_TO_STEP: Map<String, BuilderStep> = mapOf(
    "id" to BuilderStep.IDENTITY,
    "name" to BuilderStep.IDENTITY,
    "description" to BuilderStep.IDENTITY,
    "iconKey" to BuilderStep.IDENTITY,
    "memoryPolicy.namespace" to BuilderStep.IDENTITY,
    "systemPrompt" to BuilderStep.INSTRUCTIONS,
    "toolNames" to BuilderStep.CAPABILITIES,
    "skillIds" to BuilderStep.CAPABILITIES,
    "allowedWidgetTemplates" to BuilderStep.CAPABILITIES,
    "defaultMode" to BuilderStep.LOOP,
    "hooks" to BuilderStep.HOOKS,
  )

  /**
   * Every rule violation [draft] currently has: [AgentValidator.validate] over the definition, plus
   * [validateUserSkill] over each authored custom skill body.
   *
   * The agent's own [AgentDraft.customSkills] ids are passed as `ownedCustomSkillIds`, so a skill the
   * user just authored validates as known instead of `unknown_skill`. This has to agree exactly with
   * what [com.aiopenpocket.agent.core.agent.UserAgentRepository.publish] checks — it derives the same
   * set from the `skills` argument [AgentBuilderViewModel.publish] passes it — or the wizard would
   * green-light a publish the repository then rejects (or vice versa).
   *
   * Skill-content issues are re-tagged to the `skillIds` field so [FIELD_TO_STEP] routes them to the
   * CAPABILITIES step where that editor lives; [validateUserSkill]'s own field names
   * (`instructionsMarkdown`/`id`) describe a `user_skills` row, not a wizard control.
   */
  fun validate(draft: AgentDraft): List<ValidationIssue> =
    AgentValidator.validate(draft.toDefinition(), draft.customSkills.mapTo(mutableSetOf()) { it.id }) +
      draft.toSkillEntities().flatMap { skill ->
        validateUserSkill(skill).map { it.copy(field = "skillIds") }
      }

  /** Which [BuilderStep] owns [issue] — the step whose UI should display it inline. */
  fun stepFor(issue: ValidationIssue): BuilderStep = FIELD_TO_STEP[issue.field] ?: BuilderStep.IDENTITY

  /** Every step that has at least one current error — Task 20 can use this to badge the step rail. */
  fun blockingSteps(errors: List<ValidationIssue>): Set<BuilderStep> = errors.mapTo(mutableSetOf(), ::stepFor)

  /** The subset of [errors] that belong to [step] — what that step's composable renders inline. */
  fun errorsForStep(errors: List<ValidationIssue>, step: BuilderStep): List<ValidationIssue> =
    errors.filter { stepFor(it) == step }

  /** Gates the wizard's "Next" control: only [step]'s OWN errors block leaving it. */
  fun canAdvanceFrom(step: BuilderStep, errors: List<ValidationIssue>): Boolean =
    errorsForStep(errors, step).isEmpty()

  /** Gates "Publish": every error across all six steps must be clear, matching [AgentValidator.requirePublishable]. */
  fun canPublish(errors: List<ValidationIssue>): Boolean = errors.isEmpty()
}
