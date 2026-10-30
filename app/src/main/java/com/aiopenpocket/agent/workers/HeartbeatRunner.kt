package com.aiopenpocket.agent.workers

// @trace heartbeat-napominalki-agenta-5f0442
// @trace user-agent-builder-848153
// @trace structured-plan-engine-907066
// @trace vybor-provaydera-i-zhivoy-katalog-modeley-fe9a53

import android.util.Log
import com.aiopenpocket.agent.core.agent.localizedSystemPrompt
import com.aiopenpocket.agent.core.agent.AgentDefinition
import com.aiopenpocket.agent.core.agent.AgentResolver
import com.aiopenpocket.agent.core.llm.ChatMessage
import com.aiopenpocket.agent.core.llm.ModelKeyGate
import com.aiopenpocket.agent.core.llm.SelectedLlmProvider
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
import com.aiopenpocket.agent.core.prompt.CurrentMoment
import com.aiopenpocket.agent.loop.AgentState
import com.aiopenpocket.agent.loop.ReactLoopRunner
import com.aiopenpocket.agent.loop.TurnContextHolder
import com.aiopenpocket.agent.loop.TurnNotifier
import com.aiopenpocket.agent.loop.persistPlanStepOutputs
import com.aiopenpocket.di.AppScope
import com.aiopenpocket.i18n.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

enum class FireOutcome { DONE, RETRY }

/**
 * The text of a reminder's push, under the «Напоминание»/"Reminder" heading.
 *
 * The heading used to be the reminder's own [HeartbeatEntity.title] and the body its
 * [HeartbeatEntity.message] — and for a one-line reminder the model writes the same sentence into
 * both, so the shade showed "Call mom" / "Call mom". A fixed heading on its own would have thrown the
 * title away, so both are kept where they differ: nothing is said twice, and nothing is lost.
 */
internal fun reminderPushBody(title: String, message: String?): String {
  val heading = title.trim()
  val text = message?.trim().orEmpty()
  return when {
    text.isEmpty() -> heading
    // `contains`, not equality: the message is often the title plus the detail ("Call mom at 7"), and
    // prefixing the title to that would stutter.
    heading.isEmpty() || text.contains(heading, ignoreCase = true) -> text
    else -> "$heading — $text"
  }
}

/**
 * The logic of a heartbeat firing — separate from [HeartbeatWorker] so it can be tested without
 * WorkManager. push is a static push; agent is a headless [ReactLoopRunner] turn with a safe subset of
 * tools. The push and the deep link into the conversation reuse [TurnNotifier] (the same infrastructure
 * as background turns); an agent turn (with a task or a "pulse" without one) stays silent if the agent
 * answered HEARTBEAT_OK.
 *
 * Agent mode bound to a preset/custom agent (Task 22, `user-agent-builder-848153`): it runs only for an
 * already RESOLVED and `[com.aiopenpocket.agent.core.agent.AgentDefinition.enabled]` snapshot — an
 * archived/deleted agent is quietly skipped, instead of failing or running under someone else's name.
 * The tools are the intersection of the agent's `AgentDefinition.toolNames` with [SAFE_TOOL_NAMES]
 * (never the full safe set if the agent has a narrower scope of its own), and the turn itself is
 * wrapped in [TurnContextHolder] so that MemoryTools/FileTools inside it write to THIS agent's
 * namespace/sandbox, not a shared or someone else's one.
 *
 * ## Typed plan-step heartbeats (Task 11 of the Structured Plan Engine)
 *
 * A heartbeat whose [HeartbeatEntity.planTarget] resolves (a [com.aiopenpocket.agent.core.memory.entity.HeartbeatTargetType.PLAN_STEP]
 * row [com.aiopenpocket.agent.core.plan.schedule.PlanScheduler] created) is routed to [firePlanStep]
 * instead of the generic push/agent dispatch below — design spec
 * `2026-07-12-structured-plan-engine-design.md` section 12.1: "HeartbeatWorker → HeartbeatRunner →
 * check the agent and the approved plan version → PlanRunner.resume(planId, stepId) → PlanExecutor →
 * PlanVerifier → progress/evidence → reschedule or terminal notification". This never runs an
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
  // A background turn goes to the same vendor as a normal one: there used to be a literal
  // LlmProvider.NIM here, and after switching vendors the user got reminders that silently failed
  // on an empty NIM key (or went to the vendor they had deliberately left).
  private val selectedProvider: SelectedLlmProvider,
  // No default on purpose: a wakeup without a key must be skipped and recorded, never sent to Meta to
  // come back as an HTTP 401 that the retry loop would then repeat.
  private val modelKeyGate: ModelKeyGate,
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
    // A push with a deep link into the conversation — through the shared TurnNotifier (tap → the same
    // chat). Without POST_NOTIFICATIONS showAgentNotification silently returns false inside; it still
    // counts as fired — the reminder worked, and the missing permission is a settings matter, not a
    // heartbeat failure.
    //
    // The heading says WHAT this is, the body says WHAT to remember — see [reminderPushBody].
    turnNotifier.turnFinished(
      hb.conversationId,
      tr("Напоминание", "Reminder"),
      reminderPushBody(hb.title, hb.message),
      failed = false,
    )
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
    // No Meta key (none is bundled; the person enters it): the wakeup is skipped and the reason is
    // recorded on the run, shown on the scheduled-tasks screen. NOT a push, NOT a retry: a background turn
    // has nobody to ask, and a notification per skipped wakeup would turn a missing key into spam.
    if (!modelKeyGate()) return skip(hb, NO_MODEL_KEY)
    // A periodic wakeup that keeps writing into a chat nobody answers is the runaway case: stop running
    // it until the person writes again (Noodle's rule for its bots). Checked before the model call, so
    // a paused wakeup costs nothing. A one-time wakeup still runs — it is a single thing the person asked
    // for, not a loop.
    if (hb.kind == "periodic" && isUnanswered(hb.conversationId)) {
      Log.i(TAG, "heartbeat ${hb.id} paused: $MAX_UNANSWERED_WAKEUPS messages without a reply")
      record(hb, ok = true, result = PAUSED_UNANSWERED)
      return FireOutcome.DONE
    }
    val systemPrompt = buildString {
      append(agent?.localizedSystemPrompt ?: "Ты — полезный ассистент AI Open Pocket.")
      append("\n\nСейчас ты работаешь В ФОНЕ по плановому heartbeat — юзер тебя не вызывал. ")
      append("Отвечай кратко и по делу: твой ответ уйдёт сообщением в чат и пушем.")
      // The right to stay silent — for ANY background turn, not only a "pulse" without a task: with a task
      // the agent used to be obliged to write something, and "decide yourself whether to bother the
      // user" did not work at all. The note about reminders is there so "remind me to drink water" is
      // not swallowed as "nothing to report".
      append("\nЕсли сообщать нечего — ответь ровно $HEARTBEAT_OK: тогда юзер ничего не получит. ")
      append("Напоминание, о котором тебя просили, — это всегда «есть что сообщить».")
      // A wakeup is permission to do the task it was scheduled for, not to start something else.
      append("\nПробуждение не даёт права на новую работу: делай только задачу этого напоминания ")
      append("и не начинай того, о чём человек не просил.")
      // The same "Now" section as the chat (PrePromptAssembler) and the plan have: without it a background
      // turn does not know what time it is — it can neither convert "at 19:00" in the task nor
      // "today/in an hour" into a time (calendar.start in Unix ms, delays), and "what matters right now"
      // in a pulse is decided blindly.
      append("\n\n")
      append(CurrentMoment.section(System.currentTimeMillis()))
    }
    val task = hb.prompt ?: PULSE_PROMPT
    val messages = mutableListOf(
      ChatMessage(role = "system", content = systemPrompt),
      ChatMessage(role = "user", content = task),
    )
    // Security (Task 22): scope to THIS agent's own declared tools intersected with the background
    // allowlist — never the full SAFE_TOOL_NAMES regardless of the bound agent's own (possibly much
    // narrower) scope. An EMPTY toolNames is an empty scope, not "no restriction": this used to fall
    // through to the whole SAFE_TOOL_NAMES set via `takeIf { it.isNotEmpty() }`, so an agent the user
    // had stripped of every tool still woke up in the background holding the full background
    // allowlist. The interactive path (PrePromptAssembler) already scopes a bound agent even when its
    // set is empty; this is the same rule. Only an UNBOUND heartbeat (agent == null) gets the default.
    val scopedToolNames = agent?.toolNames?.intersect(SAFE_TOOL_NAMES) ?: SAFE_TOOL_NAMES
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
        hb.conversationId, messages, tools, selectedProvider(), BG_MAX_ITERATIONS, agent,
        interactiveApproval = false,
      ) { /* background: nobody to show the stream to */ }
    }

    if (result.state == AgentState.ERROR) {
      Log.w(TAG, "heartbeat ${hb.id} attempt=$attempt error: ${result.errorMessage}")
      if (attempt < MAX_ATTEMPTS - 1) return FireOutcome.RETRY
      record(hb, ok = false, result = result.errorMessage ?: "LLM error")
      return FireOutcome.DONE
    }

    val text = result.text.trim()
    // HEARTBEAT_OK = the agent has nothing to say — complete silence, with or without a task (see the system prompt).
    if (text.contains(HEARTBEAT_OK)) {
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
   *   spec section 12.1: "If the version is no longer approved, the heartbeat does not run the old step");
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
    // Same gate as fireAgent: a plan step is a model call too. Skipped and recorded, never run keyless.
    if (!modelKeyGate()) return skip(hb, NO_MODEL_KEY)

    val result = try {
      planRunner.run(
        PlanRunRequest(
          conversationId = snapshot.plan.conversationId,
          planId = target.planId,
          requestedStepId = target.stepId,
          background = true,
        ),
        provider = selectedProvider(),
      )
    } catch (e: CancellationException) {
      // CancellationException extends IllegalStateException: without this branch stopping the worker would
      // be swallowed below and recorded as "the state changed".
      throw e
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
   * "progress/evidence → reschedule or terminal notification"). Always returns [FireOutcome.DONE] —
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
    // The step's answer goes to the plan's chat on ANY outcome, before the push (tapping the push must
    // open an already written answer). The background path used to write nothing to the chat: after
    // "Approve" the plan ran here, the card said "all steps done", and the result itself (a menu, a
    // selection) was saved nowhere. A write failure (the conversation was deleted mid-step) must not
    // break the record/resync below: without the sync the plan's next step would never be scheduled.
    try {
      persistPlanStepOutputs(result.stepOutputs) { text, card ->
        conversationRepository.addMessage(hb.conversationId, "assistant", text, cardSpec = card)
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "heartbeat ${hb.id} could not save plan step output: ${e.message}")
    }
    when (result.outcome) {
      PlanRunOutcome.COMPLETED -> {
        turnNotifier.turnFinished(hb.conversationId, hb.title, tr("План выполнен.", "The plan is done."), failed = false)
        record(hb, ok = true, result = tr("План завершён.", "Plan completed."))
        planScheduleSync.cancel(target.planId)
      }
      PlanRunOutcome.FAILED -> {
        turnNotifier.turnFinished(
          hb.conversationId, hb.title, result.blockedReason ?: tr("Шаг плана завершился с ошибкой.", "A plan step failed."), failed = true,
        )
        record(hb, ok = false, result = result.blockedReason)
        planScheduleSync.cancel(target.planId)
      }
      PlanRunOutcome.CANCELLED -> {
        record(hb, ok = true, result = result.blockedReason ?: tr("План отменён.", "The plan was cancelled."))
        planScheduleSync.cancel(target.planId)
      }
      PlanRunOutcome.PARTIAL -> {
        val after = planRepository.snapshot(target.planId)
        val stepBlocked = after?.stepStates?.find { it.stepId == target.stepId }?.status == StepStatus.BLOCKED
        if (stepBlocked) {
          // blockedReason is already a full sentence with the step's title ('Step "…" is waiting for your
          // decision…'): a "Plan step needs attention:" prefix would duplicate it.
          turnNotifier.turnFinished(
            hb.conversationId, hb.title, result.blockedReason ?: tr("Шаг плана требует внимания.", "A plan step needs your attention."), failed = false,
          )
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

  /**
   * The last [MAX_UNANSWERED_WAKEUPS] text messages of the chat are all the assistant's: the person has
   * not answered for that long. Counted from the chat itself rather than a counter on the heartbeat, so
   * any message from the person resets it with no extra state; a silent HEARTBEAT_OK writes nothing and
   * so never counts.
   */
  private suspend fun isUnanswered(conversationId: String): Boolean {
    val recent = conversationRepository.getRecentAnnotatableMessages(conversationId, MAX_UNANSWERED_WAKEUPS)
    return recent.size == MAX_UNANSWERED_WAKEUPS && recent.none { it.role == "user" }
  }

  private suspend fun record(hb: HeartbeatEntity, ok: Boolean, result: String?) {
    val status = when {
      !ok -> "error"
      hb.kind == "periodic" -> "scheduled" // a periodic one lives on
      else -> "fired"
    }
    heartbeatDao.recordRun(hb.id, status, System.currentTimeMillis(), result)
  }

  companion object {
    private const val TAG = "HeartbeatRunner"
    const val BG_MAX_ITERATIONS = 5
    const val MAX_ATTEMPTS = 3
    /** After this many assistant messages in a row without a reply, periodic wakeups wait for the person. */
    const val MAX_UNANSWERED_WAKEUPS = 12
    /**
     * Reading + notifications + storage_tool (isolated per agent in code through TurnContextHolder —
     * as safe in the background as memory_tool) + srs_tool (a local write of cards to Room, like
     * memory_tool; without it the "Tutor" background reminders could neither show nor mark a review).
     * Excluded: email/files/alarm/heartbeat itself (self-replication)/render_card.
     *
     * In the background only the READ and WRITE actions of these tools actually work.
     * calendar.delete_event and storage_tool.delete are DESTRUCTIVE: by the
     * [com.aiopenpocket.agent.core.policy.ExecutionPolicy] floor they need a human "Allow", and a
     * background turn (`interactiveApproval = false`) has nobody to ask — ReactLoopRunner refuses right
     * away, deleting nothing. The tools are in the set for their reading and writing (listing events,
     * creating an event, the agent's notes), not for deleting.
     */
    val SAFE_TOOL_NAMES = setOf(
      "memory_tool", "database_query", "calendar", "step_counter", "notifications", "storage_tool", "srs_tool",
    )

    /** The "nothing to report" marker: such an answer of a background agent turn is neither written to the chat nor pushed. */
    const val HEARTBEAT_OK = "HEARTBEAT_OK"
    val PULSE_PROMPT = """
      Это плановый heartbeat. Посмотри доступные данные (память, базу) и реши: есть ли
      что-то полезное или срочное для юзера прямо сейчас? Если есть — коротко сообщи.
      Если нет — ответь ровно: HEARTBEAT_OK
    """.trimIndent()
    // Why a run was skipped: shown as the run result on the scheduled-tasks screen. Getters, not const:
    // tr reads the interface language when the reason is written down.
    private val AGENT_NOT_FOUND: String
      get() = tr("Агент, к которому привязано напоминание, больше не существует.", "The agent this reminder is tied to no longer exists.")
    private val AGENT_NOT_PUBLISHED: String
      get() = tr("Агент архивирован или ещё не опубликован — напоминание пропущено.", "The agent is archived or not published yet — reminder skipped.")
    private val NO_MODEL_KEY: String
      get() = tr(
        "Нет ключа Meta API — запуск пропущен. Добавь ключ в Настройках.",
        "No Meta API key — this run was skipped. Add your key in Settings.",
      )
    private val PAUSED_UNANSWERED: String
      get() = tr(
        "На паузе: в чате $MAX_UNANSWERED_WAKEUPS сообщений без ответа. Продолжу, когда вы напишете.",
        "Paused: $MAX_UNANSWERED_WAKEUPS messages in the chat without a reply. Resumes when you write.",
      )
    private val PLAN_NOT_FOUND: String
      get() = tr("План, на который ссылается heartbeat, больше не существует.", "The plan this heartbeat refers to no longer exists.")
    private val PLAN_NOT_ACTIVE: String
      get() = tr("План сейчас не активен (на паузе/завершён/отменён) — шаг не запущен.", "The plan isn't active right now (paused, completed or cancelled) — step not run.")
    private val PLAN_VERSION_STALE: String
      get() = tr("Одобрена новая версия плана — старый шаг heartbeat'а больше не актуален.", "A new version of the plan was approved — this heartbeat step is out of date.")
    private val PLAN_RUN_ERROR: String
      get() = tr("Не удалось выполнить шаг плана — состояние изменилось во время запуска.", "Couldn't run the plan step — the state changed during the run.")
  }
}
