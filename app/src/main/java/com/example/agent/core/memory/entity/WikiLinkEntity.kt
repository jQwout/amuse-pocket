package com.aiopenpocket.agent.core.memory.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Directed association between two [WikiPageEntity] rows — the "wiki cross-link".
 *
 * [relation] labels the edge (e.g. "related", "part_of", "caused_by"); the default is a plain
 * "related" association. Traversal queries read both directions, so the graph is effectively
 * navigable either way. Both endpoints cascade-delete with their page.
 */
@Entity(
  tableName = "wiki_links",
  primaryKeys = ["fromPageId", "toPageId", "relation"],
  foreignKeys = [
    ForeignKey(
      entity = WikiPageEntity::class,
      parentColumns = ["id"],
      childColumns = ["fromPageId"],
      onDelete = ForeignKey.CASCADE,
    ),
    ForeignKey(
      entity = WikiPageEntity::class,
      parentColumns = ["id"],
      childColumns = ["toPageId"],
      onDelete = ForeignKey.CASCADE,
    ),
  ],
  indices = [Index("fromPageId"), Index("toPageId")],
)
data class WikiLinkEntity(
  val fromPageId: String,
  val toPageId: String,
  val relation: String,
  val createdAt: Long = System.currentTimeMillis(),
)
