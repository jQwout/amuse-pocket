package com.aiopenpocket.agent.workers

// @trace heartbeat-napominalki-agenta-5f0442

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class HeartbeatWorker(
  context: Context,
  params: WorkerParameters,
) : CoroutineWorker(context, params) {

  override suspend fun doWork(): Result = Result.success() // Логика — в Task 5.

  companion object {
    const val KEY_HEARTBEAT_ID = "heartbeat_id"
  }
}
