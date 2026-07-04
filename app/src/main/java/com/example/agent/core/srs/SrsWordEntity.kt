package com.aiopenpocket.agent.core.srs

// @trace yazykovoy-agent-repetitor-s-intervalnymi-povtoreniyami-4a9360

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A spaced-repetition vocabulary card. [id] is "<lang>:<term-normalized>" so a word is unique per
 * language and re-adding it upserts (OnConflictStrategy.REPLACE on the primary key).
 */
@Entity(tableName = "srs_words", indices = [Index("lang")])
data class SrsWordEntity(
  @PrimaryKey val id: String,
  val lang: String,
  val term: String,
  val translation: String,
  val example: String,
  val stage: Int,
  val dueMs: Long,
  val createdAt: Long,
  val updatedAt: Long,
)
