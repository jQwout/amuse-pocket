package com.aiopenpocket.agent.loop

import com.aiopenpocket.agent.core.llm.ChatMessage
import com.aiopenpocket.agent.core.llm.FunctionSchema
import com.aiopenpocket.agent.core.llm.LlmClient
import com.aiopenpocket.agent.core.llm.LlmProvider
import com.aiopenpocket.agent.core.llm.StreamEvent
import com.aiopenpocket.agent.core.mcp.ToolRegistry
import com.aiopenpocket.agent.core.prompt.PrePromptAssembler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import com.aiopenpocket.di.AppScope

/**
 * Plan-and-Execute orchestrator — a teaching demo of multi-agent flow built ON TOP of the single
 * [ReactLoopRunner], not as a separate engine. It runs three role phases over one conversation:
 *
 *  1. **Planner** — one LLM call that returns a structured [Plan] via the `submit_plan` tool.
 *  2. **Executor** — each step runs the shared ReAct loop (full tool set) with a focused instruction.
 *  3. **Synthesizer** — one LLM call that composes the final answer from the step results.
 *
 * Everything streams as plain [AgentEvent.TextDelta] so the existing chat UI shows the whole process
 * live; the accumulated transcript is returned as the [AgentResult.text] so it persists as one
 * assistant message. No new card type, no live-updating widget.
 */
@SingleIn(AppScope::class)
class PlanExecuteOrchestrator @Inject constructor(
  private val llmClient: LlmClient,
  private val registry: ToolRegistry,
  private val prePromptAssembler: PrePromptAssembler,
  private val reactLoopRunner: ReactLoopRunner,
) {

  fun execute(
    conversationId: String,
    goal: String,
    provider: LlmProvider = LlmProvider.NIM,
  ): Flow<AgentEvent> = channelFlow {
    // Каждый TextDelta зеркалим в transcript, чтобы итоговое сообщение в БД совпадало с тем, что
    // пользователь видел в стриме (план + шаги + синтез), а не только с финальным ответом.
    val transcript = StringBuilder()
    val tap: suspend (AgentEvent) -> Unit = { ev ->
      if (ev is AgentEvent.TextDelta) transcript.append(ev.content)
      send(ev)
    }

    try {
      val context = prePromptAssembler.assemble(conversationId, goal)
      android.util.Log.d(TAG, "▶ plan-execute conv=$conversationId goal=\"${goal.take(60)}\"")

      // 1. PLANNER
      tap(AgentEvent.StateChanged(AgentState.STREAMING))
      val plan = runPlanner(goal, provider)
      android.util.Log.d(TAG, "plan: ${plan.steps.size} step(s) [${plan.steps.joinToString { it.title }}]")
      tap(AgentEvent.TextDelta(renderPlanHeader(plan)))

      // 2. EXECUTOR — по шагам, через общий ReAct-цикл.
      val toolTrace = mutableListOf<ToolTrace>()
      val results = mutableListOf<String>()
      for (step in plan.steps) {
        android.util.Log.d(TAG, "step ${step.id}/${plan.steps.size}: ${step.title}")
        tap(AgentEvent.TextDelta("\n\n**Шаг ${step.id}: ${step.title}**\n"))
        val stepResult = runStep(conversationId, context.systemPrompt, goal, plan, results, step, provider) { tap(it) }
        results += stepResult.text
        toolTrace += stepResult.toolTrace
        if (stepResult.state == AgentState.ERROR) {
          tap(AgentEvent.TextDelta("\n⚠️ Шаг прерван: ${stepResult.errorMessage ?: "ошибка"}\n"))
        }
      }

      // 3. SYNTHESIZER — финальный связный ответ. Идёт через общий цикл с полным набором тулов,
      // чтобы при необходимости закрыть ответ карточкой (напр. lesson_complete в уроке план-мода).
      android.util.Log.d(TAG, "synthesize")
      tap(AgentEvent.TextDelta("\n\n---\n\n"))
      val synth = runSynthesizer(conversationId, context.systemPrompt, goal, plan, results, provider) { tap(it) }
      toolTrace += synth.toolTrace
      android.util.Log.d(TAG, "✓ plan-execute done steps=${plan.steps.size}")

      send(AgentEvent.StateChanged(AgentState.FINISHED))
      send(
        AgentEvent.Completed(
          AgentResult(
            text = transcript.toString(),
            toolTrace = toolTrace,
            state = AgentState.FINISHED,
            iterations = plan.steps.size,
          ),
        ),
      )
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      android.util.Log.e(TAG, "Plan-execute failed", e)
      send(AgentEvent.StateChanged(AgentState.ERROR))
      send(AgentEvent.Error(e.message ?: "Plan-execute failed"))
    }
  }.buffer(Channel.UNLIMITED).flowOn(Dispatchers.IO)

  /** Planner phase: force-structured output via the `submit_plan` tool, with a text-parse fallback. */
  private suspend fun runPlanner(goal: String, provider: LlmProvider): Plan {
    val messages = mutableListOf(
      ChatMessage(role = "system", content = PLANNER_SYSTEM),
      ChatMessage(role = "user", content = goal),
    )
    var plan: Plan? = null
    var text = ""
    llmClient.streamChat(messages, listOf(SUBMIT_PLAN), provider).collect { chunk ->
      when (chunk) {
        is StreamEvent.ToolCall -> if (chunk.name == "submit_plan") plan = parsePlan(chunk.arguments)
        is StreamEvent.TextDelta -> text += chunk.content
        else -> {}
      }
    }
    return plan
      ?: parsePlanFromText(text)
      ?: Plan(listOf(PlanStep(id = 1, title = goal)))
  }

  /** Executor phase: one focused run of the shared ReAct loop with the full tool set. */
  private suspend fun runStep(
    conversationId: String,
    systemPrompt: String,
    goal: String,
    plan: Plan,
    priorResults: List<String>,
    step: PlanStep,
    provider: LlmProvider,
    emit: suspend (AgentEvent) -> Unit,
  ): AgentResult {
    val instruction = buildString {
      appendLine("Общая задача пользователя: $goal")
      appendLine()
      appendLine("Полный план:")
      plan.steps.forEach { appendLine("${it.id}. ${it.title}") }
      appendLine()
      if (priorResults.isNotEmpty()) {
        appendLine("Результаты предыдущих шагов:")
        priorResults.forEachIndexed { i, r -> appendLine("Шаг ${i + 1}: $r") }
        appendLine()
      }
      appendLine("Сейчас выполни ТОЛЬКО шаг ${step.id}: ${step.title}")
      step.detail?.let { appendLine(it) }
      appendLine("Используй инструменты при необходимости. Верни краткий результат шага.")
    }
    val messages = mutableListOf(
      ChatMessage(role = "system", content = systemPrompt),
      ChatMessage(role = "user", content = instruction),
    )
    return reactLoopRunner.run(conversationId, messages, registry.getAllSchemas(), provider, emit)
  }

  /** Synthesizer phase: compose the user-facing answer from the step results, via the shared loop. */
  private suspend fun runSynthesizer(
    conversationId: String,
    systemPrompt: String,
    goal: String,
    plan: Plan,
    results: List<String>,
    provider: LlmProvider,
    emit: suspend (AgentEvent) -> Unit,
  ): AgentResult {
    val instruction = buildString {
      appendLine("Задача пользователя: $goal")
      appendLine()
      appendLine("Результаты шагов:")
      plan.steps.forEachIndexed { i, s ->
        appendLine("Шаг ${i + 1} (${s.title}): ${results.getOrNull(i).orEmpty()}")
      }
      appendLine()
      appendLine("Дай пользователю финальный связный ответ на его языке. Не пересказывай внутреннюю кухню шагов, если это не нужно.")
    }
    val messages = mutableListOf(
      ChatMessage(role = "system", content = systemPrompt),
      ChatMessage(role = "user", content = instruction),
    )
    return reactLoopRunner.run(conversationId, messages, registry.getAllSchemas(), provider, emit)
  }

  private fun renderPlanHeader(plan: Plan): String = buildString {
    append("\n📋 **План:**\n")
    plan.steps.forEach { append("${it.id}. ${it.title}\n") }
  }

  private fun parsePlan(args: Map<String, JsonElement>): Plan? {
    val stepsEl = args["steps"] as? JsonArray ?: return null
    val steps = stepsEl.mapIndexedNotNull { index, el ->
      val obj = el as? JsonObject ?: return@mapIndexedNotNull null
      val title = (obj["title"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        ?: return@mapIndexedNotNull null
      val detail = (obj["detail"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
      PlanStep(id = index + 1, title = title, detail = detail)
    }
    return if (steps.isEmpty()) null else Plan(steps)
  }

  /** Fallback when the model answers in prose: pick numbered / bulleted lines as steps. */
  private fun parsePlanFromText(text: String): Plan? {
    val steps = text.lineSequence()
      .map { it.trim() }
      .filter { it.isNotEmpty() }
      .mapNotNull { line -> LINE_PREFIX.find(line)?.let { line.removeRange(it.range).trim() } }
      .filter { it.isNotBlank() }
      .toList()
    if (steps.isEmpty()) return null
    return Plan(steps.mapIndexed { i, title -> PlanStep(id = i + 1, title = title) })
  }

  companion object {
    private const val TAG = "PlanExec"

    private val LINE_PREFIX = Regex("""^\s*(\d+[.)]|[-*•])\s+""")

    private const val PLANNER_SYSTEM =
      "Ты — планировщик. Разбей задачу пользователя на 2–5 конкретных последовательных шагов. " +
        "Вызови инструмент submit_plan со списком шагов. Сами шаги НЕ выполняй. " +
        "Названия шагов — короткие, на языке пользователя."

    private val SUBMIT_PLAN = FunctionSchema(
      function = FunctionSchema.FunctionDefinition(
        name = "submit_plan",
        description = "Сохрани план выполнения задачи: 2–5 конкретных последовательных шагов.",
        parameters = JsonObject(
          mapOf(
            "type" to JsonPrimitive("object"),
            "properties" to JsonObject(
              mapOf(
                "steps" to JsonObject(
                  mapOf(
                    "type" to JsonPrimitive("array"),
                    "description" to JsonPrimitive("Упорядоченный список шагов плана."),
                    "items" to JsonObject(
                      mapOf(
                        "type" to JsonPrimitive("object"),
                        "properties" to JsonObject(
                          mapOf(
                            "title" to JsonObject(
                              mapOf(
                                "type" to JsonPrimitive("string"),
                                "description" to JsonPrimitive("Краткое название шага на языке пользователя."),
                              ),
                            ),
                            "detail" to JsonObject(
                              mapOf(
                                "type" to JsonPrimitive("string"),
                                "description" to JsonPrimitive("Что именно нужно сделать на этом шаге."),
                              ),
                            ),
                          ),
                        ),
                        "required" to JsonArray(listOf(JsonPrimitive("title"))),
                      ),
                    ),
                  ),
                ),
              ),
            ),
            "required" to JsonArray(listOf(JsonPrimitive("steps"))),
          ),
        ),
      ),
    )
  }
}
