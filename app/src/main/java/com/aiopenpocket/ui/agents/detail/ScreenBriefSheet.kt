package com.aiopenpocket.ui.agents.detail

// @trace screen-brief-and-mockup-before-the-forge-builds-82cfb2

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.aiopenpocket.agent.core.forge.ScreenBlock
import com.aiopenpocket.agent.core.forge.ScreenBrief
import com.aiopenpocket.agent.core.forge.ScreenLayout
import com.aiopenpocket.i18n.tr
import java.io.File

/**
 * "Build the screen" asks first: what should be on it (the person's words, pre-filled from what the agent
 * is for), which blocks, which layout - and offers a Muse Image mockup of that before anything is built.
 * The forge used to start at once from the agent's name and the person got a screen they never described.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ScreenBriefSheet(
  initial: ScreenBrief,
  mockup: ForgeMockup,
  onDrawMockup: (ScreenBrief, Boolean) -> Unit,
  onBuild: (ScreenBrief) -> Unit,
  onDismiss: () -> Unit,
) {
  // Saveable like the sheet's own "open" flag: a rotation or a theme switch must not wipe what was written.
  var text by rememberSaveable { mutableStateOf(initial.text) }
  var blocks by rememberSaveable { mutableStateOf(initial.blocks) }
  var layout by rememberSaveable { mutableStateOf(initial.layout) }
  val brief = ScreenBrief(text = text, blocks = blocks, layout = layout)
  // The mockup follows the theme the person is looking at, not the system's.
  val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .navigationBarsPadding()
        .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      Text(
        tr("Какой экран собрать?", "What screen should it build?"),
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
      )
      OutlinedTextField(
        value = text,
        onValueChange = { text = it.take(ScreenBrief.TEXT_MAX) },
        label = { Text(tr("Что должно быть на экране", "What should be on the screen")) },
        minLines = 3,
        maxLines = 6,
        modifier = Modifier.fillMaxWidth().testTag("brief_text"),
      )
      SectionLabel(tr("Блоки", "Blocks"))
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ScreenBlock.entries.forEach { block ->
          FilterChip(
            selected = block in blocks,
            onClick = { blocks = if (block in blocks) blocks - block else blocks + block },
            label = { Text(block.label) },
          )
        }
      }
      SectionLabel(tr("Раскладка", "Layout"))
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ScreenLayout.entries.forEach { option ->
          FilterChip(selected = option == layout, onClick = { layout = option }, label = { Text(option.label) })
        }
      }

      MockupBox(mockup)

      Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
          onClick = { onDrawMockup(brief, dark) },
          enabled = !mockup.drawing && text.isNotBlank(),
          modifier = Modifier.weight(1f).testTag("brief_mockup"),
        ) {
          Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(Modifier.width(6.dp))
          Text(if (mockup.path == null) tr("Макет · 1¢", "Mockup · 1¢") else tr("Перерисовать", "Redraw"))
        }
        Button(
          onClick = { onBuild(brief) },
          enabled = text.isNotBlank() && !mockup.drawing,
          modifier = Modifier.weight(1f).testTag("brief_build"),
        ) {
          Icon(Icons.Default.Build, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(Modifier.width(6.dp))
          Text(if (mockup.path == null) tr("Собрать", "Build") else tr("Собрать так", "Build this"))
        }
      }
      Text(
        tr(
          "Макет — набросок от Muse Image, чтобы увидеть идею до сборки. Кузница собирает по тексту, блокам и раскладке, это пара минут.",
          "The mockup is a Muse Image sketch to see the idea before building. The forge builds from the text, blocks and layout; it takes a couple of minutes.",
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun SectionLabel(text: String) {
  Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The mockup slot: nothing until asked, a spinner while drawing, then the picture (or why there is none). */
@Composable
private fun MockupBox(mockup: ForgeMockup) {
  val shape = RoundedCornerShape(18.dp)
  when {
    mockup.drawing -> Box(
      modifier = Modifier.fillMaxWidth().height(220.dp).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant),
      contentAlignment = Alignment.Center,
    ) {
      Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp)
        Text(
          tr("Muse Image рисует макет…", "Muse Image is drawing the mockup…"),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    mockup.path != null && File(mockup.path).isFile -> AsyncImage(
      model = File(mockup.path),
      contentDescription = tr("Макет экрана", "Screen mockup"),
      contentScale = ContentScale.Fit,
      modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp).clip(shape).testTag("brief_mockup_image"),
    )
    mockup.error != null -> Text(mockup.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    else -> Unit
  }
}
