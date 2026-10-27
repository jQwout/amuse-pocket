package com.aiopenpocket.ui.roadmap

// @trace ai-academy-onboarding-5d51bb
// @trace english-ui-on-the-demo-path-223241

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
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.aiopenpocket.i18n.tr
import com.aiopenpocket.ui.components.AcademyBottomNav
import com.aiopenpocket.ui.components.AcademyCollapsingScaffold
import com.aiopenpocket.ui.components.glass.glassCardSurface
import com.aiopenpocket.ui.components.glass.rememberGlassState
import com.aiopenpocket.ui.components.AcademyTab
import dev.zacsweers.metrox.viewmodel.metroViewModel

@Composable
fun RoadmapScreen(
  onOpenLessonChat: (String) -> Unit,
  onReopenOnboarding: () -> Unit,
  onNavigateToHome: () -> Unit,
  onNavigateToSettings: () -> Unit,
) {
  val viewModel = metroViewModel<RoadmapViewModel>()
  val progress by viewModel.roadmapProgress.collectAsState()
  val completedCount = progress.size
  val progressFraction = completedCount.toFloat() / ROADMAP_LEVELS.size

  val glassState = rememberGlassState()
  AcademyCollapsingScaffold(
    title = tr("Путь агента", "Agent path"),
    glassState = glassState,
    bottomBar = {
      AcademyBottomNav(
        selectedTab = AcademyTab.Path,
        onTabSelected = { tab ->
          when (tab) {
            AcademyTab.Chat -> onNavigateToHome()
            AcademyTab.Path -> Unit
            AcademyTab.Tools -> onNavigateToSettings()
          }
        },
        glassState = glassState,
      )
    },
  ) { padding ->
    LazyColumn(
      modifier = Modifier
        .fillMaxSize()
        .padding(horizontal = 20.dp),
      // The scaffold paddings go into contentPadding, not .padding(): the list runs the full height and scrolls under
      // the floating islands, while the edge cards do not hide under the glass.
      contentPadding = PaddingValues(
        top = padding.calculateTopPadding(),
        bottom = padding.calculateBottomPadding() + 24.dp,
      ),
    ) {
      item {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = tr(
            "Освой искусство оркестрации AI через пошаговую программу обучения.",
            "Master the art of AI orchestration through a step-by-step learning program.",
          ),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        Spacer(modifier = Modifier.height(24.dp))
      }

      itemsIndexed(ROADMAP_LEVELS) { index, level ->
        val status = when {
          level.id in progress -> LevelStatus.COMPLETED
          index == 0 || ROADMAP_LEVELS.getOrNull(index - 1)?.id in progress -> LevelStatus.CURRENT
          else -> LevelStatus.LOCKED
        }

        PathTimelineItem(
          level = level,
          status = status,
          isFirst = index == 0,
          isLast = index == ROADMAP_LEVELS.lastIndex,
          onClick = {
            if (status != LevelStatus.LOCKED) {
              // Lesson 1 = onboarding: open its screen; the other lessons are a chat.
              if (level.id == "l1_welcome") onReopenOnboarding()
              else viewModel.openLesson(level.id, onOpenLessonChat)
            }
          },
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
private fun PathTimelineItem(
  level: RoadmapLevel,
  status: LevelStatus,
  isFirst: Boolean,
  isLast: Boolean,
  onClick: () -> Unit,
) {
  val isLocked = status == LevelStatus.LOCKED
  val lineColor = when (status) {
    LevelStatus.COMPLETED -> MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
    LevelStatus.CURRENT -> MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
    LevelStatus.LOCKED -> MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
  }

  Row(
    modifier = Modifier
      .fillMaxWidth()
      .height(IntrinsicSize.Min),
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier = Modifier
        .width(48.dp)
        .fillMaxHeight(),
    ) {
      if (!isFirst) {
        Box(
          modifier = Modifier
            .width(2.dp)
            .height(16.dp)
            .background(lineColor),
        )
      } else {
        Spacer(modifier = Modifier.height(16.dp))
      }

      TimelineNode(level = level, status = status)

      if (!isLast) {
        Box(
          modifier = Modifier
            .width(2.dp)
            .weight(1f)
            .background(lineColor),
        )
      }
    }

    Spacer(modifier = Modifier.width(12.dp))

    PathLevelCard(
      level = level,
      status = status,
      isLocked = isLocked,
      onClick = onClick,
      modifier = Modifier
        .weight(1f)
        .padding(bottom = if (isLast) 0.dp else 16.dp),
    )
  }
}

@Composable
private fun TimelineNode(
  level: RoadmapLevel,
  status: LevelStatus,
) {
  when (status) {
    LevelStatus.CURRENT -> {
      Box(contentAlignment = Alignment.Center) {
        Box(
          modifier = Modifier
            .size(44.dp)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), CircleShape),
        )
        Box(
          modifier = Modifier
            .size(36.dp)
            .background(MaterialTheme.colorScheme.onPrimary, CircleShape)
            .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = level.icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
          )
        }
      }
    }
    LevelStatus.COMPLETED -> {
      Box(
        modifier = Modifier
          .size(36.dp)
          .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f), CircleShape)
          .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), CircleShape),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = Icons.Default.Check,
          contentDescription = tr("Пройдено", "Completed"),
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(18.dp),
        )
      }
    }
    LevelStatus.LOCKED -> {
      Box(
        modifier = Modifier
          .size(36.dp)
          .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.15f), CircleShape),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = Icons.Default.Lock,
          contentDescription = tr("Заблокировано", "Locked"),
          tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
          modifier = Modifier.size(16.dp),
        )
      }
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
  val alpha = if (isLocked) 0.45f else 1f

  Column(
    modifier = modifier
      .alpha(alpha)
      .glassCardSurface(RoundedCornerShape(16.dp))
      .then(
        if (!isLocked) Modifier.clickable(onClick = onClick) else Modifier,
      )
      .padding(16.dp),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(
        text = level.title,
        style = MaterialTheme.typography.titleMedium,
        color = if (status == LevelStatus.CURRENT) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
      )
      if (status == LevelStatus.CURRENT) {
        Text(
          text = tr("АКТИВНО", "ACTIVE"),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.primary,
          modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.onPrimary)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        )
      }
      if (status == LevelStatus.COMPLETED) {
        Text(
          text = tr("ПРОЙДЕНО", "DONE"),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
          modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.6f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        )
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
private fun ProTipCard(progressFraction: Float) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .glassCardSurface(RoundedCornerShape(16.dp))
      .padding(16.dp),
  ) {
    Text(
      text = tr("СОВЕТ", "TIP"),
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.primary,
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
      text = tr("Будь последователен", "Stay consistent"),
      style = MaterialTheme.typography.titleMedium,
      color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(modifier = Modifier.height(6.dp))
    Text(
      text = tr(
        "Пользователи, проходящие хотя бы один урок в день, на 75% чаще достигают «Мастерства агента».",
        "People who take at least one lesson a day are 75% more likely to reach “Agent Mastery.”",
      ),
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
      color = MaterialTheme.colorScheme.primary,
      trackColor = Color(0xFF3D3A45),
    )
  }
}
