package com.aiopenpocket.agent.core.srs

// @trace yazykovoy-agent-repetitor-s-intervalnymi-povtoreniyami-4a9360

/** A vocabulary card as the tool/repository see it (storage id/timestamps live in the entity). */
data class SrsWord(
  val lang: String,
  val term: String,
  val translation: String,
  val example: String = "",
  val stage: Int = 0,
  val dueMs: Long = 0L,
)
