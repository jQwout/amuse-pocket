package com.aiopenpocket.ui.roadmap

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.WavingHand
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.aiopenpocket.ui.components.AcademyBottomNav
import com.aiopenpocket.ui.components.AcademyHeader
import com.aiopenpocket.ui.components.AcademyTab
import com.aiopenpocket.ui.theme.CardDark
import com.aiopenpocket.ui.theme.MyApplicationTheme
import com.aiopenpocket.ui.theme.PurpleAccent
import com.aiopenpocket.ui.theme.PurpleAccentDark
import dev.zacsweers.metrox.viewmodel.metroViewModel

data class RoadmapLevel(
  val id: String,
  val title: String,
  val description: String,
  val icon: ImageVector,
)

val ROADMAP_LEVELS = listOf(
  RoadmapLevel(
    "l1_welcome",
    "Анатомия чата",
    "Сообщение, препромпт, стриминг и путь данных от тебя к LLM и обратно.",
    Icons.Default.WavingHand,
  ),
  RoadmapLevel(
    "l2_notes",
    "Быстрые заметки",
    "Техники захвата контекста в реальном времени и передачи агенту.",
    Icons.Default.EditNote,
  ),
  RoadmapLevel(
    "l3_reminders",
    "Напоминания",
    "Временная логика и автоматические триггеры для выполнения задач.",
    Icons.Default.Notifications,
  ),
  RoadmapLevel(
    "l4_memory",
    "Память и навыки",
    "Долгая память, эпизоды и возможности агента — базовое представление о системе.",
    Icons.Default.Psychology,
  ),
)

enum class LevelStatus { LOCKED, CURRENT, COMPLETED }

@Composable
fun RoadmapScreen(
  onOpenLessonChat: (String) -> Unit,
  onReopenOnboarding: () -> Unit,
  onNavigateToChats: () -> Unit,
  onNavigateToLibrary: () -> Unit,
  onNavigateToSettings: () -> Unit,
) {
  val viewModel = metroViewModel<RoadmapViewModel>()
  val progress by viewModel.roadmapProgress.collectAsState()
  RoadmapContent(
    levels = ROADMAP_LEVELS,
    progress = progress,
    onNavigateToChats = onNavigateToChats,
    onNavigateToLibrary = onNavigateToLibrary,
    onNavigateToSettings = onNavigateToSettings,
    onLevelClick = { level ->
      if (level.id == "l1_welcome") onReopenOnboarding()
      else viewModel.openLesson(level.id, onOpenLessonChat)
    },
  )
}

@Composable
private fun RoadmapContent(
  levels: List<RoadmapLevel>,
  progress: Set<String>,
  onNavigateToChats: () -> Unit,
  onNavigateToLibrary: () -> Unit,
  onNavigateToSettings: () -> Unit,
  onLevelClick: (RoadmapLevel) -> Unit,
) {
  val progressFraction = progress.size.toFloat() / levels.size

  Scaffold(
    bottomBar = {
      AcademyBottomNav(
        selectedTab = AcademyTab.Path,
        onTabSelected = { tab ->
          when (tab) {
            AcademyTab.Chats -> onNavigateToChats()
            AcademyTab.Path -> Unit
            AcademyTab.Library -> onNavigateToLibrary()
          }
        },
      )
    },
  ) { padding ->
    LazyColumn(
      modifier = Modifier
        .fillMaxSize()
        .padding(padding)
        .padding(horizontal = 20.dp),
      contentPadding = PaddingValues(bottom = 24.dp),
    ) {
      item {
        Spacer(modifier = Modifier.height(16.dp))
        AcademyHeader(onNavigateToSettings = onNavigateToSettings)
      }
      item {
        Spacer(modifier = Modifier.height(24.dp))
        SectionTitle("Путь агента", "Освой искусство оркестрации AI через пошаговую программу обучения.")
        Spacer(modifier = Modifier.height(28.dp))
      }
      itemsIndexed(levels) { index, level ->
        val status = when {
          level.id in progress -> LevelStatus.COMPLETED
          index == 0 || levels.getOrNull(index - 1)?.id in progress -> LevelStatus.CURRENT
          else -> LevelStatus.LOCKED
        }
        PathTimelineItem(
          level = level,
          status = status,
          isFirst = index == 0,
          isLast = index == levels.lastIndex,
          onClick = { if (status != LevelStatus.LOCKED) onLevelClick(level) },
        )
      }
      item {
        Spacer(modifier = Modifier.height(20.dp))
        ProTipCard(progressFraction = progressFraction)
      }
    }
  }
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
  Text(
    text = title,
    style = MaterialTheme.typography.headlineLarge,
    color = MaterialTheme.colorScheme.onSurface,
  )
  Spacer(modifier = Modifier.height(8.dp))
  Text(
    text = subtitle,
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
  )
}

@Composable
private fun PathTimelineItem(
  level: RoadmapLevel,
  status: LevelStatus,
  isFirst: Boolean,
  isLast: Boolean,
  onClick: () -> Unit,
) {
  val isLocked = status == LevelStatus.LOCKED
  val lineColor = when (status) {
    LevelStatus.COMPLETED -> PurpleAccent.copy(alpha = 0.6f)
    LevelStatus.CURRENT -> PurpleAccent.copy(alpha = 0.4f)
    LevelStatus.LOCKED -> MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
  }

  Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier = Modifier.width(48.dp).fillMaxHeight(),
    ) {
      if (!isFirst) {
        Box(modifier = Modifier.width(2.dp).height(16.dp).background(lineColor))
      } else {
        Spacer(modifier = Modifier.height(16.dp))
      }
      TimelineNode(level = level, status = status)
      if (!isLast) {
        Box(modifier = Modifier.width(2.dp).weight(1f).background(lineColor))
      }
    }
    Spacer(modifier = Modifier.width(12.dp))
    PathLevelCard(
      level = level,
      status = status,
      isLocked = isLocked,
      onClick = onClick,
      modifier = Modifier.weight(1f).padding(bottom = if (isLast) 0.dp else 16.dp),
    )
  }
}

@Composable
private fun TimelineNode(level: RoadmapLevel, status: LevelStatus) {
  when (status) {
    LevelStatus.CURRENT -> Box(contentAlignment = Alignment.Center) {
      Box(modifier = Modifier.size(44.dp).background(PurpleAccent.copy(alpha = 0.15f), CircleShape))
      Box(
        modifier = Modifier
          .size(36.dp)
          .background(PurpleAccentDark, CircleShape)
          .border(2.dp, PurpleAccent, CircleShape),
        contentAlignment = Alignment.Center,
      ) {
        Icon(level.icon, contentDescription = null, tint = PurpleAccent, modifier = Modifier.size(18.dp))
      }
    }
    LevelStatus.COMPLETED -> Box(
      modifier = Modifier
        .size(36.dp)
        .background(PurpleAccent.copy(alpha = 0.2f), CircleShape)
        .border(1.dp, PurpleAccent.copy(alpha = 0.5f), CircleShape),
      contentAlignment = Alignment.Center,
    ) {
      Icon(Icons.Default.Check, contentDescription = "Пройдено", tint = PurpleAccent, modifier = Modifier.size(18.dp))
    }
    LevelStatus.LOCKED -> Box(
      modifier = Modifier
        .size(36.dp)
        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.15f), CircleShape),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = Icons.Default.Lock,
        contentDescription = "Заблокировано",
        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
        modifier = Modifier.size(16.dp),
      )
    }
  }
}

@Composable
private fun PathLevelCard(
  level: RoadmapLevel,
  status: LevelStatus,
  isLocked: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier
      .alpha(if (isLocked) 0.45f else 1f)
      .clip(RoundedCornerShape(16.dp))
      .background(CardDark)
      .then(if (!isLocked) Modifier.clickable(onClick = onClick) else Modifier)
      .padding(16.dp),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(
        text = level.title,
        style = MaterialTheme.typography.titleMedium,
        color = if (status == LevelStatus.CURRENT) PurpleAccent else MaterialTheme.colorScheme.onSurface,
      )
      when (status) {
        LevelStatus.CURRENT -> StatusBadge("АКТИВНО", PurpleAccent, PurpleAccentDark)
        LevelStatus.COMPLETED -> StatusBadge(
          "ПРОЙДЕНО",
          PurpleAccent.copy(alpha = 0.8f),
          PurpleAccentDark.copy(alpha = 0.6f),
        )
        LevelStatus.LOCKED -> Unit
      }
    }
    Spacer(modifier = Modifier.height(6.dp))
    Text(
      text = level.description,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
    )
  }
}

@Composable
private fun StatusBadge(text: String, contentColor: Color, background: Color) {
  Text(
    text = text,
    style = MaterialTheme.typography.labelSmall,
    color = contentColor,
    modifier = Modifier
      .clip(RoundedCornerShape(8.dp))
      .background(background)
      .padding(horizontal = 8.dp, vertical = 2.dp),
  )
}

@Composable
private fun ProTipCard(progressFraction: Float) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(16.dp))
      .background(CardDark)
      .padding(16.dp),
  ) {
    Text("СОВЕТ", style = MaterialTheme.typography.labelSmall, color = PurpleAccent)
    Spacer(modifier = Modifier.height(8.dp))
    Text(
      "Будь последователен",
      style = MaterialTheme.typography.titleMedium,
      color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(modifier = Modifier.height(6.dp))
    Text(
      "Пользователи, проходящие хотя бы один урок в день, на 75% чаще достигают «Мастерства агента».",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
    )
    Spacer(modifier = Modifier.height(16.dp))
    LinearProgressIndicator(
      progress = { progressFraction.coerceIn(0f, 1f) },
      modifier = Modifier
        .fillMaxWidth()
        .height(4.dp)
        .clip(RoundedCornerShape(2.dp)),
      color = PurpleAccent,
      trackColor = Color(0xFF3D3A45),
    )
  }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F, heightDp = 1100)
@Composable
private fun RoadmapScreenPreview() {
  MyApplicationTheme {
    RoadmapContent(
      levels = ROADMAP_LEVELS,
      progress = setOf("l1_welcome"),
      onNavigateToChats = {},
      onNavigateToLibrary = {},
      onNavigateToSettings = {},
      onLevelClick = {},
    )
  }
}
