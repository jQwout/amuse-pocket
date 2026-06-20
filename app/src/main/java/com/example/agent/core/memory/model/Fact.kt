package com.aiopenpocket.agent.core.memory.model

data class Fact(
  val id: String,
  val key: String,
  val value: String,
  val category: String,
  val createdAt: Long,
  val updatedAt: Long,
)
