package com.aiopenpocket.ui.memory

/** Node kind in the bipartite memory graph: a conversation or a tag hub. */
enum class MemoryNodeKind { CHAT, TAG }

/**
 * One node of the memory graph. [refId] is the conversation id for [MemoryNodeKind.CHAT] nodes and
 * the raw tag for [MemoryNodeKind.TAG] nodes; [degree] drives the hub size of tag nodes.
 */
data class MemoryNode(
  val id: String,
  val label: String,
  val kind: MemoryNodeKind,
  val refId: String,
  val degree: Int = 0,
)

/** An undirected "conversation has tag" membership edge. */
data class MemoryEdge(
  val fromId: String,
  val toId: String,
)

data class MemoryGraph(
  val nodes: List<MemoryNode> = emptyList(),
  val edges: List<MemoryEdge> = emptyList(),
)

data class MemoryGraphState(
  val graph: MemoryGraph = MemoryGraph(),
  val isLoading: Boolean = true,
)
