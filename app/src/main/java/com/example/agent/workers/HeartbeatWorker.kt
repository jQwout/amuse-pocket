package com.aiopenpocket.agent.workers

// @trace heartbeat-napominalki-agenta-5f0442

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.aiopenpocket.AiOpenPocketApp

class HeartbeatWorker(
  context: Context,
  params: WorkerParameters,
) : CoroutineWorker(context, params) {

  override suspend fun doWork(): Result {
    val heartbeatId = inputData.getString(KEY_HEARTBEAT_ID) ?: return Result.failure()
    // Metro-граф достаём через Application: дефолтная WorkerFactory остаётся,
    // NotificationWorker продолжает работать как раньше.
    val graph = (applicationContext as AiOpenPocketApp).appGraph
    return when (graph.heartbeatRunner.handleFire(heartbeatId, runAttemptCount)) {
      FireOutcome.RETRY -> Result.retry()
      FireOutcome.DONE -> Result.success()
    }
  }

  companion object {
    const val KEY_HEARTBEAT_ID = "heartbeat_id"
  }
}
