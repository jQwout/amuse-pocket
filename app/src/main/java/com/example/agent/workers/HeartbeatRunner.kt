package com.aiopenpocket.agent.workers

// @trace heartbeat-napominalki-agenta-5f0442
// @trace user-agent-builder-848153

import android.util.Log
import com.aiopenpocket.agent.core.agent.AgentResolver
import com.aiopenpocket.agent.core.llm.ChatMessage
import com.aiopenpocket.agent.core.llm.LlmProvider
import com.aiopenpocket.agent.core.mcp.ToolRegistry
import com.aiopenpocket.agent.core.memory.dao.HeartbeatDao
import com.aiopenpocket.agent.core.memory.entity.HeartbeatEntity
import com.aiopenpocket.agent.core.memory.repository.ConversationRepository
import com.aiopenpocket.agent.loop.AgentState
import com.aiopenpocket.agent.loop.ReactLoopRunner
import com.aiopenpocket.agent.loop.TurnContextHolder
import com.aiopenpocket.agent.loop.TurnNotifier
import com.aiopenpocket.di.AppScope
import kotlinx.coroutines.withContext
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

enum class FireOutcome { DONE, RETRY }

/**
 * Логика срабатывания heartbeat'а — отдельно от [HeartbeatWorker], чтобы тестироваться без
 * WorkManager. push — статичный пуш; agent — headless-ход [ReactLoopRunner] с безопасным подмножеством
 * тулз. Пуш и deep-link в диалог переиспользуют [TurnNotifier] (та же инфраструктура, что у фоновых
 * ходов); «пульс» без задачи молчит на HEARTBEAT_OK.
 *
 * Agent-режим, привязанный к preset/custom агенту (Task 22, `user-agent-builder-848153`): бежит
 * только для уже РЕЗОЛВЛЕННОГО и `[com.aiopenpocket.agent.core.agent.AgentDefinition.enabled]`
 * снапшота — архивированный/удалённый агент тихо Skip'ается, а не падает и не исполняется от чужого
 * имени. Инструменты — пересечение `AgentDefinition.toolNames` агента с [SAFE_TOOL_NAMES] (никогда
 * полный safe-набор, если у агента своя более узкая область), а сам ход обёрнут в
 * [TurnContextHolder], чтобы MemoryTools/FileTools внутри хода писали в namespace/песочницу ИМЕННО
 * этого агента, а не в общий/чужой.
 */
@Inject
@SingleIn(AppScope::class)
class HeartbeatRunner(
  private val heartbeatDao: HeartbeatDao,
  private val reactLoopRunner: ReactLoopRunner,
  private val toolRegistry: ToolRegistry,
  private val conversationRepository: ConversationRepository,
  private val turnNotifier: TurnNotifier,
  private val agentResolver: AgentResolver,
  private val turnContextHolder: TurnContextHolder,
) {

  suspend fun handleFire(heartbeatId: String, attempt: Int): FireOutcome {
    val hb = heartbeatDao.getById(heartbeatId) ?: return FireOutcome.DONE
    if (hb.status == "cancelled") return FireOutcome.DONE
    return when (hb.mode) {
      "push" -> firePush(hb)
      "agent" -> fireAgent(hb, attempt)
      else -> FireOutcome.DONE
    }
  }

  private suspend fun firePush(hb: HeartbeatEntity): FireOutcome {
    // Пуш с deep-link в диалог — через общий TurnNotifier (заголовок = имя напоминания, тап → тот же
    // чат). Без POST_NOTIFICATIONS showAgentNotification внутри молча вернёт false; всё равно fired —
    // напоминание отработало, отсутствие разрешения это вопрос настроек, а не сбой heartbeat'а.
    turnNotifier.turnFinished(hb.conversationId, hb.title, hb.message.orEmpty(), failed = false)
    record(hb, ok = true, result = hb.message)
    return FireOutcome.DONE
  }

  private suspend fun fireAgent(hb: HeartbeatEntity, attempt: Int): FireOutcome {
    // Resolved EXACTLY ONCE (Task 22): this same snapshot flows into the system prompt, the tool
    // scope below AND reactLoopRunner.run's own `agent` param — never re-resolved mid-function, so a
    // concurrent edit/publish can't make this one run see two different versions of "the agent".
    // hb.agentId == null (plain, agent-less heartbeat — the only case every pre-Task-22 test uses)
    // resolves to null and always did; the runnability gate below only applies when a specific agent
    // IS bound, so ordinary agent-less heartbeats are completely unaffected.
    val agent = agentResolver.resolve(hb.agentId)
    if (hb.agentId != null) {
      if (agent == null) return skip(hb, AGENT_NOT_FOUND)
      if (!agent.enabled) return skip(hb, AGENT_NOT_PUBLISHED)
    }
    val systemPrompt = buildString {
      append(agent?.systemPrompt ?: "Ты — полезный ассистент AI Open Pocket.")
      append("\n\nСейчас ты работаешь В ФОНЕ по плановому heartbeat — юзер тебя не вызывал. ")
      append("Отвечай кратко и по делу: твой ответ уйдёт сообщением в чат и пушем.")
    }
    val task = hb.prompt ?: PULSE_PROMPT
    val messages = mutableListOf(
      ChatMessage(role = "system", content = systemPrompt),
      ChatMessage(role = "user", content = task),
    )
    // Security (Task 22): scope to THIS agent's own declared tools intersected with the background
    // allowlist — never the full SAFE_TOOL_NAMES regardless of the bound agent's own (possibly much
    // narrower) scope. A null/empty toolNames means "no explicit restriction" (same convention
    // PrePromptAssembler.assemble uses via `agent?.toolNames?.takeIf { it.isNotEmpty() }` for the
    // interactive loop's tool scoping), which here still caps out at SAFE_TOOL_NAMES rather than
    // opening up to every registered tool — there is no "unrestricted" background run.
    val scopedToolNames = agent?.toolNames?.takeIf { it.isNotEmpty() }
      ?.let { it intersect SAFE_TOOL_NAMES }
      ?: SAFE_TOOL_NAMES
    val tools = toolRegistry.getSchemasFor(scopedToolNames)

    // interactiveApproval=false (Task 9): this is a headless WorkManager wakeup with no screen that
    // could ever answer an approval prompt. A hook's REQUIRE_APPROVAL must resolve to an immediate
    // deny here instead of registering with ToolApprovalCoordinator and burning its timeout for no one.
    //
    // withContext(turnContextHolder.contextElement(...)) (Task 22): mirrors AgentLoopOrchestrator's
    // own wrapping of this same call — without it, a tool that reads TurnContextHolder mid-run
    // (MemoryTools' per-agent namespace, FileTools' sandbox root) would see whatever context a PRIOR
    // run happened to leave on this worker's thread instead of THIS heartbeat's own agent/
    // conversation, leaking one agent's memory/files into another's background run.
    val result = withContext(turnContextHolder.contextElement(hb.conversationId, agent)) {
      reactLoopRunner.run(
        hb.conversationId, messages, tools, LlmProvider.NIM, BG_MAX_ITERATIONS, agent,
        interactiveApproval = false,
      ) { /* фон: стриминг некому показывать */ }
    }

    if (result.state == AgentState.ERROR) {
      Log.w(TAG, "heartbeat ${hb.id} attempt=$attempt error: ${result.errorMessage}")
      if (attempt < MAX_ATTEMPTS - 1) return FireOutcome.RETRY
      record(hb, ok = false, result = result.errorMessage ?: "LLM error")
      return FireOutcome.DONE
    }

    val text = result.text.trim()
    // «Пульс» (без явной задачи): HEARTBEAT_OK = агенту нечего сказать — полная тишина.
    if (hb.prompt == null && text.contains("HEARTBEAT_OK")) {
      record(hb, ok = true, result = null)
      return FireOutcome.DONE
    }

    conversationRepository.addMessage(hb.conversationId, "assistant", text)
    turnNotifier.turnFinished(hb.conversationId, hb.title, text.take(140), failed = false)
    record(hb, ok = true, result = text.take(200))
    return FireOutcome.DONE
  }

  /**
   * A bound agent isn't runnable right now (deleted since scheduling, or archived/never-published —
   * Task 22). Recorded exactly like an LLM error ([record]'s `ok = false` → status "error", the only
   * "something's wrong" status [HeartbeatEntity.status] has), but returned as DONE rather than RETRY:
   * this is a permanent condition for this fire, not a transient one — retrying within the same
   * attempt budget cannot un-archive the agent. [reason] lands in [HeartbeatEntity.lastResult], which
   * the reminder card shows verbatim, so it stays a short, user-facing sentence, not a stack trace.
   */
  private suspend fun skip(hb: HeartbeatEntity, reason: String): FireOutcome {
    Log.w(TAG, "heartbeat ${hb.id} skipped: $reason")
    record(hb, ok = false, result = reason)
    return FireOutcome.DONE
  }

  private suspend fun record(hb: HeartbeatEntity, ok: Boolean, result: String?) {
    val status = when {
      !ok -> "error"
      hb.kind == "periodic" -> "scheduled" // периодика живёт дальше
      else -> "fired"
    }
    heartbeatDao.recordRun(hb.id, status, System.currentTimeMillis(), result)
  }

  companion object {
    private const val TAG = "HeartbeatRunner"
    const val BG_MAX_ITERATIONS = 5
    const val MAX_ATTEMPTS = 3
    /** Чтение + уведомления. Исключены: sms/email/файлы/alarm/сам heartbeat (самовоспроизводство)/render_card. */
    val SAFE_TOOL_NAMES = setOf("memory_tool", "database_query", "calendar", "step_counter", "notifications")
    val PULSE_PROMPT = """
      Это плановый heartbeat. Посмотри доступные данные (память, базу) и реши: есть ли
      что-то полезное или срочное для юзера прямо сейчас? Если есть — коротко сообщи.
      Если нет — ответь ровно: HEARTBEAT_OK
    """.trimIndent()
    private const val AGENT_NOT_FOUND = "Агент, к которому привязано напоминание, больше не существует."
    private const val AGENT_NOT_PUBLISHED = "Агент архивирован или ещё не опубликован — напоминание пропущено."
  }
}
