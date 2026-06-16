package com.aiopenpocket.agent.core.memory.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Many-to-many tag attached to a [WikiPageEntity]. Tags are the primary retrieval axis:
 * the agent pulls relevant pages by tag instead of substring search over flat facts.
 *
 * Cascade-deleted with the owning page.
 */
@Entity(
  tableName = "wiki_page_tags",
  primaryKeys = ["pageId", "tag"],
  foreignKeys = [
    ForeignKey(
      entity = WikiPageEntity::class,
      parentColumns = ["id"],
      childColumns = ["pageId"],
      onDelete = ForeignKey.CASCADE,
    ),
  ],
  indices = [Index("tag"), Index("pageId")],
)
data class WikiPageTagEntity(
  val pageId: String,
  val tag: String,
)
