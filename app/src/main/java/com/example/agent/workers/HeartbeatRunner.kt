package com.aiopenpocket.agent.workers

// @trace heartbeat-napominalki-agenta-5f0442

import android.util.Log
import com.aiopenpocket.agent.core.agent.MiniAgentCatalog
import com.aiopenpocket.agent.core.llm.ChatMessage
import com.aiopenpocket.agent.core.llm.LlmProvider
import com.aiopenpocket.agent.core.mcp.ToolRegistry
import com.aiopenpocket.agent.core.memory.dao.HeartbeatDao
import com.aiopenpocket.agent.core.memory.entity.HeartbeatEntity
import com.aiopenpocket.agent.core.memory.repository.ConversationRepository
import com.aiopenpocket.agent.loop.AgentState
import com.aiopenpocket.agent.loop.ReactLoopRunner
import com.aiopenpocket.agent.loop.TurnNotifier
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

enum class FireOutcome { DONE, RETRY }

/**
 * Логика срабатывания heartbeat'а — отдельно от [HeartbeatWorker], чтобы тестироваться без
 * WorkManager. push — статичный пуш; agent — headless-ход [ReactLoopRunner] с безопасным подмножеством
 * тулз. Пуш и deep-link в диалог переиспользуют [TurnNotifier] (та же инфраструктура, что у фоновых
 * ходов); «пульс» без задачи молчит на HEARTBEAT_OK.
 */
@Inject
@SingleIn(AppScope::class)
class HeartbeatRunner(
  private val heartbeatDao: HeartbeatDao,
  private val reactLoopRunner: ReactLoopRunner,
  private val toolRegistry: ToolRegistry,
  private val conversationRepository: ConversationRepository,
  private val turnNotifier: TurnNotifier,
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
    val agent = MiniAgentCatalog.get(hb.agentId)
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
    val tools = toolRegistry.getSchemasFor(SAFE_TOOL_NAMES)

    val result = reactLoopRunner.run(
      hb.conversationId, messages, tools, LlmProvider.NIM, BG_MAX_ITERATIONS, hb.agentId,
    ) { /* фон: стриминг некому показывать */ }

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
  }
}
