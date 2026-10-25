package com.aiopenpocket.ui.home

// @trace multi-chat-conversations-b3c93b
//
// Превью-каркас экрана Home для скриншот-тестов: те же карточки на фейковых данных, без
// ViewModel/DI. Вынесено из HomeScreen.kt (паттерн ChatScreenPreviews).

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.Icons
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aiopenpocket.agent.core.memory.model.Conversation
import com.aiopenpocket.ui.components.AcademyBottomNav
import com.aiopenpocket.ui.components.AcademyTab
import com.aiopenpocket.ui.components.glass.auroraBackground
import com.aiopenpocket.ui.components.glass.GlassCard
import com.aiopenpocket.ui.components.glass.glassSource
import com.aiopenpocket.ui.components.glass.rememberGlassState
import com.aiopenpocket.ui.roadmap.ROADMAP_LEVELS
import com.aiopenpocket.ui.theme.MyApplicationTheme
import com.aiopenpocket.ui.theme.ThemeMode
import dev.zacsweers.metrox.viewmodel.metroViewModel

// ==================================================================================================
// Превью экрана Home (скриншот-тесты). Тот же визуальный каркас (аврора/GlassCard/GlassSurface-нав),
// что и в бою, собранный из тех же file-private карточек ([NewChatCard]/[AgentsEntryCard]/
// [RecentChatCard]/[CourseCard]/[EmptyHint]/[HomeHero]/[SectionHeader]), но с фейковыми данными
// вместо ViewModel — [HomeScreen] тянет обе VM через metroViewModel(), которому в Robolectric/
// @Preview неоткуда взять DI-граф. Паттерн — как у ChatPreviewScaffold (ChatScreen.kt).
// ==================================================================================================

private fun previewConversation(id: String, title: String, updatedAt: Long) = Conversation(
    id = id,
    title = title,
    createdAt = updatedAt,
    updatedAt = updatedAt,
    personaId = "preview",
    summary = null,
)

internal val previewRecentChats = listOf(
    previewConversation("c1", "Как работает RAG?", 3_000L),
    previewConversation("c2", "План на неделю", 2_000L),
    previewConversation("c3", "Отладка агента", 1_000L),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomePreviewScaffold(
    recentChats: List<Conversation> = previewRecentChats,
) {
    val overallProgress = 0.4f
    val currentLevelIndex = 2
    val currentLevel = ROADMAP_LEVELS.getOrNull(currentLevelIndex)

    MyApplicationTheme(mode = ThemeMode.DARK) {
        val glassState = rememberGlassState()
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
                            IconButton(onClick = {}) {
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
                                  .clickable(onClick = {}),
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
                        onTabSelected = {},
                        glassState = glassState,
                    )
                },
            ) { padding ->
                LazyColumn(
                    modifier = Modifier
                      .fillMaxSize()
                      .padding(padding)
                      .padding(horizontal = 20.dp)
                      .glassSource(glassState),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 12.dp),
                ) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        HomeHero()
                        Spacer(modifier = Modifier.height(28.dp))
                    }
                    item {
                        NewChatCard(onClick = {})
                        Spacer(modifier = Modifier.height(12.dp))
                        AgentsEntryCard(onClick = {})
                        Spacer(modifier = Modifier.height(28.dp))
                    }

                    item {
                        SectionHeader(
                            title = "НЕДАВНИЕ ЧАТЫ",
                            actionLabel = "Все",
                            onAction = {},
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                    if (recentChats.isEmpty()) {
                        item { EmptyHint(text = "Здесь появятся твои недавние чаты.") }
                    } else {
                        items(recentChats, key = { it.id }) { chat ->
                            RecentChatCard(chat = chat, onClick = {})
                            Spacer(modifier = Modifier.height(10.dp))
                        }
                    }

                    item {
                        Spacer(modifier = Modifier.height(20.dp))
                        SectionHeader(
                            title = "ПРОДОЛЖИТЬ ОБУЧЕНИЕ",
                            actionLabel = "Все курсы",
                            onAction = {},
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                    item {
                        CourseCard(
                            category = "AGENT PATH",
                            title = "Путь агента",
                            description = "Освой оркестрацию AI через пошаговую программу обучения.",
                            progress = overallProgress,
                            onClick = {},
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
                                onClick = {},
                            )
                        }
                    }
                }
            }
        }
    }
}
