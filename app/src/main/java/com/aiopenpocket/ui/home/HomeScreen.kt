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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import com.aiopenpocket.ui.components.glass.GlassCard
import com.aiopenpocket.ui.components.glass.auroraBackground
import com.aiopenpocket.ui.components.glass.glassSource
import com.aiopenpocket.ui.components.glass.rememberGlassState
import com.aiopenpocket.ui.conversations.ConversationsListViewModel
import com.aiopenpocket.ui.roadmap.ROADMAP_LEVELS
import com.aiopenpocket.ui.roadmap.RoadmapViewModel
import com.aiopenpocket.ui.theme.MyApplicationTheme
import com.aiopenpocket.ui.theme.ThemeMode
import com.aiopenpocket.ui.util.formatRelativeDate
import dev.zacsweers.metrox.viewmodel.metroViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
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
    val glassState = rememberGlassState()
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

    Box(Modifier
      .fillMaxSize()
      .auroraBackground()) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = "AI Academy",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        actionIconContentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                    actions = {
                        IconButton(onClick = onNavigateToConversations) {
                            Icon(
                                imageVector = Icons.Default.History,
                                contentDescription = "История чатов",
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            )
                        }
                        Box(
                            modifier = Modifier
                              .size(40.dp)
                              .clip(CircleShape)
                              .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                              .background(MaterialTheme.colorScheme.onPrimary)
                              .clickable(onClick = onNavigateToSettings),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = "Профиль",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        Spacer(modifier = Modifier.size(12.dp))
                    },
                )
            },
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
                    glassState = glassState,
                )
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                  .fillMaxSize()
                  .padding(horizontal = 20.dp)
                  .glassSource(glassState),
                // Отступы каркаса — в contentPadding, а НЕ в .padding(): иначе список инсетится и
                // не заезжает под плавающий навбар, из-за чего Главная выглядела иначе, чем
                // остальные экраны (навбар «стоял», а не плавал над контентом).
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding() + 4.dp,
                    bottom = padding.calculateBottomPadding() + 12.dp,
                ),
            ) {
                item {
                    Spacer(modifier = Modifier.height(8.dp))
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
                    item { EmptyHint(text = "Здесь появятся твои недавние чаты.") }
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
}

