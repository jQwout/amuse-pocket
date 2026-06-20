package com.aiopenpocket.ui.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiopenpocket.agent.core.memory.model.Conversation
import com.aiopenpocket.agent.core.memory.repository.ConversationRepository
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Derives the bipartite memory graph (chats + tags) on the fly from [conversation_tags]. We never
 * persist a graph — relatedness is just tag overlap, so the graph is recomputed from the same flow
 * the chat list uses.
 */
@Inject
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey(MemoryGraphViewModel::class)
class MemoryGraphViewModel(
  private val conversationRepository: ConversationRepository,
) : ViewModel() {

  private val _state = MutableStateFlow(MemoryGraphState())
  val state: StateFlow<MemoryGraphState> = _state.asStateFlow()

  init {
    viewModelScope.launch {
      conversationRepository.getAllConversations().collect { conversations ->
        _state.update { it.copy(graph = buildGraph(conversations), isLoading = false) }
      }
    }
  }

  private fun buildGraph(conversations: List<Conversation>): MemoryGraph {
    val edges = mutableListOf<MemoryEdge>()
    val chatNodes = mutableListOf<MemoryNode>()
    val tagDegree = LinkedHashMap<String, Int>()

    conversations.forEach { conversation ->
      val chatNodeId = "chat:${conversation.id}"
      chatNodes += MemoryNode(
        id = chatNodeId,
        label = conversation.title,
        kind = MemoryNodeKind.CHAT,
        refId = conversation.id,
      )
      conversation.tags.distinct().forEach { tag ->
        val tagNodeId = "tag:$tag"
        edges += MemoryEdge(chatNodeId, tagNodeId)
        tagDegree[tag] = (tagDegree[tag] ?: 0) + 1
      }
    }

    val tagNodes = tagDegree.map { (tag, degree) ->
      MemoryNode(
        id = "tag:$tag",
        label = "#$tag",
        kind = MemoryNodeKind.TAG,
        refId = tag,
        degree = degree,
      )
    }

    return MemoryGraph(nodes = chatNodes + tagNodes, edges = edges)
  }
}
