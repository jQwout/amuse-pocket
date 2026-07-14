package com.aiopenpocket.agent.workers

// @trace heartbeat-napominalki-agenta-5f0442
// @trace user-agent-builder-848153
// @trace structured-plan-engine-907066

import android.util.Log
import com.aiopenpocket.agent.core.agent.AgentDefinition
import com.aiopenpocket.agent.core.agent.AgentResolver
import com.aiopenpocket.agent.core.llm.ChatMessage
import com.aiopenpocket.agent.core.llm.LlmProvider
import com.aiopenpocket.agent.core.mcp.ToolRegistry
import com.aiopenpocket.agent.core.memory.dao.HeartbeatDao
import com.aiopenpocket.agent.core.memory.entity.HeartbeatEntity
import com.aiopenpocket.agent.core.memory.repository.ConversationRepository
import com.aiopenpocket.agent.core.plan.execution.PlanRunRequest
import com.aiopenpocket.agent.core.plan.execution.PlanRunResult
import com.aiopenpocket.agent.core.plan.execution.PlanRunner
import com.aiopenpocket.agent.core.plan.execution.PlanScheduleSync
import com.aiopenpocket.agent.core.plan.model.PlanRunOutcome
import com.aiopenpocket.agent.core.plan.model.PlanStatus
import com.aiopenpocket.agent.core.plan.model.StepStatus
import com.aiopenpocket.agent.core.plan.persistence.PlanRepository
import com.aiopenpocket.agent.core.plan.schedule.PlanHeartbeatTarget
import com.aiopenpocket.agent.core.plan.schedule.planTarget
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
 *
 * ## Typed plan-step heartbeats (Task 11 of the Structured Plan Engine)
 *
 * A heartbeat whose [HeartbeatEntity.planTarget] resolves (a [com.aiopenpocket.agent.core.memory.entity.HeartbeatTargetType.PLAN_STEP]
 * row [com.aiopenpocket.agent.core.plan.schedule.PlanScheduler] created) is routed to [firePlanStep]
 * instead of the generic push/agent dispatch below — design spec
 * `2026-07-12-structured-plan-engine-design.md` section 12.1: "HeartbeatWorker → HeartbeatRunner →
 * проверить agent и approved plan version → PlanRunner.resume(planId, stepId) → PlanExecutor →
 * PlanVerifier → progress/evidence → reschedule или terminal notification". This never runs an
 * infinite foreground loop — one fire advances [planRunner] by at most one step (the default
 * `PlanRunBudget.maxStepsPerRun = 1`) and always finishes with [FireOutcome.DONE]; the NEXT due step
 * (if any) is a freshly re-armed heartbeat from [planScheduleSync], never a WorkManager retry of THIS
 * same job. A plain (non-plan) push/agent heartbeat's behavior is completely unchanged.
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
  private val planRepository: PlanRepository,
  private val planRunner: PlanRunner,
  private val planScheduleSync: PlanScheduleSync,
) {

  suspend fun handleFire(heartbeatId: String, attempt: Int): FireOutcome {
    val hb = heartbeatDao.getById(heartbeatId) ?: return FireOutcome.DONE
    if (hb.status == "cancelled") return FireOutcome.DONE
    val target = hb.planTarget
    return if (target != null) {
      firePlanStep(hb, target, attempt)
    } else {
      when (hb.mode) {
        "push" -> firePush(hb)
        "agent" -> fireAgent(hb, attempt)
        else -> FireOutcome.DONE
      }
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
    val agent = when (val guard = resolveGuardedAgent(hb.agentId)) {
      is GuardedAgent.Denied -> return skip(hb, guard.reason)
      is GuardedAgent.Allowed -> guard.agent
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
   * Fires a typed [com.aiopenpocket.agent.core.memory.entity.HeartbeatTargetType.PLAN_STEP] heartbeat
   * (design spec section 12.1's `firePlanStep` pseudocode): validates the plan/agent are still
   * runnable, resumes exactly [target]'s step through [planRunner] with `background = true` (never
   * waits on UI approval — `PlanExecutor` already defaults `interactiveApproval` to false for a
   * background run), then hands the outcome to [handlePlanRunResult] to record/notify/reschedule.
   * Never returns [FireOutcome.RETRY] — see [handlePlanRunResult]'s own kdoc for why.
   *
   * Pre-flight checks are this method's own responsibility, NOT duplicated inside [planRunner]/
   * `PlanExecutor` (which do not themselves reject a stale/disabled binding):
   * - plan exists ([PLAN_NOT_FOUND]);
   * - plan is [PlanStatus.ACTIVE] ([PLAN_NOT_ACTIVE]) — paused/awaiting-approval/terminal plans are
   *   never resumed by a stray heartbeat (`PlanScheduler.sync` should already have cancelled this
   *   row when the plan left ACTIVE, but a WorkManager job already in flight when that cancel ran is
   *   a real race this check defends against);
   * - the plan's CURRENT approved version still matches [target]'s ([PLAN_VERSION_STALE] — design
   *   spec section 12.1: "Если версия больше не approved, heartbeat не запускает старый шаг");
   * - the bound agent (if any) still exists and is enabled — reuses [resolveGuardedAgent], the EXACT
   *   same Task 22 guard [fireAgent] uses, rather than a second copy of that check.
   *
   * "No concurrent execution lease" (the pre-flight list's remaining item) is deliberately NOT
   * re-checked here — [planRunner]'s own per-plan `Mutex.tryLock` already refuses a concurrent run
   * immediately (`PlanRunOutcome.PARTIAL`, "already executing"), and [handlePlanRunResult] treats
   * that exactly like any other PARTIAL outcome (harmless re-sync).
   */
  private suspend fun firePlanStep(hb: HeartbeatEntity, target: PlanHeartbeatTarget, attempt: Int): FireOutcome {
    val snapshot = planRepository.snapshot(target.planId) ?: return skip(hb, PLAN_NOT_FOUND)
    if (snapshot.plan.status != PlanStatus.ACTIVE) return skip(hb, PLAN_NOT_ACTIVE)
    if (snapshot.plan.approvedVersion != target.planVersion) return skip(hb, PLAN_VERSION_STALE)
    when (val guard = resolveGuardedAgent(snapshot.plan.agentId)) {
      is GuardedAgent.Denied -> return skip(hb, guard.reason)
      is GuardedAgent.Allowed -> Unit // PlanRunner resolves the agent itself again internally; nothing more to thread through here.
    }

    val result = try {
      planRunner.run(
        PlanRunRequest(
          conversationId = snapshot.plan.conversationId,
          planId = target.planId,
          requestedStepId = target.stepId,
          background = true,
        ),
        provider = LlmProvider.NIM,
      )
    } catch (e: IllegalStateException) {
      Log.w(TAG, "heartbeat ${hb.id} plan step '${target.stepId}' attempt=$attempt refused: ${e.message}")
      return skip(hb, PLAN_RUN_ERROR)
    } catch (e: IllegalArgumentException) {
      Log.w(TAG, "heartbeat ${hb.id} plan step '${target.stepId}' attempt=$attempt refused: ${e.message}")
      return skip(hb, PLAN_RUN_ERROR)
    }

    return handlePlanRunResult(hb, target, result)
  }

  /**
   * Records/notifies/reschedules after [firePlanStep] resumes one step (design spec section 12.1:
   * "progress/evidence → reschedule или terminal notification"). Always returns [FireOutcome.DONE] —
   * NEVER [FireOutcome.RETRY] — for every [PlanRunOutcome]: `PlanExecutor`/`PlanRunBudget` already own
   * the per-step retry budget, so a WorkManager-level retry here would be a second, conflicting retry
   * mechanism layered on top of that one. The next attempt (if the plan stays ACTIVE) is a freshly
   * re-armed heartbeat from [planScheduleSync], never this same job retrying itself — matching this
   * task's own constraint: "one wake runs the due step(s) within budget... then re-schedules the next
   * due step", not "retries the current one".
   *
   * [PlanRunOutcome.FAILED] cancels [planScheduleSync] rather than re-arming it: a single failed
   * attempt (retry budget not yet exhausted) and a genuinely exhausted retry budget are NOT
   * distinguished here (`PlanRunner`/`PlanExecutor` return the identical outcome for both — see the
   * Task 11 report's own note on this), and blindly re-syncing on EVERY failure risks an unbounded
   * background heartbeat loop for a step that can never succeed. A human can always resume progress
   * manually (design spec: "a step sitting in FAILED can still be retried back to READY manually") —
   * [turnNotifier] tells them a plan step needs attention rather than failing silently forever.
   */
  private suspend fun handlePlanRunResult(hb: HeartbeatEntity, target: PlanHeartbeatTarget, result: PlanRunResult): FireOutcome {
    when (result.outcome) {
      PlanRunOutcome.COMPLETED -> {
        turnNotifier.turnFinished(hb.conversationId, hb.title, "План выполнен.", failed = false)
        record(hb, ok = true, result = "План завершён.")
        planScheduleSync.cancel(target.planId)
      }
      PlanRunOutcome.FAILED -> {
        turnNotifier.turnFinished(hb.conversationId, hb.title, result.blockedReason ?: "Шаг плана завершился с ошибкой.", failed = true)
        record(hb, ok = false, result = result.blockedReason)
        planScheduleSync.cancel(target.planId)
      }
      PlanRunOutcome.CANCELLED -> {
        record(hb, ok = true, result = result.blockedReason ?: "План отменён.")
        planScheduleSync.cancel(target.planId)
      }
      PlanRunOutcome.PARTIAL -> {
        val after = planRepository.snapshot(target.planId)
        val stepBlocked = after?.stepStates?.find { it.stepId == target.stepId }?.status == StepStatus.BLOCKED
        if (stepBlocked) {
          turnNotifier.turnFinished(hb.conversationId, hb.title, "Шаг плана требует внимания: ${result.blockedReason ?: hb.title}", failed = false)
        }
        record(hb, ok = true, result = result.blockedReason)
        if (after != null && after.plan.status == PlanStatus.ACTIVE) {
          planScheduleSync.sync(target.planId)
        } else {
          planScheduleSync.cancel(target.planId)
        }
      }
    }
    return FireOutcome.DONE
  }

  /**
   * Task 22's "an explicitly-bound agent must exist and be enabled" guard, factored out of
   * [fireAgent] so [firePlanStep] can reuse the IDENTICAL check and skip-reasons instead of a second,
   * possibly-diverging copy — [com.aiopenpocket.agent.core.plan.execution.PlanRunner] does not itself
   * reject a resolved-null/disabled agent (it just hands whatever `agentResolver.resolve` returns
   * straight to `PlanExecutor`), so this class must still perform the check for a plan-step fire,
   * exactly as it always has for a generic agent-mode one. [agentId] null ("no explicit agent bound")
   * always passes.
   */
  private suspend fun resolveGuardedAgent(agentId: String?): GuardedAgent {
    val agent = agentResolver.resolve(agentId)
    if (agentId != null) {
      if (agent == null) return GuardedAgent.Denied(AGENT_NOT_FOUND)
      if (!agent.enabled) return GuardedAgent.Denied(AGENT_NOT_PUBLISHED)
    }
    return GuardedAgent.Allowed(agent)
  }

  private sealed class GuardedAgent {
    data class Allowed(val agent: AgentDefinition?) : GuardedAgent()
    data class Denied(val reason: String) : GuardedAgent()
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
    private const val PLAN_NOT_FOUND = "План, на который ссылается heartbeat, больше не существует."
    private const val PLAN_NOT_ACTIVE = "План сейчас не активен (на паузе/завершён/отменён) — шаг не запущен."
    private const val PLAN_VERSION_STALE = "Одобрена новая версия плана — старый шаг heartbeat'а больше не актуален."
    private const val PLAN_RUN_ERROR = "Не удалось выполнить шаг плана — состояние изменилось во время запуска."
  }
}
