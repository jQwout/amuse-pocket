package com.aiopenpocket.agent.core.plan.validation

// @trace structured-plan-engine-907066

import com.aiopenpocket.agent.core.plan.model.PlanStep
import com.aiopenpocket.agent.core.plan.model.PlanVersion

/**
 * Whether a proposed edit to an approved [PlanVersion] must go back through
 * [PlanStateMachine.proposeRevision] + a fresh user approval, or may simply be written in place.
 * [MATERIAL] is what the plan's Global Constraints call "a material revision" — it always forces a
 * new pending version (design spec section 6). [COSMETIC] is wording/description-only and may edit
 * the existing version's content directly. [NONE] means the two versions being compared are
 * content-identical (nothing for a caller to do at all).
 */
enum class PlanChangeKind { NONE, COSMETIC, MATERIAL }

/**
 * Result of [PlanChangeClassifier.classify]: the overall [kind], plus exactly which named fields
 * differed (see the field-name constants on [PlanChangeClassifier]) — so a caller/UI can explain
 * *why* a revision is material instead of only showing a yes/no.
 */
data class PlanChangeClassification(
  val kind: PlanChangeKind,
  val changedFields: Set<String>,
)

/**
 * Pure diff/classifier between two [PlanVersion]s of the same plan (design spec
 * `2026-07-12-structured-plan-engine-design.md` section 6; plan's Global Constraints: "material
 * revision always creates a new pending version"). No I/O, never throws — every difference is
 * folded into a [PlanChangeClassification], the same "return a value, don't throw" convention as
 * [PlanStateMachine]/[PlanValidator].
 *
 * ## Signature enrichment beyond `task-2-brief.md`'s literal `classify(old, new): PlanChangeKind`
 *
 * This task's own instructions explicitly ask for "a typed classification (+ which fields
 * changed)", so [classify] returns [PlanChangeClassification] (kind + [PlanChangeClassification
 * .changedFields]) rather than a bare [PlanChangeKind]. This mirrors Task 1's own precedent of
 * enriching a brief's literal one-liner return type when the actual instructions ask for more
 * (`PlanStateMachine.transition` returning `PlanTransitionResult` instead of a bare `PlanStatus`) —
 * `.kind` alone is exactly the brief's literal contract.
 *
 * ## What this does NOT check
 *
 * [classify] is a pure content diff, nothing else:
 * - It does not re-validate `planId`/version-sequencing legality (same plan? exactly
 *   `approved.version + 1`? not pre-approved?) — that is entirely
 *   [PlanStateMachine.proposeRevision]'s job, already built in Task 1, and deliberately not
 *   duplicated here.
 * - It never sees per-run progress at all: [PlanVersion] itself carries none of
 *   `com.aiopenpocket.agent.core.plan.model.PlanStepState`'s fields (`status`/`attempts`/
 *   `nextDueAt`/timestamps) or [com.aiopenpocket.agent.core.plan.model.PlanEvidence] — those live
 *   in separate, normalized tables (design spec section 6). Task 2's own brief calls this out
 *   directly: "Non-material progress fields are absent from `PlanVersion` and therefore cannot
 *   produce a material diff." There is nothing to special-case; a `(PlanVersion, PlanVersion)` diff
 *   structurally cannot see them.
 * - `planId`, `version` and `createdBy`/`approvedAt` are identity/provenance, not plan *content* —
 *   excluded from the diff entirely, the same way [PlanVersion]'s own kdoc separates "frozen plan
 *   content" from the version's identity/approval bookkeeping around it.
 *
 * ## The exact material-change field list (so later tasks match)
 *
 * MATERIAL (any one of these present in [PlanChangeClassification.changedFields] makes the whole
 * result [PlanChangeKind.MATERIAL]) — verbatim from design spec section 6 plus this task's own
 * explicit "dependencies" callout:
 * - [FIELD_OBJECTIVE], [FIELD_CONSTRAINTS], [FIELD_CRITERIA] — spec: "objective, constraints или
 *   criteria". **Unlike steps below, no wording-only carve-out exists for a single criterion's
 *   `description` text** — the spec never breaks `criteria` down into sub-fields the way it does
 *   for steps (see [FIELD_STEP_WORDING]), so any change anywhere inside the criteria list is
 *   treated as one atomic, always-material bucket. Flagged as a deliberate reading, not an
 *   oversight, in the task report.
 * - [FIELD_STEPS] — spec: "добавление/удаление/reorder steps". Compared as an ordered id list, so
 *   a same-set-different-order change is caught the same as an add/remove.
 * - [FIELD_STEP_DEPENDS_ON] — a step's own `dependsOn` changing. Not literally itemized in the
 *   design spec's section-6 bullet list, but explicitly named in this task's own assignment
 *   ("goal/step-set/**dependencies**/expectedTools/criteria...") and squarely a structural,
 *   execution-order-changing edit by any reasonable reading — included as material.
 * - [FIELD_STEP_EXPECTED_TOOLS] — spec: "изменение expected tools".
 * - [FIELD_STEP_SCHEDULE] — spec: "изменение schedule".
 * - [FIELD_STEP_COMPLETION_RULE] — spec: "изменение completion rule".
 *
 * COSMETIC-only (present in `changedFields` but never by itself escalates to MATERIAL):
 * - [FIELD_STEP_WORDING] — a matched step's `title`/`instruction` text changing while its id,
 *   position, `dependsOn`, `expectedTools`, `schedule` and `completionRule` all stay the same. This
 *   is exactly this task's brief's own "wording/description-only → may edit in place" example.
 * - [FIELD_ASSUMPTIONS] — **UNSURE, flagged in the task report**: design spec section 6 never
 *   mentions `assumptions` in either its material or (implicitly, by omission) non-material list at
 *   all, even though it sits right next to `objective`/`constraints` on [PlanVersion]. Read here as
 *   a deliberate omission (parallel to steps' own title/instruction carve-out) rather than assumed
 *   material, since this task's instructions ask to be "faithful to the spec's exact material-change
 *   definition" and inventing an unstated rule risks the opposite mistake. The safety-first
 *   counter-argument (an edited assumption silently changing what a plan means without forcing
 *   re-approval) is real; Task 5/10 should confirm or override this explicitly rather than assume
 *   it was settled.
 *
 * "budget" is not a field of [PlanVersion] at all (see [PlanValidator]'s kdoc on the same point) —
 * there is nothing named "budget" for this diff to ever produce.
 */
object PlanChangeClassifier {

  const val FIELD_OBJECTIVE = "objective"
  const val FIELD_CONSTRAINTS = "constraints"
  const val FIELD_CRITERIA = "criteria"
  const val FIELD_STEPS = "steps"
  const val FIELD_STEP_DEPENDS_ON = "steps.dependsOn"
  const val FIELD_STEP_EXPECTED_TOOLS = "steps.expectedTools"
  const val FIELD_STEP_SCHEDULE = "steps.schedule"
  const val FIELD_STEP_COMPLETION_RULE = "steps.completionRule"
  const val FIELD_STEP_WORDING = "steps.wording"
  const val FIELD_ASSUMPTIONS = "assumptions"

  private val MATERIAL_FIELDS = setOf(
    FIELD_OBJECTIVE,
    FIELD_CONSTRAINTS,
    FIELD_CRITERIA,
    FIELD_STEPS,
    FIELD_STEP_DEPENDS_ON,
    FIELD_STEP_EXPECTED_TOOLS,
    FIELD_STEP_SCHEDULE,
    FIELD_STEP_COMPLETION_RULE,
  )

  fun classify(old: PlanVersion, new: PlanVersion): PlanChangeClassification {
    val changed = mutableSetOf<String>()
    if (old.objective != new.objective) changed += FIELD_OBJECTIVE
    if (old.assumptions != new.assumptions) changed += FIELD_ASSUMPTIONS
    if (old.constraints != new.constraints) changed += FIELD_CONSTRAINTS
    if (old.criteria != new.criteria) changed += FIELD_CRITERIA
    changed += stepDiffFields(old.steps, new.steps)

    val kind = when {
      changed.isEmpty() -> PlanChangeKind.NONE
      changed.any { it in MATERIAL_FIELDS } -> PlanChangeKind.MATERIAL
      else -> PlanChangeKind.COSMETIC
    }
    return PlanChangeClassification(kind, changed)
  }

  /**
   * Step-set membership/order is compared first (as an ordered id list, so add/remove/reorder are
   * all caught the same way); only ids present in BOTH lists are compared field-by-field so a
   * step-set change never masks or is masked by a per-step wording/structural change on the steps
   * that didn't move.
   */
  private fun stepDiffFields(old: List<PlanStep>, new: List<PlanStep>): Set<String> {
    val changed = mutableSetOf<String>()
    if (old.map { it.id } != new.map { it.id }) changed += FIELD_STEPS

    val oldById = old.associateBy { it.id }
    val newById = new.associateBy { it.id }
    for (id in oldById.keys intersect newById.keys) {
      val o = oldById.getValue(id)
      val n = newById.getValue(id)
      if (o.dependsOn != n.dependsOn) changed += FIELD_STEP_DEPENDS_ON
      if (o.expectedTools != n.expectedTools) changed += FIELD_STEP_EXPECTED_TOOLS
      if (o.schedule != n.schedule) changed += FIELD_STEP_SCHEDULE
      if (o.completionRule != n.completionRule) changed += FIELD_STEP_COMPLETION_RULE
      if (o.title != n.title || o.instruction != n.instruction) changed += FIELD_STEP_WORDING
    }
    return changed
  }
}
