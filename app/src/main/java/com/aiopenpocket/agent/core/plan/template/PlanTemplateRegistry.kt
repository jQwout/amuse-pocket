package com.aiopenpocket.agent.core.plan.template

// @trace structured-plan-engine-907066

import com.aiopenpocket.agent.core.prompt.AssetSource
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Looks up the [PlanTemplate] (if any) for an agent (design spec
 * `2026-07-12-structured-plan-engine-design.md` section 13). A `null` result is a completely normal,
 * expected outcome — most agents (and every agent-less plan) have no domain template at all — never
 * an error; callers ([com.aiopenpocket.agent.core.plan.planner.PlanPlanner]) treat it as "no extra
 * domain guidance/constraints for this plan", not a failure.
 *
 * ## Почему шаблоны пресетов сейчас никем не читаются — и это НЕ повод их удалять
 *
 * `assets/plan_templates/` содержит `budget.json`, `languages.json`, `nutrition.json` — методологию
 * планирования для трёх пресетов, которая ищется ровно по их id. Единственный вход в планировщик —
 * режим PLAN из чата (`TurnModule`), а пресетам режимы PLAN/GOAL намеренно не выдаются
 * ([com.aiopenpocket.agent.core.agent.toDefinition] — там же и обоснование). Значит эти три файла
 * сейчас недостижимы ни одним рантайм-путём.
 *
 * Так решено СОЗНАТЕЛЬНО: методология зафиксирована автором, и возможности переписать изначальный
 * план или цель из чата быть не должно. Файлы — не забытый мусор; прежде чем удалять их или
 * возвращать пресетам планирование, сверьтесь с этим решением.
 */
interface PlanTemplateRegistry {
  /**
   * The template bound to [agentId], or `null` when [agentId] is `null` or has no template of its
   * own. Deliberately does NOT fall back to another agent's template on a miss — a missing template
   * is a real, meaningful "this agent has no domain guidance" answer, not something to paper over by
   * guessing (mirrors [com.aiopenpocket.agent.core.agent.AgentResolver.resolve]'s own refusal to
   * substitute a different agent on a lookup miss).
   */
  suspend fun getForAgent(agentId: String?): PlanTemplate?
}

/**
 * Asset-backed [PlanTemplateRegistry]: one JSON file per agent at `assets/plan_templates/<agentId>.json`,
 * decoded against the frozen [PlanTemplate] shape. This task defines the registry and its asset
 * convention; Task 13 is the first task that actually adds `plan_templates/budget.json`,
 * `plan_templates/nutrition.json` and `plan_templates/languages.json` — until then every
 * [getForAgent] call legitimately returns `null` for every agent, same as "no template" (there is
 * nothing else this class could do with assets that do not exist yet).
 *
 * Mirrors `com.aiopenpocket.agent.core.lesson.LessonRepository`'s own "load from bundled assets,
 * null/failure on a miss" shape, but reads through the [AssetSource] port (like
 * [com.aiopenpocket.agent.core.prompt.PrePromptAssembler]) rather than an injected `Context`
 * directly, so this class stays constructible/testable with a plain fake in a pure JUnit test —
 * consistent with the rest of this task's files never depending on Robolectric.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class AssetPlanTemplateRegistry(
  private val assets: AssetSource,
) : PlanTemplateRegistry {

  private val json = Json { ignoreUnknownKeys = true }

  override suspend fun getForAgent(agentId: String?): PlanTemplate? {
    if (agentId == null) return null
    return withContext(Dispatchers.IO) {
      val raw = assets.load("$TEMPLATE_ASSET_DIR/$agentId.json") ?: return@withContext null
      // A malformed/mismatched asset is treated the same as "no template" (fail-open on this
      // decoration-only lookup) rather than crashing a planning turn over a bundled-content bug —
      // the plan itself still validates against `PlanValidator`'s own rules regardless.
      runCatching { json.decodeFromString(PlanTemplate.serializer(), raw) }.getOrNull()
    }
  }

  private companion object {
    const val TEMPLATE_ASSET_DIR = "plan_templates"
  }
}
