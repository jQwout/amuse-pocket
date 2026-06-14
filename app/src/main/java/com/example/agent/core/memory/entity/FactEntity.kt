package com.aiopenpocket.agent.core.memory.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
  tableName = "facts",
  indices = [Index("key"), Index("category")],
)
data class FactEntity(
  @PrimaryKey val id: String,
  val key: String,
  val value: String,
  val category: String,
  val createdAt: Long = System.currentTimeMillis(),
  val updatedAt: Long = System.currentTimeMillis(),
)
