package com.aiopenpocket.ui.chat.components

// @trace mcp-tools-f77d9b

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.aiopenpocket.agent.core.mcp.ToolResult
import com.aiopenpocket.agent.loop.ToolTrace
import com.aiopenpocket.ui.theme.MyApplicationTheme

@Composable
fun ToolTracePanel(
  traces: List<ToolTrace>,
  modifier: Modifier = Modifier,
) {
  Card(
    modifier = modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.secondaryContainer,
    ),
  ) {
    Column(modifier = Modifier.padding(12.dp)) {
      Text(
        text = "Tool Trace",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
      )
      traces.forEach { trace ->
        val resultText = when (trace.result) {
          is ToolResult.Success -> "OK"
          is ToolResult.Error -> "ERR"
        }
        Text(
          text = "[${trace.iteration}] ${trace.toolName} → $resultText",
          style = MaterialTheme.typography.bodySmall,
          modifier = Modifier.padding(top = 4.dp),
        )
      }
    }
  }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun ToolTracePanelPreview() {
  MyApplicationTheme {
    ToolTracePanel(
      traces = listOf(
        ToolTrace(1, "search_memory", emptyMap(), ToolResult.Success("3 matches")),
        ToolTrace(2, "write_file", emptyMap(), ToolResult.Error("permission denied")),
      ),
    )
  }
}
