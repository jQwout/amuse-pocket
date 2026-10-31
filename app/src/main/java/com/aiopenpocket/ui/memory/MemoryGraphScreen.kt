package com.aiopenpocket.ui.memory

// @trace agent-memory-05e545

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.aiopenpocket.i18n.tr
import com.aiopenpocket.ui.components.AcademyCollapsingScaffold
import com.aiopenpocket.ui.components.glass.rememberGlassState
import com.aiopenpocket.ui.theme.MyApplicationTheme
import dev.zacsweers.metrox.viewmodel.metroViewModel

@Composable
fun MemoryGraphScreen(
  onNavigateBack: () -> Unit,
  onOpenChat: (String) -> Unit,
) {
  val viewModel: MemoryGraphViewModel = metroViewModel()
  val state by viewModel.state.collectAsState()
  MemoryGraphContent(state = state, onNavigateBack = onNavigateBack, onOpenChat = onOpenChat)
}

@Composable
private fun MemoryGraphContent(
  state: MemoryGraphState,
  onNavigateBack: () -> Unit,
  onOpenChat: (String) -> Unit,
) {
  // The same glass header on the aurora as Profile, which links here: a plain grey Material bar on a flat
  // black page was the one screen on that path that looked like a different app.
  AcademyCollapsingScaffold(
    title = tr("Граф памяти", "Memory graph"),
    glassState = rememberGlassState(),
    navigationIcon = {
      IconButton(onClick = onNavigateBack) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Назад", "Back"))
      }
    },
  ) { padding ->
    Box(modifier = Modifier.fillMaxSize().padding(padding)) {
      if (state.graph.nodes.isEmpty()) {
        Text(
          tr(
            "Память появится по мере общения: у чатов набегут теги, и тут соберётся граф.",
            "Memory builds up as you chat: your chats collect tags, and the graph forms here.",
          ),
          modifier = Modifier.align(Alignment.Center).padding(32.dp),
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      } else {
        ForceDirectedGraph(
          graph = state.graph,
          chatColor = MaterialTheme.colorScheme.primary,
          tagColor = MaterialTheme.colorScheme.tertiary,
          edgeColor = MaterialTheme.colorScheme.outlineVariant,
          labelColor = MaterialTheme.colorScheme.onSurface,
          modifier = Modifier.fillMaxSize(),
          onNodeClick = { node ->
            if (node.kind == MemoryNodeKind.CHAT) onOpenChat(node.refId)
          },
        )
      }
    }
  }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun MemoryGraphEmptyPreview() {
  MyApplicationTheme {
    MemoryGraphContent(
      state = MemoryGraphState(),
      onNavigateBack = {},
      onOpenChat = {},
    )
  }
}
