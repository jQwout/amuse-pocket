package com.aiopenpocket.agent.core.memory.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single wiki-style memory page: a stable, evolving note about one topic.
 *
 * Replaces the flat key/value [FactEntity] + rolling conversation summary model. A page is
 * identified by a human-readable [slug] (stable handle the agent links by) and accumulates
 * free-form [content]. Tags ([WikiPageTagEntity]) and links ([WikiLinkEntity]) turn the flat
 * store into a small knowledge graph.
 */
@Entity(
  tableName = "wiki_pages",
  indices = [Index("slug", unique = true), Index("updatedAt")],
)
data class WikiPageEntity(
  @PrimaryKey val id: String,
  val slug: String,
  val title: String,
  val content: String,
  val createdAt: Long = System.currentTimeMillis(),
  val updatedAt: Long = System.currentTimeMillis(),
)
