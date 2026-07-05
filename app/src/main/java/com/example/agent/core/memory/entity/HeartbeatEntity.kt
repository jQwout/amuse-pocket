package com.aiopenpocket.agent.core.memory.entity

// @trace heartbeat-napominalki-agenta-5f0442

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Реестр напоминалок/heartbeat'ов — источник правды для карточки в чате и для
 * [com.aiopenpocket.agent.workers.HeartbeatWorker]. В WorkManager уходит только id
 * (обходим 10KB-лимит input Data); unique work name = "heartbeat_<id>".
 */
@Entity(tableName = "heartbeats")
data class HeartbeatEntity(
  @PrimaryKey val id: String,
  val title: String,
  /** "push" — статичный пуш без LLM; "agent" — фоновое пробуждение агента. */
  val mode: String,
  /** Задача для agent-режима; null = дефолтный «пульс» (агент сам решает, писать ли). */
  val prompt: String? = null,
  /** Текст пуша для push-режима. */
  val message: String? = null,
  /** Мини-агент, от чьего имени просыпаемся; null = дефолтный ассистент. */
  val agentId: String? = null,
  /** Диалог, куда пишется результат agent-режима (и который открывает тап по пушу). */
  val conversationId: String,
  /** "one_time" | "periodic". */
  val kind: String,
  /** Epoch millis планового срабатывания (one_time). */
  val fireAt: Long? = null,
  val intervalMinutes: Long? = null,
  /** "scheduled" | "fired" | "cancelled" | "error". Periodic после срабатывания остаётся scheduled. */
  val status: String = "scheduled",
  val createdAt: Long = System.currentTimeMillis(),
  val lastRunAt: Long? = null,
  /** Короткая выжимка последнего результата — для карточки. */
  val lastResult: String? = null,
)
