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
import com.aiopenpocket.agent.core.mcp.CapabilityNames
import com.aiopenpocket.agent.core.mcp.ToolResult
import com.aiopenpocket.agent.loop.ToolTrace
import com.aiopenpocket.i18n.tr
import com.aiopenpocket.ui.theme.MyApplicationTheme
import kotlinx.serialization.json.JsonPrimitive

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
        text = tr("Вызовы инструментов", "Tool calls"),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
      )
      traces.forEach { trace ->
        val resultText = when (val result = trace.result) {
          is ToolResult.Success -> if (result.simulated) tr("имитация", "simulated") else tr("ок", "ok")
          is ToolResult.Error -> tr("ошибка", "error")
        }
        // trace.toolName holds the tool name (memory_tool), and the action is in the action argument; the screen
        // gets the shared human name of the call, as in the confirmation bar ("Memory: find facts").
        Text(
          text = "[${trace.iteration}] ${CapabilityNames.call(trace.toolName, trace.arguments)} → $resultText",
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
        // The real shape of the call: the tool name + action in the arguments (not "search_memory" as a function name).
        ToolTrace(1, "memory_tool", mapOf("action" to JsonPrimitive("search_memory")), ToolResult.Success("3 matches")),
        ToolTrace(2, "file_tool", mapOf("action" to JsonPrimitive("write_file")), ToolResult.Error("permission denied")),
      ),
    )
  }
}
