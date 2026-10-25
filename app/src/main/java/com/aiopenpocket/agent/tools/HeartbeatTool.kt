package com.aiopenpocket.agent.tools

// @trace heartbeat-napominalki-agenta-5f0442
// @trace user-agent-builder-848153

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.aiopenpocket.agent.core.mcp.RiskClass
import com.aiopenpocket.agent.core.mcp.Tool
import com.aiopenpocket.agent.core.mcp.ToolResult
import com.aiopenpocket.agent.core.mcp.long
import com.aiopenpocket.agent.core.mcp.missingArg
import com.aiopenpocket.agent.core.mcp.string
import com.aiopenpocket.agent.core.mcp.toolSchema
import com.aiopenpocket.agent.core.memory.dao.HeartbeatDao
import com.aiopenpocket.agent.core.memory.entity.HeartbeatEntity
import com.aiopenpocket.agent.core.plan.schedule.HeartbeatScheduler
import com.aiopenpocket.agent.loop.TurnContextHolder
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.json.JsonElement
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Напоминалки и heartbeat агента. mode=push — статичный пуш в назначенное время;
 * mode=agent — фоновое пробуждение агента (с задачей из prompt или дефолтным «пульсом»,
 * где агент сам решает, писать ли юзеру — HEARTBEAT_OK = молчать). Источник правды — Room
 * (heartbeats); фактическое планирование/отмена WorkManager-задачи вынесены в
 * [HeartbeatScheduler] (Task 11 of the Structured Plan Engine) — this class only ever builds/persists
 * the [HeartbeatEntity] and asks [HeartbeatScheduler] to arm/cancel it, never touches `WorkManager`
 * directly itself anymore.
 *
 * This class remains the LLM-facing GENERIC scheduler only — it never accepts a `planId`/
 * `planVersion`/`stepId` argument from the model (design spec `2026-07-12-structured-plan-engine-design.md`
 * section 12.1: "LLM не передаёт произвольные planId/version/stepId напрямую в HeartbeatTool"). A
 * typed [com.aiopenpocket.agent.core.memory.entity.HeartbeatTargetType.PLAN_STEP] heartbeat is only
 * ever created by [com.aiopenpocket.agent.core.plan.schedule.PlanScheduler], which calls
 * [HeartbeatScheduler] directly and never goes through this tool's `execute`.
 *
 * `schedule` привязывает heartbeat к текущему [turnContextHolder]'у агенту (`agentId`) и сразу же
 * отклоняет его (Task 22, `user-agent-builder-848153`), если этот агент архивирован/ещё не
 * опубликован — иначе он бы просто молча Skip'ался при каждом срабатывании в
 * [com.aiopenpocket.agent.workers.HeartbeatRunner].
 */
@Inject
@SingleIn(AppScope::class)
class HeartbeatTool(
  private val context: Context,
  private val heartbeatDao: HeartbeatDao,
  private val turnContextHolder: TurnContextHolder,
  private val heartbeatScheduler: HeartbeatScheduler,
) : Tool {

  // Per-call risk (Task 9): list only reads Room; schedule creates a new WorkManager job; cancel
  // is the irreversible one (the pending fire, and its underlying data, is gone).
  override fun riskFor(arguments: Map<String, JsonElement>): RiskClass = when (arguments.string("action")) {
    "schedule" -> RiskClass.WRITE
    "cancel" -> RiskClass.DESTRUCTIVE
    else -> RiskClass.READ
  }

  override val name = "heartbeat"
  override val description =
    "НАПОМИНАНИЯ (уведомлением, без звонка) и heartbeat. mode=push — отложенный или периодический " +
    "пуш; mode=agent — фоновое пробуждение агента. ЭТО инструмент по умолчанию для «напомни», " +
    "«напиши мне», «не забудь»: системный будильник (alarm) бери только когда человек попросил " +
    "именно будильник. Время задаётся ОТНОСИТЕЛЬНО: delay_minutes от текущего момента — он есть " +
    "в секции «Сейчас» системного промпта, посчитай разницу сам. Срабатывание неточное " +
    "(WorkManager экономит батарею), возможна задержка — для минута-в-минуту нужен будильник. " +
    "После успешного schedule СРАЗУ вызови render_card с template=\"reminder\" и " +
    "params={\"heartbeat_id\":\"<id из ответа>\"} — юзер увидит живую карточку с кнопкой отмены."
  override val schema = toolSchema(name, description) {
    string(
      "action", "Действие", required = true,
      enum = listOf("schedule", "cancel", "list"),
    )
    string("mode", "push — просто уведомление; agent — разбудить агента", enum = listOf("push", "agent"))
    string("title", "Короткий заголовок (обязателен для schedule)")
    string("message", "Текст пуша (mode=push)")
    string(
      "prompt",
      "Задача для агента (mode=agent). Не задан — «пульс»: агент сам решит, есть ли что сообщить.",
    )
    integer("delay_minutes", "Одноразово: через сколько минут сработать")
    integer("interval_minutes", "Периодически: интервал в минутах (минимум 15 — лимит WorkManager)")
    string("heartbeat_id", "ID для cancel")
  }

  override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
    return when (arguments.string("action") ?: return missingArg("action")) {
      "schedule" -> schedule(arguments)
      "cancel" -> cancel(arguments)
      "list" -> list()
      else -> ToolResult.Error("Unknown action")
    }
  }

  private suspend fun schedule(args: Map<String, JsonElement>): ToolResult {
    if (!hasNotificationPermission()) {
      return ToolResult.Error(
        "Нет разрешения на уведомления. Покажи карточку render_card " +
          "template=\"notification_permission\" и повтори schedule после выдачи разрешения.",
      )
    }
    val title = args.string("title") ?: return missingArg("title")
    val mode = args.string("mode") ?: "push"
    val delay = args.long("delay_minutes")
    val interval = args.long("interval_minutes")
    if (delay == null && interval == null) {
      return ToolResult.Error("Нужен delay_minutes (одноразово) или interval_minutes (периодически).")
    }
    if (interval != null && interval < 15) {
      return ToolResult.Error("Минимальный интервал периодической задачи — 15 минут (лимит WorkManager).")
    }
    val conversationId = turnContextHolder.conversationId
      ?: return ToolResult.Error("Нет активного диалога для heartbeat.", recoverable = false)

    // Task 22: reject at CREATION time, not just when it eventually fires (HeartbeatRunner.fireAgent
    // would otherwise just silently Skip it forever). turnContextHolder.agent is the SAME snapshot
    // PrePromptAssembler already resolved for THIS turn (Task 5) — re-checking its own .enabled is
    // enough; there is no separate "unknown agent" case to handle here, since agentId below is always
    // sourced FROM this already-resolved snapshot (turnContextHolder.agentId == agent?.id), never from
    // an arbitrary caller-supplied value that could fail to resolve at all.
    val boundAgent = turnContextHolder.agent
    if (boundAgent != null && !boundAgent.enabled) {
      return ToolResult.Error(
        "Агент «${boundAgent.name}» архивирован или ещё не опубликован — привязать к нему heartbeat нельзя.",
        recoverable = false,
      )
    }

    val id = UUID.randomUUID().toString()
    val entity = HeartbeatEntity(
      id = id,
      title = title,
      mode = mode,
      prompt = args.string("prompt"),
      message = args.string("message"),
      agentId = turnContextHolder.agentId,
      conversationId = conversationId,
      kind = if (interval != null) "periodic" else "one_time",
      fireAt = delay?.let { System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(it) },
      intervalMinutes = interval,
    )
    heartbeatDao.insert(entity)
    heartbeatScheduler.schedule(entity)

    val schedule = if (interval != null) "каждые $interval мин" else "через $delay мин"
    return ToolResult.Success(
      "Запланировано ($schedule). heartbeat_id=$id. " +
        "Теперь вызови render_card template=\"reminder\" params={\"heartbeat_id\":\"$id\"}.",
    )
  }

  /**
   * Отмена — только своего: агент из ростера или конструктора не трогает чужие напоминания, а шаги
   * плана (planId) ведёт план-движок — их отменяют в карточке плана, иначе план ждал бы шага,
   * которого больше нет. Обычный чат (агента нет) — это сам пользователь, ему доступны все его
   * напоминания, кроме плановых.
   */
  private suspend fun cancel(args: Map<String, JsonElement>): ToolResult {
    val id = args.string("heartbeat_id") ?: return missingArg("heartbeat_id")
    val hb = heartbeatDao.getById(id) ?: return ToolResult.Error("Напоминания $id нет.", recoverable = true)
    if (hb.planId != null) {
      return ToolResult.Error("Это шаг плана — его отменяют в карточке плана.", recoverable = false)
    }
    val agentId = turnContextHolder.agentId
    if (agentId != null && hb.agentId != agentId) {
      return ToolResult.Error("Это напоминание другого агента.", recoverable = false)
    }
    heartbeatScheduler.cancel(id)
    heartbeatDao.setStatus(id, "cancelled")
    return ToolResult.Success("Отменено: $id")
  }

  private suspend fun list(): ToolResult {
    val agentId = turnContextHolder.agentId
    val active = heartbeatDao.listActive()
      .filter { it.planId == null && (agentId == null || it.agentId == agentId) }
    if (active.isEmpty()) return ToolResult.Success("Активных напоминаний нет.")
    val lines = active.joinToString("\n") { hb ->
      val schedule = hb.intervalMinutes?.let { "каждые $it мин" } ?: "одноразово"
      "- ${hb.title} [${hb.mode}, $schedule] id=${hb.id}"
    }
    return ToolResult.Success(lines)
  }

  private fun hasNotificationPermission(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
      ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
      PackageManager.PERMISSION_GRANTED

  companion object {
    fun workName(heartbeatId: String) = "heartbeat_$heartbeatId"
  }
}
