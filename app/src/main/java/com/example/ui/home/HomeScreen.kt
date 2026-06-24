package com.aiopenpocket.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aiopenpocket.agent.core.memory.model.Conversation
import com.aiopenpocket.ui.components.AcademyBottomNav
import com.aiopenpocket.ui.components.AcademyTab
import com.aiopenpocket.ui.conversations.ConversationsListViewModel
import com.aiopenpocket.ui.roadmap.ROADMAP_LEVELS
import com.aiopenpocket.ui.roadmap.RoadmapViewModel
import com.aiopenpocket.ui.theme.CardDark
import com.aiopenpocket.ui.theme.PersonaQuoteBg
import com.aiopenpocket.ui.theme.PurpleAccent
import com.aiopenpocket.ui.theme.PurpleAccentDark
import dev.zacsweers.metrox.viewmodel.metroViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

private val CardShape = RoundedCornerShape(20.dp)

@Composable
fun HomeScreen(
  onOpenChat: (String) -> Unit,
  onOpenLessonChat: (String) -> Unit,
  onNavigateToConversations: () -> Unit,
  onNavigateToLibrary: () -> Unit,
  onNavigateToRoadmap: () -> Unit,
  onNavigateToSettings: () -> Unit,
  onNavigateToAgents: () -> Unit,
) {
  val conversationsViewModel: ConversationsListViewModel = metroViewModel()
  val roadmapViewModel: RoadmapViewModel = metroViewModel()
  val conversationsState by conversationsViewModel.state.collectAsState()
  val progress by roadmapViewModel.roadmapProgress.collectAsState()
  val scope = rememberCoroutineScope()

  val recentChats = remember(conversationsState.conversations) {
    conversationsState.conversations
      .filter { !it.isTutorial }
      .sortedByDescending { it.updatedAt }
      .take(3)
  }
  val completedCount = ROADMAP_LEVELS.count { it.id in progress }
  val overallProgress =
    if (ROADMAP_LEVELS.isEmpty()) 0f else completedCount.toFloat() / ROADMAP_LEVELS.size
  val currentLevelIndex = ROADMAP_LEVELS.indexOfFirst { it.id !in progress }
  val currentLevel = ROADMAP_LEVELS.getOrNull(currentLevelIndex)

  val openNewChat: () -> Unit = {
    scope.launch {
      val id = conversationsViewModel.createConversation()
      if (id != null) onOpenChat(id)
    }
    Unit
  }

  Scaffold(
    containerColor = PersonaQuoteBg,
    bottomBar = {
      AcademyBottomNav(
        selectedTab = AcademyTab.Chat,
        onTabSelected = { tab ->
          when (tab) {
            AcademyTab.Chat -> Unit
            AcademyTab.Path -> onNavigateToRoadmap()
            AcademyTab.Tools -> onNavigateToSettings()
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
      contentPadding = PaddingValues(top = 12.dp, bottom = 12.dp),
    ) {
      item {
        HomeTopBar(
          onHistoryClick = onNavigateToConversations,
          onAvatarClick = onNavigateToSettings,
        )
      }
      item {
        Spacer(modifier = Modifier.height(32.dp))
        HomeHero()
        Spacer(modifier = Modifier.height(28.dp))
      }
      item {
        NewChatCard(onClick = openNewChat)
        Spacer(modifier = Modifier.height(12.dp))
        AgentsEntryCard(onClick = onNavigateToAgents)
        Spacer(modifier = Modifier.height(28.dp))
      }

      item {
        SectionHeader(
          title = "НЕДАВНИЕ ЧАТЫ",
          actionLabel = "Все",
          onAction = onNavigateToConversations,
        )
        Spacer(modifier = Modifier.height(12.dp))
      }
      if (recentChats.isEmpty()) {
        item { EmptyHint(text = "Здесь появятся ваши недавние чаты.") }
      } else {
        items(recentChats, key = { it.id }) { chat ->
          RecentChatCard(chat = chat, onClick = { onOpenChat(chat.id) })
          Spacer(modifier = Modifier.height(10.dp))
        }
      }

      item {
        Spacer(modifier = Modifier.height(20.dp))
        SectionHeader(
          title = "ПРОДОЛЖИТЬ ОБУЧЕНИЕ",
          actionLabel = "Все курсы",
          onAction = onNavigateToLibrary,
        )
        Spacer(modifier = Modifier.height(12.dp))
      }
      item {
        CourseCard(
          category = "AGENT PATH",
          title = "Путь агента",
          description = "Освой оркестрацию AI через пошаговую программу обучения.",
          progress = overallProgress,
          onClick = onNavigateToRoadmap,
        )
      }
      if (currentLevel != null) {
        item {
          Spacer(modifier = Modifier.height(12.dp))
          CourseCard(
            category = "СЛЕДУЮЩИЙ УРОК · ${currentLevelIndex + 1} ИЗ ${ROADMAP_LEVELS.size}",
            title = currentLevel.title,
            description = currentLevel.description,
            progress = overallProgress,
            onClick = { roadmapViewModel.openLesson(currentLevel.id, onOpenLessonChat) },
          )
        }
      }
    }
  }
}

@Composable
private fun HomeTopBar(
  onHistoryClick: () -> Unit,
  onAvatarClick: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = "school AI",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
      )
      Text(
        text = "Academy",
        style = MaterialTheme.typography.headlineSmall,
        color = PurpleAccent,
      )
    }
    IconButton(onClick = onHistoryClick) {
      Icon(
        imageVector = Icons.Default.History,
        contentDescription = "История чатов",
        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
      )
    }
    Spacer(modifier = Modifier.size(4.dp))
    Box(
      modifier = Modifier
        .size(40.dp)
        .clip(CircleShape)
        .border(2.dp, PurpleAccent, CircleShape)
        .background(PurpleAccentDark)
        .clickable(onClick = onAvatarClick),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = Icons.Default.Person,
        contentDescription = "Профиль",
        tint = PurpleAccent,
        modifier = Modifier.size(22.dp),
      )
    }
  }
}

@Composable
private fun HomeHero() {
  Column(
    modifier = Modifier.fillMaxWidth(),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Box(
      modifier = Modifier
        .size(72.dp)
        .clip(CircleShape)
        .background(CardDark),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = Icons.Default.AutoAwesome,
        contentDescription = null,
        tint = PurpleAccent,
        modifier = Modifier.size(32.dp),
      )
    }
    Spacer(modifier = Modifier.height(20.dp))
    Text(
      text = "Чем помочь сегодня?",
      style = MaterialTheme.typography.headlineMedium,
      color = MaterialTheme.colorScheme.onSurface,
      textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
      text = "Задайте вопрос или продолжите обучение",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
      textAlign = TextAlign.Center,
    )
  }
}

@Composable
private fun NewChatCard(onClick: () -> Unit) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(CardShape)
      .background(CardDark)
      .clickable(onClick = onClick)
      .padding(16.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      modifier = Modifier
        .size(44.dp)
        .clip(CircleShape)
        .background(PurpleAccent),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = Icons.Default.AutoAwesome,
        contentDescription = null,
        tint = PurpleAccentDark,
        modifier = Modifier.size(22.dp),
      )
    }
    Column(
      modifier = Modifier
        .weight(1f)
        .padding(horizontal = 14.dp),
    ) {
      Text(
        text = "Новый чат",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Text(
        text = "Спросить AI Academy что угодно",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
      )
    }
    Icon(
      imageVector = Icons.AutoMirrored.Default.KeyboardArrowRight,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
    )
  }
}

@Composable
private fun AgentsEntryCard(onClick: () -> Unit) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(CardShape)
      .background(CardDark)
      .clickable(onClick = onClick)
      .padding(16.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      modifier = Modifier
        .size(44.dp)
        .clip(CircleShape)
        .background(PurpleAccentDark),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = Icons.Default.Hub,
        contentDescription = null,
        tint = PurpleAccent,
        modifier = Modifier.size(22.dp),
      )
    }
    Column(
      modifier = Modifier
        .weight(1f)
        .padding(horizontal = 14.dp),
    ) {
      Text(
        text = "Агенты",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Text(
        text = "Специалисты и координатор, который их распределяет",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
      )
    }
    Icon(
      imageVector = Icons.AutoMirrored.Default.KeyboardArrowRight,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
    )
  }
}

@Composable
private fun SectionHeader(
  title: String,
  actionLabel: String,
  onAction: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = title,
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
    )
    Text(
      text = actionLabel,
      style = MaterialTheme.typography.labelLarge,
      color = PurpleAccent,
      modifier = Modifier
        .clip(RoundedCornerShape(8.dp))
        .clickable(onClick = onAction)
        .padding(horizontal = 6.dp, vertical = 2.dp),
    )
  }
}

@Composable
private fun RecentChatCard(chat: Conversation, onClick: () -> Unit) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(CardShape)
      .background(CardDark)
      .clickable(onClick = onClick)
      .padding(14.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      modifier = Modifier
        .size(40.dp)
        .clip(CircleShape)
        .background(PurpleAccentDark),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = Icons.AutoMirrored.Filled.Chat,
        contentDescription = null,
        tint = PurpleAccent,
        modifier = Modifier.size(20.dp),
      )
    }
    Column(
      modifier = Modifier
        .weight(1f)
        .padding(horizontal = 14.dp),
    ) {
      Text(
        text = chat.title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = formatRelativeDate(chat.updatedAt),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
      )
    }
    Icon(
      imageVector = Icons.AutoMirrored.Default.KeyboardArrowRight,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
    )
  }
}

@Composable
private fun CourseCard(
  category: String,
  title: String,
  description: String,
  progress: Float,
  onClick: () -> Unit,
) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(CardShape)
      .background(CardDark)
      .clickable(onClick = onClick)
      .padding(18.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = category,
        style = MaterialTheme.typography.labelSmall,
        color = PurpleAccent,
      )
      Icon(
        imageVector = Icons.AutoMirrored.Default.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
      )
    }
    Spacer(modifier = Modifier.height(10.dp))
    Text(
      text = title,
      style = MaterialTheme.typography.titleLarge,
      color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(modifier = Modifier.height(4.dp))
    Text(
      text = description,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
    )
    Spacer(modifier = Modifier.height(16.dp))
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Text(
        text = "Прогресс",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
      )
      Text(
        text = "${(progress.coerceIn(0f, 1f) * 100).toInt()}%",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
      )
    }
    Spacer(modifier = Modifier.height(8.dp))
    LinearProgressIndicator(
      progress = { progress.coerceIn(0f, 1f) },
      modifier = Modifier
        .fillMaxWidth()
        .height(6.dp)
        .clip(RoundedCornerShape(3.dp)),
      color = PurpleAccent,
      trackColor = Color(0xFF3D3A45),
    )
  }
}

@Composable
private fun EmptyHint(text: String) {
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .clip(CardShape)
      .background(CardDark)
      .padding(18.dp),
  ) {
    Text(
      text = text,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
    )
  }
}

/** Сегодня/Вчера с временем, иначе «N дней назад» или дата. */
private fun formatRelativeDate(timestamp: Long): String {
  val now = Calendar.getInstance()
  val then = Calendar.getInstance().apply { timeInMillis = timestamp }
  val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))

  fun sameDay(a: Calendar, b: Calendar) =
    a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
      a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

  val yesterday = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }

  return when {
    sameDay(now, then) -> "Сегодня, $time"
    sameDay(yesterday, then) -> "Вчера, $time"
    else -> {
      val days = ((now.timeInMillis - timestamp) / (1000L * 60 * 60 * 24)).toInt()
      if (days in 2..6) {
        "$days ${pluralDays(days)} назад"
      } else {
        SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(timestamp))
      }
    }
  }
}

private fun pluralDays(days: Int): String {
  val mod10 = days % 10
  val mod100 = days % 100
  return when {
    mod10 == 1 && mod100 != 11 -> "день"
    mod10 in 2..4 && mod100 !in 12..14 -> "дня"
    else -> "дней"
  }
}
