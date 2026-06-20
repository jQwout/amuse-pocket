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
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material.icons.filled.WavingHand
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.unit.dp
import com.aiopenpocket.ui.components.AcademyBottomNav
import com.aiopenpocket.ui.components.AcademyTab
import com.aiopenpocket.ui.theme.CardDark
import com.aiopenpocket.ui.theme.PurpleAccent
import com.aiopenpocket.ui.theme.PurpleAccentDark
import dev.zacsweers.metrox.viewmodel.metroViewModel

data class RoadmapLevel(
  val id: String,
  val title: String,
  val description: String,
  val icon: ImageVector,
  /** Markdown theory kept as reference for the in-chat lesson script. Not rendered directly. */
  val theory: String = "",
)

val ROADMAP_LEVELS = listOf(
  RoadmapLevel(
    "l1_welcome",
    "Анатомия чата",
    "Сообщение, препромпт, стриминг и путь данных от тебя к LLM и обратно.",
    Icons.Default.WavingHand,
    theory = """
      ## Как устроен один ответ агента

      Когда ты отправляешь сообщение, происходит цепочка шагов:

      1. **Препромпт** — система собирает контекст: базовые правила, выбранную
         персону, релевантные факты и недавнюю историю диалога.
      2. **Запрос к LLM** — всё это уходит в модель одним пакетом сообщений.
      3. **Стриминг** — ответ приходит по кусочкам (токенам) и печатается в реальном времени.
      4. **Инструменты** — если модель решает вызвать тул, ты видишь это в *tool trace*.

      > Локально-первый принцип: в облако уходит только промпт. Память, файлы и
      > история остаются на устройстве.

      Базовая команда для проверки — просто поздоровайся и попроси рассказать,
      что агент умеет.
    """.trimIndent(),
  ),
  RoadmapLevel(
    "l2_notes",
    "Быстрые заметки",
    "Техники захвата контекста в реальном времени и передачи агенту.",
    Icons.Default.EditNote,
    theory = """
      ## Файлы как заметки

      Агент работает с файлами в защищённой песочнице. Доступные инструменты:

      - `write_file` — создать или перезаписать заметку
      - `read_file` — прочитать содержимое
      - `search_files` — найти файл по содержимому
      - `list_dir` — показать список файлов

      **Попробуй:** «Запиши в заметку `idea.txt`, что нужно купить молоко»,
      затем «Прочитай `idea.txt`».
    """.trimIndent(),
  ),
  RoadmapLevel(
    "l3_reminders",
    "Напоминания",
    "Временная логика и автоматические триггеры для выполнения задач.",
    Icons.Default.Notifications,
    theory = """
      ## Напоминания и фоновые задачи

      Агент умеет планировать действия на будущее через `work_manager`:

      - **schedule_notification** — одноразовое уведомление через N минут
      - **schedule_periodic** — повтор каждые N минут (минимум 15)
      - **cancel_work** — отмена задачи по её ID

      > Из-за ограничений Android время срабатывания — приблизительное (±15 минут),
      > особенно в режиме энергосбережения.

      **Попробуй:** «Напомни через 2 минуты проверить почту».
    """.trimIndent(),
  ),
  RoadmapLevel(
    "l4_memory",
    "Память и навыки",
    "Долгая память, эпизоды и возможности агента — базовое представление о системе.",
    Icons.Default.Psychology,
    theory = """
      ## Три слоя памяти

      1. **Короткая** — текущий диалог (последние сообщения).
      2. **Долгая (факты)** — `remember_fact` сохраняет факт о тебе навсегда,
         `search_memory` ищет его позже.
      3. **Эпизоды** — журнал действий: каждый вызов инструмента записывается
         автоматически, `recall_episodes` достаёт прошлые события.

      **Попробуй:** «Запомни: я предпочитаю короткие ответы» — а в новом чате
      спроси, как тебе лучше отвечать.
    """.trimIndent(),
  ),
  /* Уроки 5–7 временно отключены
  RoadmapLevel(
    "l5_database",
    "База данных",
    "SQL-запросы и работа со структурированными данными на устройстве.",
    Icons.Default.Storage,
    theory = """
      ## Структурированные данные

      Когда заметок мало — хватает файлов. Когда данных много и они однотипные
      (задачи, расходы, привычки) — нужна таблица и `query_db`.

      - Запросы — только чтение (`SELECT`), данные не повреждаются.
      - Можно считать агрегаты: `COUNT`, `SUM`, `AVG`.
      - Доступ ограничен безопасными таблицами.

      **Попробуй:** «Сколько у меня записей в базе?»
    """.trimIndent(),
  ),
  RoadmapLevel(
    "l6_trace",
    "Tool Trace",
    "Прозрачность работы агента: как читать трассировку инструментов.",
    Icons.Default.TrackChanges,
    theory = """
      ## Прозрачность работы

      **Tool trace** — это лог всех инструментов, которые агент вызвал, чтобы
      ответить. Он показывает:

      - какой инструмент был вызван и с какими аргументами;
      - что инструмент вернул;
      - порядок шагов в рамках одного ответа.

      > Это не отладочный мусор, а фича: ты всегда видишь, *почему* агент ответил
      > именно так, и можешь доверять результату.

      Открой панель trace иконкой на верхней панели чата после ответа с инструментами.
    """.trimIndent(),
  ),
  RoadmapLevel(
    "l7_combo",
    "Комбо",
    "Комбинирование инструментов для решения сложных задач.",
    Icons.Default.RocketLaunch,
    theory = """
      ## От задачи — к инструментам

      Сильные сценарии получаются, когда агент *комбинирует* инструменты в одном
      ответе:

      - заметка + напоминание: записать идею и напомнить о ней завтра;
      - база + карточка: посчитать данные и показать `render_card(chart_bar)`;
      - память + файлы: вспомнить предпочтение и применить его к новой задаче.

      **Попробуй:** «Составь список дел на сегодня и покажи его карточкой».
    """.trimIndent(),
  ),
  */
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
  val completedCount = progress.size
  val progressFraction = completedCount.toFloat() / ROADMAP_LEVELS.size

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
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Icon(
              imageVector = Icons.Default.School,
              contentDescription = null,
              tint = PurpleAccent,
              modifier = Modifier.size(20.dp),
            )
            Text(
              text = "AI Academy",
              style = MaterialTheme.typography.labelLarge,
              color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
          }
          IconButton(onClick = onNavigateToSettings) {
            Icon(
              imageVector = Icons.Default.Settings,
              contentDescription = "Настройки",
              tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
          }
        }
      }

      item {
        Spacer(modifier = Modifier.height(24.dp))
        Text(
          text = "Путь агента",
          style = MaterialTheme.typography.headlineLarge,
          color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
          text = "Освой искусство оркестрации AI через пошаговую программу обучения.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        Spacer(modifier = Modifier.height(28.dp))
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
              // Урок 1 = онбординг: открываем его экран, остальные уроки — чат.
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
    LevelStatus.COMPLETED -> PurpleAccent.copy(alpha = 0.6f)
    LevelStatus.CURRENT -> PurpleAccent.copy(alpha = 0.4f)
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
            .background(PurpleAccent.copy(alpha = 0.15f), CircleShape),
        )
        Box(
          modifier = Modifier
            .size(36.dp)
            .background(PurpleAccentDark, CircleShape)
            .border(2.dp, PurpleAccent, CircleShape),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = level.icon,
            contentDescription = null,
            tint = PurpleAccent,
            modifier = Modifier.size(18.dp),
          )
        }
      }
    }
    LevelStatus.COMPLETED -> {
      Box(
        modifier = Modifier
          .size(36.dp)
          .background(PurpleAccent.copy(alpha = 0.2f), CircleShape)
          .border(1.dp, PurpleAccent.copy(alpha = 0.5f), CircleShape),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = Icons.Default.Check,
          contentDescription = "Пройдено",
          tint = PurpleAccent,
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
          contentDescription = "Заблокировано",
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
      .clip(RoundedCornerShape(16.dp))
      .background(CardDark)
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
        color = if (status == LevelStatus.CURRENT) PurpleAccent else MaterialTheme.colorScheme.onSurface,
      )
      if (status == LevelStatus.CURRENT) {
        Text(
          text = "АКТИВНО",
          style = MaterialTheme.typography.labelSmall,
          color = PurpleAccent,
          modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(PurpleAccentDark)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        )
      }
      if (status == LevelStatus.COMPLETED) {
        Text(
          text = "ПРОЙДЕНО",
          style = MaterialTheme.typography.labelSmall,
          color = PurpleAccent.copy(alpha = 0.8f),
          modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(PurpleAccentDark.copy(alpha = 0.6f))
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
      .clip(RoundedCornerShape(16.dp))
      .background(CardDark)
      .padding(16.dp),
  ) {
    Text(
      text = "СОВЕТ",
      style = MaterialTheme.typography.labelSmall,
      color = PurpleAccent,
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
      text = "Будь последователен",
      style = MaterialTheme.typography.titleMedium,
      color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(modifier = Modifier.height(6.dp))
    Text(
      text = "Пользователи, проходящие хотя бы один урок в день, на 75% чаще достигают «Мастерства агента».",
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
