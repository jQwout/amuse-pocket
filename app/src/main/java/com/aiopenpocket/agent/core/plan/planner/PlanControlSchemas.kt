package com.aiopenpocket.agent.core.plan.planner

// @trace structured-plan-engine-907066

import com.aiopenpocket.agent.core.llm.FunctionSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The internal, PLAN-only function-call schemas the planner LLM sees during a planning turn (design
 * spec `2026-07-12-structured-plan-engine-design.md` section 7). These three names —
 * [SUBMIT_PLAN]/[REQUEST_PLAN_INFORMATION]/[PROPOSE_PLAN_REVISION], mirroring the frozen
 * `com.aiopenpocket.agent.core.plan.validation.PlanValidator.PLANNER_CONTROL_TOOL_NAMES` set from
 * Task 2 — never name a real device/app capability and can never legally appear in a
 * `com.aiopenpocket.agent.core.plan.model.PlanStep.expectedTools` (`PlanValidator.toolScopeIssues`
 * already enforces that independently of this file).
 *
 * ## Why these are not [com.aiopenpocket.agent.core.mcp.Tool]s
 *
 * A real [com.aiopenpocket.agent.core.mcp.Tool] is discovered by Metro (`Set<@JvmSuppressWildcards
 * Tool>` multibinding), added to `com.aiopenpocket.agent.core.mcp.ToolRegistry`, and becomes callable
 * from ANY turn whose scoped tool set includes its name. That is exactly the opposite of what a
 * planner control call is: it can only ever legally appear on ONE specific kind of turn (a
 * [PlanPlanner] planning call), it never executes anything ([com.aiopenpocket.agent.core.mcp
 * .ToolExecutor] never sees it — `PlanControlReducer` does), and it must be structurally impossible
 * for a normal chat/GOAL turn to ever hand one to the model by accident. Not implementing
 * [com.aiopenpocket.agent.core.mcp.Tool] and never adding these to the multibinding set is what makes
 * that impossible rather than merely policy: there is no Metro wiring that could ever put these three
 * schemas into `com.aiopenpocket.agent.core.mcp.ToolRegistry.getAllSchemas()`/`getSchemasFor(...)` in
 * the first place. [PlanPlanner] is the only code in this codebase that ever passes [ALL] to
 * `com.aiopenpocket.agent.core.llm.LlmClient.streamChat`.
 *
 * ## What each call means
 *
 * - [SUBMIT_PLAN] — propose a brand-new plan (or a full from-scratch redraft while still
 *   [com.aiopenpocket.agent.core.plan.model.PlanStatus.DRAFT]/[com.aiopenpocket.agent.core.plan.model
 *   .PlanStatus.NEEDS_INFO]): the ENTIRE plan content (objective/assumptions/constraints/steps/
 *   criteria), never a partial patch.
 * - [PROPOSE_PLAN_REVISION] — same full-content shape as [SUBMIT_PLAN], but explicitly for revising
 *   an already-approved/pending version shown in the prompt's "current version" section (design spec
 *   section 6: a material revision is always a new, complete version — never a diff the engine has to
 *   merge). `PlanControlReducer` derives the correct next version number in code; the model is never
 *   asked to state one itself.
 * - [REQUEST_PLAN_INFORMATION] — context is missing; ask exactly one question instead of guessing
 *   (replaces the old free-text `[[NEED_INFO]]` marker from `rezhimy-agenta-plan-i-goal-ee2361` with a
 *   typed call).
 *
 * Neither content schema asks the model for a `PlanHorizon` — see [PlanPlanner]'s own kdoc for why
 * horizon is a caller-supplied input to a planning turn, not something `submit_plan`/
 * `propose_plan_revision` lets the model pick.
 *
 * ## A note on declaration order in this file
 *
 * [ALL]/[NAMES] are declared at the BOTTOM, after every private schema piece they use. `object`
 * property initializers run top-to-bottom at class-init time (like a Java static initializer); a
 * `val` that reads ANOTHER `val` declared further down would see that field's not-yet-run,
 * JVM-default (`null`) state rather than its real value, despite both being non-null `JsonObject`
 * types. The small builder *functions* below (`stringSchema`, `objectSchema`, etc.) are exempt —
 * calling a function never depends on "has its declaration been reached yet," only the `val`s that
 * cache a composite schema do.
 */
object PlanControlSchemas {

  const val SUBMIT_PLAN = "submit_plan"
  const val REQUEST_PLAN_INFORMATION = "request_plan_information"
  const val PROPOSE_PLAN_REVISION = "propose_plan_revision"

  // --- tiny JSON-schema builder functions (no ordering hazard: a function body only runs when
  // called, never eagerly against sibling state) --------------------------------------------------

  private fun stringSchema(description: String, enum: List<String>? = null): JsonObject = JsonObject(
    buildMap {
      put("type", JsonPrimitive("string"))
      put("description", JsonPrimitive(description))
      // JSON Schema требует enum МАССИВОМ — иначе constrained decoding модели его игнорирует.
      if (enum != null) put("enum", JsonArray(enum.map { JsonPrimitive(it) }))
    },
  )

  private fun integerSchema(description: String): JsonObject = JsonObject(
    mapOf("type" to JsonPrimitive("integer"), "description" to JsonPrimitive(description)),
  )

  private fun booleanSchema(description: String): JsonObject = JsonObject(
    mapOf("type" to JsonPrimitive("boolean"), "description" to JsonPrimitive(description)),
  )

  /**
   * A free-form JSON object with no fixed sub-properties — [CriterionVerifier][com.aiopenpocket.agent
   * .core.plan.model.CriterionVerifier.config]'s actual shape depends on its sibling `type` field, so the
   * schema itself cannot enumerate fixed sub-properties (mirrors those types' own "opaque,
   * engine-interpreted payload" kdoc from Task 1).
   */
  private fun openObjectSchema(description: String): JsonObject = JsonObject(
    mapOf("type" to JsonPrimitive("object"), "description" to JsonPrimitive(description)),
  )

  private fun arraySchema(description: String, items: JsonObject): JsonObject = JsonObject(
    mapOf("type" to JsonPrimitive("array"), "description" to JsonPrimitive(description), "items" to items),
  )

  private fun stringArraySchema(description: String): JsonObject = arraySchema(description, stringSchema("Элемент списка."))

  private fun objectSchema(
    description: String,
    properties: Map<String, JsonObject>,
    required: List<String>,
  ): JsonObject = JsonObject(
    mapOf(
      "type" to JsonPrimitive("object"),
      "description" to JsonPrimitive(description),
      "properties" to JsonObject(properties),
      "required" to JsonArray(required.map { JsonPrimitive(it) }),
    ),
  )

  // --- composite schema pieces, each precomputed once. Ordering is deliberate — see the class kdoc
  // "A note on declaration order": every val below only refers to a val ALREADY declared above it. -

  private val scheduleSchema: JsonObject = objectSchema(
    description = "Расписание шага. Указывай ТОЛЬКО для долгой программы (PROGRAM) — для разовой " +
      "задачи (SINGLE_RUN) не указывай schedule вовсе.",
    properties = mapOf(
      "kind" to stringSchema(
        "ONE_TIME — разовое срабатывание через delayMinutes; PERIODIC — повтор каждые intervalMinutes.",
        enum = listOf("ONE_TIME", "PERIODIC"),
      ),
      "delayMinutes" to integerSchema("Через сколько минут сработает ONE_TIME-шаг."),
      "intervalMinutes" to integerSchema("Период повтора PERIODIC-шага в минутах (минимум 15)."),
    ),
    required = listOf("kind"),
  )

  private val verifierSchema: JsonObject = objectSchema(
    description = "Как проверяется критерий готовности.",
    properties = mapOf(
      "type" to stringSchema(
        "TOOL_RESULT / MEMORY_FACT / USER_CONFIRMATION — проверяемые виды доказательства; " +
          "MODEL_ASSESSMENT — слабое, только оценка модели.",
        enum = listOf("TOOL_RESULT", "MEMORY_FACT", "USER_CONFIRMATION", "MODEL_ASSESSMENT"),
      ),
      "config" to openObjectSchema(
        "Что именно проверять (например ключ памяти или имя инструмента). Обязательно для всех " +
          "типов, кроме MODEL_ASSESSMENT.",
      ),
    ),
    required = listOf("type"),
  )

  /** Depends on [scheduleSchema] — declared above. */
  private val stepSchema: JsonObject = objectSchema(
    description = "Один шаг плана.",
    properties = mapOf(
      "id" to stringSchema("Короткий уникальный (в пределах плана) id шага, напр. 'collect_limits'."),
      "title" to stringSchema("Короткое название шага."),
      "instruction" to stringSchema("Что именно нужно сделать на этом шаге."),
      "dependsOn" to stringArraySchema(
        "id шагов ЭТОГО ЖЕ плана, которые должны завершиться раньше этого. Пусто, если шаг ни от чего не зависит.",
      ),
      "expectedTools" to stringArraySchema(
        "Имена инструментов АГЕНТА (см. раздел «Доступные инструменты агента»), которые может " +
          "использовать этот шаг. Пусто, если шаг не вызывает инструменты.",
      ),
      "schedule" to scheduleSchema,
    ),
    required = listOf("id", "title", "instruction"),
  )

  /** Depends on [verifierSchema] — declared above. */
  private val criterionSchema: JsonObject = objectSchema(
    description = "Один критерий готовности всего плана.",
    properties = mapOf(
      "id" to stringSchema("Короткий уникальный (в пределах плана) id критерия."),
      "description" to stringSchema("Человекочитаемое описание — по какому признаку критерий считается закрытым."),
      "required" to booleanSchema("true, если без него весь план не может стать выполненным (COMPLETED)."),
      "verifier" to verifierSchema,
    ),
    required = listOf("id", "description", "required", "verifier"),
  )

  /** Shared by [SUBMIT_PLAN] and [PROPOSE_PLAN_REVISION] — same full plan-content shape, only the name/description differ. Depends on [stepSchema]/[criterionSchema] — both declared above (this is a function, so it would in fact be safe even were that not true, but the two are kept consistent). */
  private fun planContentSchema(name: String, description: String): FunctionSchema = FunctionSchema(
    function = FunctionSchema.FunctionDefinition(
      name = name,
      description = description,
      parameters = objectSchema(
        description = "Полное содержимое версии плана.",
        properties = mapOf(
          "objective" to stringSchema("Цель плана — что должно быть достигнуто."),
          "assumptions" to stringArraySchema("Явные допущения, на которые опирается план. Может быть пустым списком."),
          "constraints" to stringArraySchema(
            "Ограничения и правила безопасности, которые план обязан соблюдать. Обязательные " +
              "ограничения домена будут добавлены автоматически, даже если их здесь нет.",
          ),
          "steps" to arraySchema("Шаги плана.", stepSchema),
          "criteria" to arraySchema("Критерии готовности всего плана.", criterionSchema),
        ),
        required = listOf("objective", "steps", "criteria"),
      ),
    ),
  )

  /** Every schema [PlanPlanner] ever hands to `LlmClient.streamChat` for a planning turn — nothing else. */
  val ALL: List<FunctionSchema> = listOf(
    planContentSchema(
      name = SUBMIT_PLAN,
      description = "Предложить новый план целиком: цель, шаги и критерии готовности. " +
        "Используй для первого черновика или полной переработки плана с нуля.",
    ),
    FunctionSchema(
      function = FunctionSchema.FunctionDefinition(
        name = REQUEST_PLAN_INFORMATION,
        description = "Контекста не хватает, чтобы построить план — задать пользователю ОДИН " +
          "уточняющий вопрос вместо того, чтобы предлагать план.",
        parameters = objectSchema(
          description = "Уточняющий вопрос пользователю.",
          properties = mapOf("question" to stringSchema("Уточняющий вопрос пользователю.")),
          required = listOf("question"),
        ),
      ),
    ),
    planContentSchema(
      name = PROPOSE_PLAN_REVISION,
      description = "Предложить НОВУЮ версию уже существующего плана целиком (не как список " +
        "изменений — разницу посчитает движок). Используй, когда в контексте показана текущая " +
        "версия плана и пользователь просит её поправить.",
    ),
  )

  /** Names of [ALL] — verified by `PlanPlannerTest` to equal `PlanValidator.PLANNER_CONTROL_TOOL_NAMES` exactly. */
  val NAMES: Set<String> = ALL.mapTo(mutableSetOf()) { it.function.name }
}
