package com.aiopenpocket.ui.roadmap

// @trace ai-academy-onboarding-5d51bb

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
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Architecture
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.WavingHand
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.aiopenpocket.ui.components.AcademyBottomNav
import com.aiopenpocket.ui.components.AcademyCollapsingScaffold
import com.aiopenpocket.ui.components.glass.glassCardSurface
import com.aiopenpocket.ui.components.glass.rememberGlassState
import com.aiopenpocket.ui.components.AcademyTab
import dev.zacsweers.metrox.viewmodel.metroViewModel

data class RoadmapLevel(
  val id: String,
  val title: String,
  val description: String,
  val icon: ImageVector,
  /** Markdown theory kept as reference for the in-chat lesson script. Not rendered directly. */
  val theory: String = "",
)

// Единый путь обучения = онбординг + все уроки-концепции (порядок педагогический, совпадает с
// каталогом библиотеки). l1_welcome — онбординг (открывается своим экраном), остальные — JSON-уроки
// из assets/lessons, которые драйвит чат-контроллер. Описания держим в синхроне с *.json.
val ROADMAP_LEVELS = listOf(
  RoadmapLevel(
    "l1_welcome",
    "Знакомство и настройка",
    "Настрой ассистента под себя: язык, персона, профиль — и первое касание того, как устроен чат.",
    Icons.Default.WavingHand,
  ),
  // Стоит ПЕРВЫМ уроком пути: до него курс нигде не объяснял, что вообще за программа отвечает
  // пользователю. «Как ИИ читает твой запрос» сразу начинает с промпта, а слова «модель» и
  // «агент» звучат дальше в каждом уроке. Полное разворачивание агента — в «Воркфлоу и агент».
  RoadmapLevel(
    "what_is_agent",
    "Что такое ИИ и кто такой агент",
    "Что за программа отвечает тебе на самом деле, чем агент отличается от чата и из каких частей он собран.",
    Icons.Default.SmartToy,
  ),
  RoadmapLevel(
    "how_llm_reads",
    "Как ИИ читает твой запрос",
    "Промпт, препромпт и путь запроса внутри модели.",
    Icons.Default.Psychology,
  ),
  RoadmapLevel(
    "how_to_ask",
    "Как просить",
    "Почему точный запрос даёт точный ответ и как формулировать, чтобы получить нужное.",
    Icons.Default.EditNote,
  ),
  RoadmapLevel(
    "tokens_and_speed",
    "Токены и скорость",
    "Что такое токен, входные и выходные токены, рассуждения моделей и отчего один ответ быстрый, а другой долгий и дорогой.",
    Icons.Default.Bolt,
  ),
  // Сразу за токенами: видео — это те же токены, только из кадров, и урок опирается на «больше
  // токенов — дольше и дороже». Здесь же пользователь впервые отдаёт модели свои данные не словами.
  RoadmapLevel(
    "how_model_sees",
    "Как модель видит",
    "Фото и видео тоже становятся токенами. Покажи модели короткий ролик — и посмотри, что она увидит.",
    Icons.Default.Videocam,
  ),
  // Модели Meta — сразу за видео: ученик только что видел, как Muse Spark смотрит ролик, и естественно
  // спросить «а кто она и кто ещё есть». Режимы рассуждения — следом, пока свежи токены рассуждений.
  RoadmapLevel(
    "meta_models",
    "Модели Meta: кто есть кто",
    "У Meta не одна модель, а команда специалистов: Muse Spark, Muse Image, SAM, Llama. Кто за что отвечает и кто работает в этом приложении.",
    Icons.Default.Hub,
  ),
  RoadmapLevel(
    "muse_spark_modes",
    "Muse Spark: быстро или вдумчиво",
    "Режимы Instant, Thinking и Contemplating: зачем модели думать дольше и когда это того стоит.",
    Icons.Default.Speed,
  ),
  // Сразу за токенами, а не в середине пути: урок про память начинается ровно там, где кончается
  // урок про окно — «окно ограничено, старое вытесняется, значит важное надо хранить снаружи».
  // К тому же «Приватность» ниже опирается на виды памяти, и раньше ссылалась на шесть уроков вперёд.
  RoadmapLevel(
    "memory_and_context",
    "Память и контекст",
    "Почему модель будто забывает разговор и как агент помнит тебя тремя видами памяти.",
    Icons.Default.Memory,
  ),
  RoadmapLevel(
    "temperature_and_randomness",
    "Почему ответы разные",
    "Что такое температура и почему один и тот же запрос даёт разные ответы.",
    Icons.Default.Thermostat,
  ),
  // За температурой: «один промпт — новая картинка» — та же случайность, только в рисовании.
  RoadmapLevel(
    "muse_image_draws",
    "Как рисует Muse Image",
    "Модель Meta для картинок сначала планирует, потом рисует и проверяет. И почему один промпт каждый раз даёт новую картинку.",
    Icons.Default.Palette,
  ),
  RoadmapLevel(
    "privacy_and_reliability",
    "Приватность и надёжность",
    "Что уходит в облако, что остаётся у тебя, и что делать при сбоях.",
    Icons.Default.Shield,
  ),
  // За приватностью: «что уходит провайдеру» доводится до «а можно ли не отдавать вовсе».
  RoadmapLevel(
    "open_weights_and_api",
    "Открытые веса и API",
    "Где на самом деле живёт модель: на твоём сервере или у вендора. От этого зависит, кто видит твои данные и кто делает работу.",
    Icons.Default.Storage,
  ),
  RoadmapLevel(
    "hallucinations",
    "Когда модель ошибается",
    "Почему модель уверенно выдумывает и как ловить такие ответы.",
    Icons.Default.TrackChanges,
  ),
  RoadmapLevel(
    "workflow_and_agent",
    "Воркфлоу и агент",
    "Два способа выстроить многошаговую задачу: заранее заданный воркфлоу и самостоятельный агент — когда брать какой.",
    Icons.Default.AccountTree,
  ),
  RoadmapLevel(
    "react_loop",
    "Цикл агента: ReAct",
    "Как агент работает внутри: рассуждай → действуй → наблюдай, и так по кругу — на живом примере.",
    Icons.Default.Loop,
  ),
  RoadmapLevel(
    "agent_tools",
    "Инструменты агента",
    "Чем агент действует: как модель вызывает инструменты и получает результат обратно.",
    Icons.Default.Build,
  ),
  RoadmapLevel(
    "tool_approval_and_limits",
    "Подтверждение инструментов",
    "Как оценить, насколько опасно действие агента, и как заставить его спрашивать твоё «ок»: классы риска, оценка по каждому вызову и хуки.",
    Icons.Default.Shield,
  ),
  // Идёт ПОСЛЕ памяти, хотя в LIBRARY_LESSON_IDS стоит третьим: урок опирается на контекстное окно
  // («место в окне ограничено», «середину диалога заменяют резюме») и на релевантные факты из
  // памяти. На пути порядок — обещание, и вперёд ссылаться нельзя; в библиотеке, куда заходят
  // вразнобой, это терпимо.
  RoadmapLevel(
    "context_engineering",
    "Контекст-инжиниринг",
    "Как перед каждым ответом собирается препромпт: слои, отбор, бюджет и защита от подмены.",
    Icons.Default.Layers,
  ),
  RoadmapLevel(
    "agent_skills",
    "Навыки агента",
    "Как агент подгружает умения по требованию, не раздувая контекст — три уровня раскрытия.",
    Icons.Default.Extension,
  ),
  // «RAG» убран с ПУТИ и оставлен только в библиотеке (LIBRARY_LESSON_IDS): урок сам трижды
  // честно говорит, что в приложении этой техники ещё нет, и его «практика» — упражнение на
  // бумаге. На пути каждый урок заканчивается действием в приложении; вернуть сюда, когда
  // поиск по документам появится.
  RoadmapLevel(
    "plan_mode",
    "План-мод",
    "Агент сначала строит план и ждёт твоего «ок» — «подумать» отдельно от «сделать».",
    Icons.Default.Checklist,
  ),
  RoadmapLevel(
    "goal_mode",
    "Гоал-мод",
    "Задаёшь цель и критерии — агент сам идёт к ней циклом, сверяясь с результатом.",
    Icons.Default.Flag,
  ),
  RoadmapLevel(
    "structured_plans",
    "Структурированные планы",
    "Большая задача как список проверяемых шагов: одобрение плана, статусы и правило «сделал — докажи».",
    Icons.Default.Checklist,
  ),
  // Рефлексия — такой же режим агента, как план-мод и гоал-мод, и он в приложении РЕАЛЬНО есть
  // (чип над полем ввода). Поэтому её место здесь, среди режимов, а не только в библиотеке.
  RoadmapLevel(
    "reflection",
    "Рефлексия",
    "Агент сначала пишет черновик, потом критикует его и переписывает — цикл ради качества.",
    Icons.Default.AutoAwesome,
  ),
  RoadmapLevel(
    "heartbeat_and_cron",
    "Heartbeat и cron",
    "Как агент живёт без твоих сообщений: расписания, фоновые пробуждения и право молчать.",
    Icons.Default.Alarm,
  ),
  RoadmapLevel(
    "chaining_and_routing",
    "Паттерны: цепочка и роутинг",
    "Два базовых паттерна воркфлоу: промпт-чейнинг и роутинг задачи.",
    Icons.AutoMirrored.Filled.AltRoute,
  ),
  RoadmapLevel(
    "arena_and_swarm",
    "Арена и рой агентов",
    "Несколько прогонов или агентов сразу: сравнить лучший ответ и поделить задачу.",
    Icons.Default.Groups,
  ),
  RoadmapLevel(
    "hooks_and_lifecycle",
    "Хуки и жизненный цикл",
    "Правила «на событие хода — сделай» без кода: события, действия и приоритет запрета.",
    Icons.Default.Bolt,
  ),
  RoadmapLevel(
    "build_your_agent",
    "Собери своего агента",
    "Как все части курса складываются в собственного агента через конструктор из шести шагов.",
    Icons.Default.Build,
  ),
  RoadmapLevel(
    "agent_blueprint",
    "Капстоун: спроектируй агента",
    "Финал курса: чеклист из пяти вопросов — цель, инструменты, паттерн, память, проверка.",
    Icons.Default.Architecture,
  ),
)

enum class LevelStatus { LOCKED, CURRENT, COMPLETED }

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
    title = "Путь агента",
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
      // Отступы каркаса — в contentPadding, а не в .padding(): список идёт во всю высоту и
      // скроллится под плавающими островами, крайние карточки не прячутся под стеклом.
      contentPadding = PaddingValues(
        top = padding.calculateTopPadding(),
        bottom = padding.calculateBottomPadding() + 24.dp,
      ),
    ) {
      item {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = "Освой искусство оркестрации AI через пошаговую программу обучения.",
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
          contentDescription = "Пройдено",
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
          text = "АКТИВНО",
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
          text = "ПРОЙДЕНО",
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
      text = "СОВЕТ",
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.primary,
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
      color = MaterialTheme.colorScheme.primary,
      trackColor = Color(0xFF3D3A45),
    )
  }
}
