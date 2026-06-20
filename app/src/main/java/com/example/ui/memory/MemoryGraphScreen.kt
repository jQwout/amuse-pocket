package com.aiopenpocket.ui.memory

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.zacsweers.metrox.viewmodel.metroViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryGraphScreen(
  onNavigateBack: () -> Unit,
  onOpenChat: (String) -> Unit,
) {
  val viewModel: MemoryGraphViewModel = metroViewModel()
  val state by viewModel.state.collectAsState()

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Граф памяти") },
        navigationIcon = {
          IconButton(onClick = onNavigateBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
          }
        },
        colors = TopAppBarDefaults.topAppBarColors(
          containerColor = MaterialTheme.colorScheme.surface,
        ),
      )
    },
  ) { padding ->
    Box(modifier = Modifier.fillMaxSize().padding(padding)) {
      if (state.graph.nodes.isEmpty()) {
        Text(
          "Память появится по мере общения: у чатов набегут теги, и тут соберётся граф.",
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
