package com.aiopenpocket.ui.roadmap

// @trace ai-academy-onboarding-5d51bb
// @trace english-ui-on-the-demo-path-223241
//
// The learning path as data: the levels in their pedagogical order and the level status. The Path screen
// (RoadmapScreen.kt) and Home draw from it.

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Architecture
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Layers
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
import androidx.compose.ui.graphics.vector.ImageVector
import com.aiopenpocket.i18n.tr

/**
 * A level of the path. Texts are stored as a ru/en pair, and [title]/[description] pick the language with a
 * getter at display time: [ROADMAP_LEVELS] is initialized once, and `tr` in the initializer would freeze the
 * language.
 */
data class RoadmapLevel(
  val id: String,
  val titleRu: String,
  val descriptionRu: String,
  val icon: ImageVector,
  val titleEn: String,
  val descriptionEn: String,
) {
  val title: String get() = tr(titleRu, titleEn)
  val description: String get() = tr(descriptionRu, descriptionEn)
}

// The single learning path = onboarding + all the concept lessons (the order is pedagogical and matches the
// library catalog). l1_welcome is onboarding (it opens its own screen), the rest are JSON lessons from
// assets/lessons driven by the chat controller. The descriptions are kept in sync with the *.json.
val ROADMAP_LEVELS = listOf(
  RoadmapLevel(
    "l1_welcome",
    "Знакомство и настройка",
    "Настрой ассистента под себя: язык, персона, профиль — и первое касание того, как устроен чат.",
    Icons.Default.WavingHand,
    titleEn = "Welcome and setup",
    descriptionEn = "Tune the assistant to you: language, persona, profile — and a first look at how the chat works.",
  ),
  // It is the FIRST lesson of the path: before it the course explained nowhere what kind of program is answering
  // the user at all. "How AI reads your request" starts right away with the prompt, and the words "model" and
  // "agent" come up later in every lesson. The full unfolding of the agent is in "Workflow and agent".
  RoadmapLevel(
    "what_is_agent",
    "Что такое ИИ и кто такой агент",
    "Что за программа отвечает тебе на самом деле, чем агент отличается от чата и из каких частей он собран.",
    Icons.Default.SmartToy,
    titleEn = "What AI is and what an agent is",
    descriptionEn = "What kind of program is really answering you, how an agent differs from a chat, and what parts it's made of.",
  ),
  RoadmapLevel(
    "how_llm_reads",
    "Как ИИ читает твой запрос",
    "Промпт, препромпт и путь запроса внутри модели.",
    Icons.Default.Psychology,
    titleEn = "How AI reads your request",
    descriptionEn = "The prompt, the pre-prompt and the path a request takes inside the model.",
  ),
  RoadmapLevel(
    "how_to_ask",
    "Как просить",
    "Почему точный запрос даёт точный ответ и как формулировать, чтобы получить нужное.",
    Icons.Default.EditNote,
    titleEn = "How to ask",
    descriptionEn = "Why a precise request gets a precise answer, and how to phrase things to get what you need.",
  ),
  RoadmapLevel(
    "tokens_and_speed",
    "Токены и скорость",
    "Что такое токен, входные и выходные токены и отчего один ответ быстрый, а другой долгий и дорогой.",
    Icons.Default.Bolt,
    titleEn = "Tokens and speed",
    descriptionEn = "What a token is, input vs. output tokens, and why one answer is fast while another is slow and expensive.",
  ),
  // Right after tokens: video is the same tokens, only from frames, and the lesson builds on "more tokens — slower
  // and more expensive". Here too the user gives the model their data other than in words for the first time.
  RoadmapLevel(
    "how_model_sees",
    "Как модель видит",
    "Фото и видео тоже становятся токенами. Покажи модели короткий ролик — и посмотри, что она увидит.",
    Icons.Default.Videocam,
    titleEn = "How a model sees",
    descriptionEn = "Photos and video become tokens too. Show the model a short clip — and see what it notices.",
  ),
  // Meta's models — right after video: the learner has just seen Muse Spark watch a clip, and it is natural to ask
  // "who is she and who else is there". Reasoning modes come next: the lesson itself introduces thinking models
  // (this used to be a block in "Tokens", and the tokens lesson grew to 23 steps) and builds on "reasoning is
  // output tokens too" while tokens are fresh.
  RoadmapLevel(
    "meta_models",
    "Модели Meta: кто есть кто",
    "У Meta не одна модель, а команда специалистов: Muse Spark, Muse Image, SAM, Llama. Кто за что отвечает и кто работает в этом приложении.",
    Icons.Default.Hub,
    titleEn = "Meta's models: who's who",
    descriptionEn = "Meta doesn't have just one model but a team of specialists: Muse Spark, Muse Image, SAM, Llama. Who does what, and which of them power this app.",
  ),
  RoadmapLevel(
    "muse_spark_modes",
    "Muse Spark: быстро или вдумчиво",
    "Думающие модели и режимы Muse Spark: зачем модели думать дольше, когда это того стоит и как она работает здесь.",
    Icons.Default.Speed,
    titleEn = "Muse Spark: fast or thoughtful",
    descriptionEn = "Thinking models and Muse Spark's modes: why a model would think longer, when it's worth it, and how it works here.",
  ),
  // In the first half of the path, not in the middle: the lesson itself introduces the context window (the block
  // moved here from "Tokens") and right away derives from it "the window is limited, the middle of a long chat
  // drops out, so what matters must be kept outside". Besides, "Privacy" below builds on the kinds of memory, and
  // it used to refer six lessons ahead.
  RoadmapLevel(
    "memory_and_context",
    "Память и контекст",
    "Контекстное окно, почему модель теряет нить длинного чата и как агент помнит тебя тремя видами памяти и профилем.",
    Icons.Default.Memory,
    titleEn = "Memory and context",
    descriptionEn = "The context window, why a model loses the thread of a long chat, and how an agent remembers you with three kinds of memory and a profile.",
  ),
  RoadmapLevel(
    "temperature_and_randomness",
    "Почему ответы разные",
    "Что такое температура и почему один и тот же запрос даёт разные ответы.",
    Icons.Default.Thermostat,
    titleEn = "Why answers vary",
    descriptionEn = "What temperature is and why the same request gets different answers.",
  ),
  // After temperature: "one prompt — a new picture" is the same randomness, only in drawing.
  RoadmapLevel(
    "muse_image_draws",
    "Как рисует Muse Image",
    "Модель Meta для картинок сначала планирует, потом рисует и проверяет. И почему один промпт каждый раз даёт новую картинку.",
    Icons.Default.Palette,
    titleEn = "How Muse Image draws",
    descriptionEn = "Meta's image model plans first, then draws and checks its work. And why the same prompt gives you a new picture every time.",
  ),
  RoadmapLevel(
    "privacy_and_reliability",
    "Приватность и надёжность",
    "Что уходит в облако, что остаётся у тебя, и что делать при сбоях.",
    Icons.Default.Shield,
    titleEn = "Privacy and reliability",
    descriptionEn = "What goes to the cloud, what stays with you, and what to do when something fails.",
  ),
  // After privacy: "what goes to the provider" is taken as far as "can I not hand it over at all".
  RoadmapLevel(
    "open_weights_and_api",
    "Открытые веса и API",
    "Где на самом деле живёт модель: на твоём сервере или у вендора. От этого зависит, кто видит твои данные и кто делает работу.",
    Icons.Default.Storage,
    titleEn = "Open weights and APIs",
    descriptionEn = "Where a model really lives: on your server or with the vendor. That decides who sees your data and who does the work.",
  ),
  RoadmapLevel(
    "hallucinations",
    "Когда модель ошибается",
    "Почему модель уверенно выдумывает и как ловить такие ответы.",
    Icons.Default.TrackChanges,
    titleEn = "When the model gets it wrong",
    descriptionEn = "Why a model confidently makes things up, and how to catch those answers.",
  ),
  RoadmapLevel(
    "workflow_and_agent",
    "Воркфлоу и агент",
    "Два способа выстроить многошаговую задачу: заранее заданный воркфлоу и самостоятельный агент — когда брать какой.",
    Icons.Default.AccountTree,
    titleEn = "Workflows and agents",
    descriptionEn = "Two ways to set up a multi-step task: a predefined workflow or an autonomous agent — and when to use which.",
  ),
  // Right after "Workflow and agent": chain and routing are typical workflow bricks, and the lesson continues it
  // mid-sentence ("a workflow has typical bricks"). It used to stand at the end of the path, a dozen lessons later.
  RoadmapLevel(
    "chaining_and_routing",
    "Паттерны: цепочка и роутинг",
    "Два базовых паттерна воркфлоу: промпт-чейнинг и роутинг задачи.",
    Icons.AutoMirrored.Filled.AltRoute,
    titleEn = "Patterns: chaining and routing",
    descriptionEn = "Two basic workflow patterns: prompt chaining and task routing.",
  ),
  RoadmapLevel(
    "react_loop",
    "Цикл агента: ReAct",
    "Как агент работает внутри: рассуждай → действуй → наблюдай, и так по кругу — на живом примере.",
    Icons.Default.Loop,
    titleEn = "The agent loop: ReAct",
    descriptionEn = "How an agent works inside: reason → act → observe, round and round — on a live example.",
  ),
  RoadmapLevel(
    "agent_tools",
    "Инструменты агента",
    "Чем агент действует: как модель вызывает инструменты и получает результат обратно.",
    Icons.Default.Build,
    titleEn = "Agent tools",
    descriptionEn = "How an agent takes action: how the model calls tools and gets the results back.",
  ),
  // Skills come before context engineering: that lesson assembles the preprompt from "already familiar" layers,
  // and the skill catalog is among them. The order used to be the reverse, and the lesson referred to something
  // not yet covered.
  RoadmapLevel(
    "agent_skills",
    "Навыки агента",
    "Как агент подгружает умения по требованию, не раздувая контекст — три уровня раскрытия.",
    Icons.Default.Extension,
    titleEn = "Agent skills",
    descriptionEn = "How an agent loads skills on demand without bloating the context — three levels of disclosure.",
  ),
  // Goes AFTER memory and skills: the lesson builds on the context window ("space in the window is limited", "the
  // middle of a long conversation drops out"), on relevant facts from memory and on the skill catalog. On the path
  // the order is a promise, and referring ahead is not allowed.
  RoadmapLevel(
    "context_engineering",
    "Контекст-инжиниринг",
    "Как перед каждым ответом собирается препромпт: слои, отбор, бюджет и защита от подмены.",
    Icons.Default.Layers,
    titleEn = "Context engineering",
    descriptionEn = "How the pre-prompt is assembled before every answer: layers, selection, budget and protection against tampering.",
  ),
  // "RAG" is removed from the PATH and kept only in the library (LIBRARY_LESSON_IDS): the lesson itself honestly
  // says three times that the app does not have this technique yet, and its "practice" is an exercise on paper.
  // On the path every lesson ends with an action in the app; bring it back here when document search appears.
  // Confirmation comes before hooks: the lesson introduces the default rule (sending and deleting — only with an
  // "ok"), and hooks add their own rules on top of it.
  RoadmapLevel(
    "tool_approval_and_limits",
    "Подтверждение инструментов",
    "Какие действия агент делает сам, а какие только с твоего «ок»: классы риска, оценка по каждому вызову, правило по умолчанию и хуки.",
    Icons.Default.Shield,
    titleEn = "Tool approval",
    descriptionEn = "Which actions an agent takes on its own and which only with your “OK”: risk classes, per-call assessment, the default rule and hooks.",
  ),
  // Hooks and the builder come BEFORE the modes: the practice of plan mode, goal mode and plans happens on your own
  // agent from the builder (a plain chat has no "Plan" and "Goal"), and the builder has a "Hooks" step.
  RoadmapLevel(
    "hooks_and_lifecycle",
    "Хуки и жизненный цикл",
    "Правила «на событие хода — сделай» без кода: события, действия и приоритет запрета.",
    Icons.Default.Bolt,
    titleEn = "Hooks and lifecycle",
    descriptionEn = "No-code rules of the form “when this happens in a turn, do that”: events, actions and why a block always wins.",
  ),
  RoadmapLevel(
    "build_your_agent",
    "Собери своего агента",
    "Как все части курса складываются в собственного агента через конструктор из шести шагов.",
    Icons.Default.Build,
    titleEn = "Build your own agent",
    descriptionEn = "How all the parts of the course come together into your own agent in a six-step builder.",
  ),
  RoadmapLevel(
    "plan_mode",
    "План-мод",
    "Агент сначала строит план и ждёт твоего «ок» — «подумать» отдельно от «сделать».",
    Icons.Default.Checklist,
    titleEn = "Plan mode",
    descriptionEn = "The agent builds a plan first and waits for your “OK” — “thinking” kept separate from “doing.”",
  ),
  RoadmapLevel(
    "goal_mode",
    "Гоал-мод",
    "Задаёшь цель и критерии — агент сам идёт к ней по шагам, а каждый шаг закрывается доказательством.",
    Icons.Default.Flag,
    titleEn = "Goal mode",
    descriptionEn = "You set a goal and criteria — the agent works toward it step by step, and each step is closed with evidence.",
  ),
  RoadmapLevel(
    "structured_plans",
    "Структурированные планы",
    "Большая задача как список проверяемых шагов: одобрение плана, статусы и правило «сделал — докажи».",
    Icons.Default.Checklist,
    titleEn = "Structured plans",
    descriptionEn = "A big task as a list of verifiable steps: plan approval, statuses and the “done it? prove it” rule.",
  ),
  // Reflection is an agent mode just like plan mode and goal mode, and it REALLY exists in the app (a chip above the
  // input field). So its place is here, among the modes, not only in the library.
  RoadmapLevel(
    "reflection",
    "Рефлексия",
    "Агент сначала пишет черновик, потом критикует его и переписывает — цикл ради качества.",
    Icons.Default.AutoAwesome,
    titleEn = "Reflection",
    descriptionEn = "The agent writes a draft first, then critiques and rewrites it — a loop for the sake of quality.",
  ),
  RoadmapLevel(
    "heartbeat_and_cron",
    "Heartbeat и cron",
    "Как агент живёт без твоих сообщений: расписания, фоновые пробуждения и право молчать.",
    Icons.Default.Alarm,
    titleEn = "Heartbeat and cron",
    descriptionEn = "How an agent lives without your messages: schedules, background wake-ups and the right to stay quiet.",
  ),
  // The arena comes last before the capstone: the capstone builds on "a judge model, as in the arena" and sums up
  // "from tokens to a swarm of agents".
  RoadmapLevel(
    "arena_and_swarm",
    "Арена и рой агентов",
    "Несколько прогонов или агентов сразу: сравнить лучший ответ и поделить задачу.",
    Icons.Default.Groups,
    titleEn = "Arena and agent swarms",
    descriptionEn = "Several runs or agents at once: compare them for the best answer and split up the task.",
  ),
  RoadmapLevel(
    "agent_blueprint",
    "Капстоун: спроектируй агента",
    "Финал курса: чеклист из пяти вопросов — цель, инструменты, паттерн, память, проверка.",
    Icons.Default.Architecture,
    titleEn = "Capstone: design an agent",
    descriptionEn = "The course finale: a five-question checklist — goal, tools, pattern, memory, testing.",
  ),
)

/**
 * Lessons that are in the library but not on the path. Their name is repeated here, as a ru/en pair, for the
 * places that have no lesson JSON at hand; the Russian one is the JSON's own title and a test keeps them equal.
 */
private val LIBRARY_ONLY_LESSON_TITLES: Map<String, Pair<String, String>> = mapOf(
  "rag" to Pair("RAG: ответы по твоей базе", "RAG: answers from your own data"),
)

/**
 * The name of a lesson in the interface language by its id (`what_is_agent` -> "What AI is and what an agent
 * is"), or null if the id is not a lesson the app knows.
 */
fun lessonDisplayTitle(lessonId: String): String? =
  ROADMAP_LEVELS.find { it.id == lessonId }?.title
    ?: LIBRARY_ONLY_LESSON_TITLES[lessonId]?.let { (ru, en) -> tr(ru, en) }

enum class LevelStatus { LOCKED, CURRENT, COMPLETED }
