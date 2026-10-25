package com.aiopenpocket.agent.core.plan.schedule

// @trace structured-plan-engine-907066
// @trace heartbeat-napominalki-agenta-5f0442

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.aiopenpocket.agent.core.memory.entity.HeartbeatEntity
import com.aiopenpocket.agent.tools.HeartbeatTool
import com.aiopenpocket.agent.workers.HeartbeatWorker
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import java.util.concurrent.TimeUnit

/**
 * The WorkManager mechanics a [HeartbeatEntity] needs to actually fire — extracted (Task 11 of the
 * Structured Plan Engine, plan's Task 11 Step 2) from what used to be inline code inside
 * [com.aiopenpocket.agent.tools.HeartbeatTool.schedule]/`cancel`, so that:
 * - [com.aiopenpocket.agent.tools.HeartbeatTool] (the LLM-facing generic scheduler) and
 *   [com.aiopenpocket.agent.core.plan.schedule.PlanScheduler] (the plan-driven scheduler, Task 11
 *   Step 3) share ONE real implementation instead of each building `OneTimeWorkRequest`/
 *   `PeriodicWorkRequest`s their own way;
 * - a test can fake this interface with a plain in-memory double instead of touching a real
 *   `WorkManager` instance — mirrors [com.aiopenpocket.agent.core.plan.execution.PlanScheduleSync]/
 *   [com.aiopenpocket.agent.core.plan.execution.PlanStepRunner]'s own reasoning for a seam interface.
 *
 * [PlanScheduler] calls this service directly, never through [com.aiopenpocket.agent.tools.HeartbeatTool]'s
 * `Tool.execute` — design spec `2026-07-12-structured-plan-engine-design.md` section 12.1: "PlanScheduler
 * calls the same service directly and does not invoke a tool through the model."
 */
interface HeartbeatScheduler {
  /** (Re)arms WorkManager for [entity] — a one-time or periodic job depending on [HeartbeatEntity.kind],
   * keyed by [HeartbeatTool.workName] of [HeartbeatEntity.id] so a second call with the SAME id replaces
   * (never duplicates) the previously-armed job. Does not touch Room — the caller persists [entity] itself. */
  suspend fun schedule(entity: HeartbeatEntity)

  /** Cancels whatever WorkManager job is currently armed for [heartbeatId], if any. Idempotent — cancelling
   * an id with no armed job (already fired, never scheduled, already cancelled) is a silent no-op, same as
   * `WorkManager.cancelUniqueWork` itself. Does not touch Room — the caller updates [HeartbeatEntity.status] itself. */
  suspend fun cancel(heartbeatId: String)
}

/**
 * Production [HeartbeatScheduler]: byte-for-byte the same WorkManager request-building
 * [com.aiopenpocket.agent.tools.HeartbeatTool.schedule]/`cancel` did inline before this task, just
 * driven off an already-built [HeartbeatEntity] instead of raw tool-call arguments. [HeartbeatEntity.fireAt],
 * when set, is an ABSOLUTE epoch millis target — the initial WorkManager delay is derived from it relative
 * to "now" at schedule-time (never negative — [maxOf] floors a slightly-late call to zero delay rather than
 * a negative one, which `setInitialDelay` would reject).
 *
 * Задержка — в МИЛЛИСЕКУНДАХ, не в минутах: раньше `(fireAt - now) / 60_000` после вставки в Room
 * округлялось вниз, и «через 2 минуты» срабатывало через минуту. Шагу плана это било вдвойне: ранний
 * запуск видел «ещё не пора» (`PlanRunner.isDue`), пересинхронизация ставила задержку 0 — и воркер
 * крутился вхолостую до самого срока. WorkManager раньше заданной задержки не запускает, так что
 * миллисекунды убирают ранний запуск целиком.
 */
@Inject
@SingleIn(AppScope::class)
class WorkManagerHeartbeatScheduler(private val context: Context) : HeartbeatScheduler {

  override suspend fun schedule(entity: HeartbeatEntity) {
    val workManager = WorkManager.getInstance(context)
    val workName = HeartbeatTool.workName(entity.id)
    val input = Data.Builder().putString(HeartbeatWorker.KEY_HEARTBEAT_ID, entity.id).build()
    val now = System.currentTimeMillis()
    if (entity.kind == "periodic") {
      val interval = requireNotNull(entity.intervalMinutes) {
        "Periodic heartbeat '${entity.id}' has no intervalMinutes."
      }
      // Без initial delay WorkManager запускает первый прогон периодики почти сразу — see the
      // identical original comment this replaces in HeartbeatTool.schedule. entity.fireAt (if set)
      // names the first occurrence explicitly; otherwise the first fire is one full interval away.
      val firstRunDelayMs = entity.fireAt?.let { maxOf(0L, it - now) } ?: TimeUnit.MINUTES.toMillis(interval)
      val request = PeriodicWorkRequestBuilder<HeartbeatWorker>(interval, TimeUnit.MINUTES)
        .setInitialDelay(firstRunDelayMs, TimeUnit.MILLISECONDS)
        .setInputData(input)
        .build()
      workManager.enqueueUniquePeriodicWork(workName, ExistingPeriodicWorkPolicy.UPDATE, request)
    } else {
      val delayMs = entity.fireAt?.let { maxOf(0L, it - now) } ?: 0L
      val request = OneTimeWorkRequestBuilder<HeartbeatWorker>()
        .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
        .setInputData(input)
        .build()
      workManager.enqueueUniqueWork(workName, ExistingWorkPolicy.REPLACE, request)
    }
  }

  override suspend fun cancel(heartbeatId: String) {
    WorkManager.getInstance(context).cancelUniqueWork(HeartbeatTool.workName(heartbeatId))
  }
}

/** Production binding: [HeartbeatScheduler] is just [WorkManagerHeartbeatScheduler], nothing more. */
@ContributesTo(AppScope::class)
@BindingContainer
object HeartbeatSchedulerBindings {

  @Provides
  @SingleIn(AppScope::class)
  fun provideHeartbeatScheduler(impl: WorkManagerHeartbeatScheduler): HeartbeatScheduler = impl
}
