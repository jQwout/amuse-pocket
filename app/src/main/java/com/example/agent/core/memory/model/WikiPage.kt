package com.aiopenpocket.agent.core.memory.model

/** A wiki memory page with its tags resolved. */
data class WikiPage(
  val id: String,
  val slug: String,
  val title: String,
  val content: String,
  val tags: List<String>,
  val createdAt: Long,
  val updatedAt: Long,
)

enum class LinkDirection { OUTGOING, INCOMING }

/** A page reachable from another page via one [WikiLink], with the edge's direction and label. */
data class WikiNeighbor(
  val pageId: String,
  val slug: String,
  val title: String,
  val relation: String,
  val direction: LinkDirection,
)

/** A page plus its immediate graph neighborhood (one hop, both directions). */
data class WikiPageView(
  val page: WikiPage,
  val neighbors: List<WikiNeighbor>,
)
