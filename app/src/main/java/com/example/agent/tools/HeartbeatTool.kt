package com.aiopenpocket.agent.tools

// @trace heartbeat-napominalki-agenta-5f0442
// @trace user-agent-builder-848153

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.aiopenpocket.agent.core.mcp.RiskClass
import com.aiopenpocket.agent.core.mcp.Tool
import com.aiopenpocket.agent.core.mcp.ToolResult
import com.aiopenpocket.agent.core.mcp.long
import com.aiopenpocket.agent.core.mcp.missingArg
import com.aiopenpocket.agent.core.mcp.string
import com.aiopenpocket.agent.core.mcp.toolSchema
import com.aiopenpocket.agent.core.memory.dao.HeartbeatDao
import com.aiopenpocket.agent.core.memory.entity.HeartbeatEntity
import com.aiopenpocket.agent.loop.TurnContextHolder
import com.aiopenpocket.agent.workers.HeartbeatWorker
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
 * (heartbeats), WorkManager получает только id.
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
    "Напоминалки и heartbeat: отложенный/периодический пуш (mode=push) или фоновое " +
    "пробуждение агента (mode=agent) через WorkManager. После успешного schedule СРАЗУ вызови " +
    "render_card с template=\"reminder\" и params={\"heartbeat_id\":\"<id из ответа>\"} — " +
    "юзер увидит живую карточку с кнопкой отмены."
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

    val input = Data.Builder().putString(HeartbeatWorker.KEY_HEARTBEAT_ID, id).build()
    val workManager = WorkManager.getInstance(context)
    val workName = workName(id)
    if (interval != null) {
      // Без initial delay WorkManager запускает первый прогон периодики почти сразу — «каждые 60 мин»
      // сработало бы мгновенно при планировании. Первый запуск: через delay (если задан) либо через
      // полный интервал, дальше — с шагом interval.
      val firstRunDelay = delay ?: interval
      val request = PeriodicWorkRequestBuilder<HeartbeatWorker>(interval, TimeUnit.MINUTES)
        .setInitialDelay(firstRunDelay, TimeUnit.MINUTES)
        .setInputData(input)
        .build()
      workManager.enqueueUniquePeriodicWork(workName, ExistingPeriodicWorkPolicy.UPDATE, request)
    } else {
      val request = OneTimeWorkRequestBuilder<HeartbeatWorker>()
        .setInitialDelay(delay ?: 0L, TimeUnit.MINUTES)
        .setInputData(input)
        .build()
      workManager.enqueueUniqueWork(workName, ExistingWorkPolicy.REPLACE, request)
    }

    val schedule = if (interval != null) "каждые $interval мин" else "через $delay мин"
    return ToolResult.Success(
      "Запланировано ($schedule). heartbeat_id=$id. " +
        "Теперь вызови render_card template=\"reminder\" params={\"heartbeat_id\":\"$id\"}.",
    )
  }

  private suspend fun cancel(args: Map<String, JsonElement>): ToolResult {
    val id = args.string("heartbeat_id") ?: return missingArg("heartbeat_id")
    WorkManager.getInstance(context).cancelUniqueWork(workName(id))
    heartbeatDao.setStatus(id, "cancelled")
    return ToolResult.Success("Отменено: $id")
  }

  private suspend fun list(): ToolResult {
    val active = heartbeatDao.listActive()
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
