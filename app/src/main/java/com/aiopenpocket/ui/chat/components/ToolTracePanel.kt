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
        text = "Вызовы инструментов",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
      )
      traces.forEach { trace ->
        val resultText = when (trace.result) {
          is ToolResult.Success -> "ок"
          is ToolResult.Error -> "ошибка"
        }
        // В trace.toolName лежит имя тула (memory_tool), действие — в аргументе action; на экран идёт
        // общее человеческое имя вызова, как в баре подтверждения («Память: найти факты»).
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
        // Реальная форма вызова: имя тула + action в аргументах (не «search_memory» как имя функции).
        ToolTrace(1, "memory_tool", mapOf("action" to JsonPrimitive("search_memory")), ToolResult.Success("3 matches")),
        ToolTrace(2, "file_tool", mapOf("action" to JsonPrimitive("write_file")), ToolResult.Error("permission denied")),
      ),
    )
  }
}
