package com.aiopenpocket.agent.core.memory.repository

// @trace heartbeat-napominalki-agenta-5f0442
// @trace multi-chat-conversations-b3c93b

import com.aiopenpocket.agent.core.memory.dao.HeartbeatDao
import com.aiopenpocket.agent.core.plan.schedule.HeartbeatScheduler
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Tears down a conversation's background heartbeats when it is deleted. `heartbeats` has NO foreign key
 * to `conversations` (an agent heartbeat can precede/outlive any one plan), so the conversation-delete
 * FK cascade never reaches them — a periodic WorkManager job would otherwise keep firing FOREVER
 * against a conversation that no longer exists (battery drain, endless PLAN_NOT_FOUND skips). Cancels
 * the WorkManager job (via [scheduler]) AND marks the row 'cancelled' so it also drops out of the
 * active list / stops any live chat card.
 */
@Inject
@SingleIn(AppScope::class)
class ConversationHeartbeatCleanup(
  private val heartbeatDao: HeartbeatDao,
  private val scheduler: HeartbeatScheduler,
) {
  suspend fun cancelForConversation(conversationId: String) {
    heartbeatDao.listForConversation(conversationId).forEach { hb ->
      scheduler.cancel(hb.id)
      heartbeatDao.setStatus(hb.id, "cancelled")
    }
  }
}
