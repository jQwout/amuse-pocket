# Техническое задание: Android AI-Agent

> ⏸️ **DivKit отложен до v2 (2026-06-15).** В v1 «Driven UI» = предзаполненные Compose-виджеты (`render_card` → `DrivenUiEngine`) + предзаполненные «апы» как композиции этих виджетов. Упоминания DivKit (§5.2 pipeline, §7 стек, Приложение B, ProGuard-правила) читать как post-v1. Остальной TZ — персоны, память, тулы, уведомления, фон — актуален.

> **Версия:** 0.2 · **Дата:** 2026-06-04 · **Статус:** pre-implementation
> **Платформа:** Android only · **Язык:** Kotlin · **Стиль:** idiomatic Kotlin + Coroutines

---

## Содержание

0. [TL;DR](#0-tldr)
1. [Продукт и цели](#1-продукт-и-цели)
2. [Целевая аудитория и сценарии](#2-целевая-аудитория-и-сценарии)
3. [Agent Persona & Character](#3-agent-persona--character)
4. [Identity (имя, визуал, голос)](#4-identity-имя-визуал-голос)
5. [Ключевая фича — Driven UI](#5-ключевая-фича--driven-ui)
6. [Архитектура](#6-архитектура)
7. [Стек](#7-стек)
8. [Память](#8-память)
9. [MCP tools](#9-mcp-tools)
10. [LLM Integration](#10-llm-integration)
11. [Identity & Security](#11-identity--security)
12. [UI Shell](#12-ui-shell)
13. [Уведомления](#13-уведомления)
14. [Background](#14-background)
15. [Жизненный цикл сессии](#15-жизненный-цикл-сессии)
16. [Деплой и дистрибуция](#16-деплой-и-дистрибуция)
17. [**Стейджи реализации**](#17-стейджи-реализации) ← основное
18. [Риски и митигации](#18-риски-и-митигации)
19. [Referents / Inspiration](#19-referents--inspiration)
20. [Открытые вопросы](#20-открытые-вопросы)
21. [Глоссарий](#21-глоссарий)

---

## 0. TL;DR

Локально-первый Android-агент. На устройстве — память, файлы, identity, MCP-тулы. В облако уходит только промпт. Главная фича: **Driven UI** — LLM на лету собирает маленькие приложения через шаблоны DivKit, которые рендерятся нативно без WebView.

Реализация — 8 стейджей, ~10-12 недель до production-ready v1.0.

---

## 1. Продукт и цели

### 1.1 Что это

Android-приложение, агент-ассистент уровня Hermes / OpenClaw / Open Interpreter, но:

- **Локально-первый** — все данные, история, identity, файлы хранятся на устройстве
- **Driven UI из коробки** — LLM может отрендерить UI-карточку (todo, weather, таймер, график, форму) прямо в чате, без перезапуска экрана
- **MCP-тулы** — файлы, уведомления, WorkManager, БД, память, web — как стандартизированный набор tools
- **Сменяемый характер** — юзер выбирает одну из 7 персон, переключается в Settings без перекомпиляции

### 1.2 Цели MVP (v1.0)

> Статус на 2026-06-13: ✅ готово · 🟡 частично · ❌ нет.

- [x] ✅ Стриминг-чат с LLM (OpenAI-compat, ≥1 провайдер) — Ktor SSE, `OpenAiCompatClient`
- [ ] 🟡 15+ работающих MCP-тулов — 7 «action-based» тулов / ~13 функций; нет `web_search`, `web_fetch`
- [ ] 🟡 Минимум 6 шаблонов карточек — 6 шаблонов есть, но рендерятся как **markdown** (`render_card → DrivenUiCard.toMarkdown`), DivKit пока не используется
- [x] ✅ 3 слоя памяти (short / long / episodic) с восстановлением сессии — Room
- [ ] ❌ Семантический поиск по памяти — только structured (`LIKE`)
- [ ] 🟡 Уведомления с action-кнопками — уведомления и планировщик есть; action-кнопок и 3 каналов нет
- [x] ✅ Background-задачи через WorkManager
- [x] ✅ Зашифрованный keystore для секретов — `EncryptedSharedPreferences`
- [ ] ❌ JSON Schema валидация аргументов тулов / рендеров
- [x] ✅ Multi-provider fallback (NIM → Groq → GitHub Models)
- [x] ✅ Сменяемая persona (7 персон в коробке)
- [ ] 🟡 Базовые тесты — 18 unit-тестов по core; integration/UI пока нет

### 1.2.1 Реализовано сверх ТЗ (на 2026-06-13)

- **Пакет:** `com.example` → `com.aiopenpocket`
- **Стек обновлён:** Kotlin 2.3.0, Compose BOM 2026.05, DI — **Metro** (не Hilt), markdown — **mikepenz** multiplatform-markdown-renderer
- **Закрепление сообщений (пины):** long-press в чате → закрепить/открепить, бейдж на пузыре, панель «Закреплённые (N)» сверху (Room v5, `MessageEntity.isPinned`)
- **render_card → markdown:** карточки (todo / table / weather / chart / timer / form) рендерятся как markdown вместо виджетов; DivKit отложен (зависимость проверена и убрана)
- **AI Academy:** онбординг, «путь агента» из 7 уроков, markdown-теория на странице урока, библиотека
- **Управление контекстом LLM:** `ContextWindowBuilder` (head/tail-окно) + `ConversationSummarizer` (сжатие середины)
- **Multi-chat:** список диалогов и переключение
- **Выбор провайдера в Settings** реально влияет на запрос (протянут в `streamChat`)
- **Tool trace UI:** сворачиваемая панель вызовов инструментов

### 1.3 Non-goals (v1)

- iOS / KMP
- Multi-user / профили
- Sync между устройствами
- Платежи / подписки
- Собственный LLM-хостинг
- Кастомный рендерер DivKit (используем SDK)
- Голосовой ввод (v1.1)
- Vision / мультимодальность (v1.1+)
- Поддержка кастомных пользовательских темплейтов (v1.1)

---

## 2. Целевая аудитория и сценарии

### 2.1 Пользовательские персоны

- **Solo-разработчик** — автоматизировать рутину, держать контекст проекта в кармане
- **Продвинутый пользователь** — приватность, локальные данные, кастомные workflow
- **Исследователь / студент** — заметки, референсы, периодические задачи

### 2.2 Ключевые сценарии

| # | Сценарий | Путь |
|---|---|---|
| 1 | «Поставь напоминание на завтра в 9:00 проверить почту» | `schedule_task` + `send_notification` |
| 2 | «Покажи мои задачи на сегодня» | `recall_episodes(today, type=task)` → `render_card(todo_list)` |
| 3 | «Что мы обсуждали вчера про запуск?» | `recall_episodes(yesterday)` + `search_memory` |
| 4 | «Найди файл budget.xlsx и прочитай первый лист» | `read_file` + `render_card(table)` |
| 5 | «Запомни: я предпочитаю короткие ответы» | `remember_fact(response_style, short)` |
| 6 | «Покажи погоду в Москве на 3 дня» | `web_fetch` → `render_card(weather_card)` |
| 7 | «Каждый понедельник в 10:00 — отчёт о новых задачах» | `schedule_task(cron)` + фоновый worker |
| 8 | «Нарисуй график моих расходов за май» | `read_file` → `render_card(chart_bar)` |

---

## 3. Agent Persona & Character

### 3.1 Зачем это

Агент — это то, с чем юзер живёт каждый день. Характер определяет половину UX. Поэтому персона = **отдельный конфигурируемый слой**, не вшитый в код.

### 3.2 Устройство

System prompt собирается из двух частей:
```
[system_base.md]      // тулы, безопасность, формат — всегда
        +
[personas/<id>.md]    // характер, тон, принципы — выбирается юзером
        ↓
[final system prompt → LLM]
```

### 3.3 Дефолт

**Persona #3 — «Резкий напарник»** (выбран по умолчанию, совпадает с тем, как юзер предпочитает общаться: короткий, по делу, с полезным follow-up).

Меняется в `SettingsScreen → Persona` без перекомпиляции.

### 3.4 Семь персон v1

Все 7 лежат в `:app/src/main/assets/personas/`. Юзер видит превью в Settings и переключается одной кнопкой.

| # | ID | Имя | Тон | Пример ответа на «поставь напоминание на 9:00» |
|---|---|---|---|---|
| 1 | `silent_butler` | Тихий дворецкий | сухой | «Готово.» |
| 2 | `attentive_secretary` | Внимательный секретарь | вежливый | «9:00 завтра, верно? WorkManager — ±15 мин, зависит от doze. Ставлю так?» |
| 3 | `sharp_partner` | **Резкий напарник** ⭐ default | деловой | «Поставил. Могу утром собрать непрочитанное в уведомление. Сделать?» |
| 4 | `egalitarian_partner` | Партнёр-эгалитарист | ровный | «WorkManager — 9:00 ± 15 мин. Точность = foreground service, но батарея. Что важнее?» |
| 5 | `friendly_companion` | Дружелюбный компаньон | тёплый | «Окей, поставила на 9:00 🙂 Давно не виделись — как выходные?» |
| 6 | `ironic_assistant` | Ироничный ассистент | сухой-ироничный | «Поставил. Утром получишь уведомление "проверить почту". Ритуал есть ритуал.» |
| 7 | `strict_coach` | Строгий коуч | прямой | «Поставил. Но ты вчера тоже ставил — и что? Что реально хочешь утром?» |

Подробные диалоги и принципы каждой персоны — в [Приложении D](#приложение-d-образцы-персон).

### 3.5 Принципы, общие для всех персон

Зашиты в `system_base.md` и не меняются при переключении:

- **Делай, не рассказывай.** Можешь вызвать тулу — вызывай, не пиши «щас проверю»
- **Показывай работу.** Tool trace виден — это фича
- **Краткость — дефолт.** Развёрнуто — только по запросу
- **Не ври.** «Не нашёл» / «не знаю» — нормальный ответ
- **Один точечный вопрос лучше трёх общих**
- **Подтверждай деструктивное** — удаление файла, массовая рассылка уведомлений
- **Локально-первый** — если данные есть в памяти, не лезь в веб
- **Не свети внутренности** — никаких ID, токенов, путей в чате

### 3.6 Тон для русского

- «Ты», не «Вы» (личный агент)
- Без канцелярита: «поставлю», не «осуществлю»
- Допустимы лёгкие связки: «кстати», «хм», «честко говоря»
- Без «с удовольствием помогу» — пустые слова
- Эмодзи — только если юзер сам начал (исключение: persona #5)

### 3.7 Расширение в v1.1+

- Юзер может создавать свои персоны в Settings (markdown-редактор + превью)
- A/B-режим: разные персоны для разных категорий задач
- Импорт/экспорт персон как .md файлов

---

## 4. Identity (имя, визуал, голос)

### 4.1 Что входит

| Аспект | Решение | Статус |
|---|---|---|
| **Имя агента** | TBD (предлагаю: Hermes / Nova / Atlas / без имени) | нужен выбор |
| **Аватар** | TBD (абстрактный / животное / робот / текст-логотип) | нужен выбор |
| **Голос** (v1.1) | TBD (женский тёплый / мужской нейтральный / без голоса) | v1.1 |
| **Цветовая палитра** | TBD (синий / зелёный / монохром / dynamic Android 12+) | нужен выбор |
| **App icon** | Создам через `image_synthesize`, ~5 концептов | можно сделать |
| **Splash экран** | Имя + аватар + dynamic color | авто от Identity |

### 4.2 Где используется

- App launcher icon
- Splash screen
- Toolbar / TopAppBar в ChatScreen
- Settings header
- Notification icon (монохромная версия)
- (v1.1) TTS voice preset

### 4.3 Если имя = "без имени"

Агент обращается к себе в третьем лице или безлично: «Готово», «Сделано», «Записал». В системном промпте явно указано не использовать «я».

---

## 5. Ключевая фича — Driven UI

### 5.1 Принцип

LLM **не рендерит UI с нуля**. Он выбирает **шаблон** (`todo_list`, `weather_card`, `timer`, `table`, `chart_bar`, `form`) и заполняет параметры. Это даёт:

- **Безопасность** — структура фиксирована, нет произвольного JSON от LLM
- **Скорость** — нет долгого «рисования» экрана
- **Предсказуемость** — UX-гарантии для каждого темплейта
- **Дебагабельность** — финальный JSON можно показать, проверить, переиспользовать

### 5.2 Pipeline

```
[User message]
     ↓
[LLM → tool_call: render_card(template="todo_list", params={...})]
     ↓
[render_card executor]
  1. Загрузить assets/divkit/templates/todo_list.json
  2. Подставить params в placeholders ({$title}, {$items})
  3. Валидировать по JSON Schema
  4. Вернуть спецификацию в tool_result
     ↓
[ChatScreen монтирует DivKit view внутри bubble сообщения]
```

### 5.3 Безопасность рендера

- Каждый шаблон имеет **JSON Schema** в `data/schemas/`
- Перед рендером — schema validation
- Если LLM вернул битый JSON — fallback на текстовое представление (Markdown-список)
- LLM **не может** вызвать рендер произвольной DivKit-спеки — только через зарегистрированный шаблон

### 5.4 Список шаблонов v1

| ID | Назначение | Параметры | Стейдж |
|---|---|---|---|
| `todo_list` | Список с чекбоксами | `title`, `items[]` | Stage 1 |
| `weather_card` | Прогноз на N дней | `city`, `days[]` | Stage 5 |
| `timer` | Таймер с кнопкой старт | `label`, `duration_sec` | Stage 5 |
| `table` | Простая таблица | `headers[]`, `rows[][]` | Stage 5 |
| `chart_bar` | Столбчатая диаграмма | `title`, `labels[]`, `values[]` | Stage 5 |
| `form` | Форма ввода | `fields[]`, `submit_label` | Stage 5 |

---

## 6. Архитектура

### 6.1 Модули (Gradle multi-module)

```
agent/
├── app/                        # Application, DI graph, Compose shell, persona UI
├── core/
│   ├── llm/                    # LlmClient (OpenAI-compat), streaming, tools
│   ├── mcp/                    # Tool registry, executor, JSON Schema
│   ├── divui/                  # DivKit обёртка, safe-render
│   ├── memory/                 # Room DB, short/long/episodic
│   └── identity/               # Keystore, EncryptedSharedPrefs
├── tools/
│   ├── render/                 # render_card
│   ├── files/                  # read/write/list/search
│   ├── notifications/          # send_notification
│   ├── work/                   # schedule_task
│   ├── db/                     # query_db, insert_db
│   ├── web/                    # web_search, web_fetch
│   └── memory-tools/           # remember_fact, search_memory, recall_episodes
├── data/                       # assets/templates, schemas, prompts
└── gradle/libs.versions.toml   # version catalog
```

### 6.2 Поток данных

```
ChatViewModel
   ├─→ LlmClient.stream(messages, tools)  ──SSE──→  LLM provider
   │                                              ↑
   │                                              tool_calls
   │                                              ↓
   ├─→ ToolRegistry.execute(name, args)  ──→  Tool (MCP)
   │                                              ↓
   │                                          ToolResult
   │                                              ↓
   └─→ Message { text?, divKitSpec?, toolTrace[] }
        ↓
   ChatScreen (Compose)
        ↓
   MessageBubble
        ├─→ text (Markdown)
        └─→ DivKitAndroidView (если есть spec)
```

### 6.3 Ключевые инварианты

1. LLM не имеет прямого доступа к UI — только через `render_card` tool
2. Tool executor всегда возвращает `ToolResult` (даже при ошибке) — LLM видит failure и адаптируется
3. Каждый tool_call пишется в `episodes` — это даёт аудит-трейл
4. Никаких `runBlocking` в main thread
5. Никаких `GlobalScope` — только структурная конкуррентность

---

## 7. Стек

| Слой | Технология | Версия |
|---|---|---|
| Язык | Kotlin | 2.1.x |
| UI shell | Jetpack Compose | BOM 2026.05.x |
| DI | Hilt | 2.53.x |
| Async | Coroutines + Flow | 1.9.x |
| HTTP / SSE | OkHttp | 4.12.x |
| Сериализация | kotlinx.serialization | 1.7.x |
| Локальная БД | Room | 2.6.x |
| Vector search | sqlite-vec-android | TBD (FTS4 fallback Stage 7) |
| Secure storage | androidx.security | 1.1.0-alpha |
| Background | WorkManager + hilt-work | 2.9.x |
| JSON Schema | networknt/json-schema-validator | 1.5.x |
| Markdown | mikepenz multiplatform-markdown-renderer (m3) | 0.41.x |
| Driven UI | Yandex DivKit | 29.4.x |
| Min SDK | Android 8 (API 26) | — |
| Target SDK | Android 15 (API 35) | — |
| Build | Gradle KTS + version catalog | 8.7+ |

---

## 8. Память

### 8.1 Short-term (текущий диалог)

- **Что:** сообщения текущей сессии + последние N сессий
- **Хранение:** Room `conversations` + `messages`, `session_id`
- **In-memory:** кэш в `ChatViewModel` (`StateFlow<List<Message>>`)
- **Восстановление:** при запуске подгружаем активную `session_id`

### 8.2 Long-term semantic (факты о юзере)

- **Что:** факты, предпочтения, профиль
- **Хранение:** Room `facts` (structured) + `fact_embeddings` (sqlite-vec, Stage 7)
- **Пример:** `{"key": "response_style", "value": "short", "category": "preferences"}`
- **Запись:** через `remember_fact` tool
- **Поиск:** семантический + structured (`"стиль ответов" → response_style=short`)

### 8.3 Episodic (что делали когда)

- **Что:** event log — все tool_calls, результаты, заметки
- **Хранение:** Room `episodes` (`timestamp`, `type`, `payload`, `outcome`, `session_id`)
- **Пример:** `{ts: 2026-06-02T18:30, type: "tool_call", tool: "render_card", outcome: "ok"}`
- **Запрос:** `recall_episodes(range=today|yesterday|last_week, type=...)`

### 8.4 Контекст для LLM

При каждом запросе LLM получает:

- Short-term: последние 20 сообщений активной сессии
- Relevant facts: top-3 по текущему запросу (семантический поиск)
- Relevant episodes: top-3 за сегодня / вчера

Лимиты: 8K context у free-tier → нужна компрессия (truncation + summary для старых сообщений).

---

## 9. MCP tools

| Tool | Inputs | Output | Реализация | Стейдж |
|---|---|---|---|---|
| `read_file` | `path` | `string` | `context.filesDir/agent/` | 1 |
| `write_file` | `path`, `content` | `string` | sandbox в internal storage | 1 |
| `list_dir` | `path` | `string[]` | — | 1 |
| `search_files` | `query` | `string[]` | FTS4 по именам | 1 |
| `send_notification` | `title`, `body`, `actions[]` | `string` | NotificationManager | 4 |
| `schedule_task` | `name`, `trigger_at`/`cron`, `payload` | `string` | WorkManager | 4 |
| `cancel_task` | `name` | `string` | WorkManager | 4 |
| `query_db` | `sql`, `params[]` | `string` | Room raw query, sandbox | 6 |
| `insert_db` | `table`, `values` | `string` | Room, whitelist таблиц | 6 |
| `remember_fact` | `key`, `value`, `category?` | `string` | memory layer | 2 |
| `search_memory` | `query`, `mode` | `string[]` | memory layer | 2 / 7 |
| `recall_episodes` | `range`, `type?` | `string[]` | memory layer | 2 |
| `render_card` | `template`, `params` | `string (JSON)` | divui layer | 1 |
| `web_search` | `query` | `string[]` | внешний API | 6 |
| `web_fetch` | `url` | `string` | OkHttp + parse | 6 |

---

## 10. LLM Integration

### 10.1 Провайдеры

- **Primary:** NVIDIA NIM (`build.nvidia.com`) — OpenAI-совместимый, free tier, RF-friendly
- **Fallback #1:** Groq
- **Fallback #2:** GitHub Models
- **Модели по умолчанию:**
  - Планирование / диалог / tool calling: `meta/llama-3.1-70b-instruct`
  - Embeddings: `nvidia/nv-embedqa-e5-v5` (для памяти, Stage 7)

### 10.2 Конфигурация

- Base URL, API key — в `EncryptedSharedPreferences`
- Endpoint переключается на лету (провайдер / модель)
- Таймауты: connect 10s, read 60s, write 60s

### 10.3 Стриминг

- SSE через OkHttp `EventSource`
- Преобразование в `Flow<StreamChunk>`
- Chunks: `text-delta`, `tool-call`, `finish`, `error`

### 10.4 Function calling

- OpenAI-compat формат: `tools: [{type: "function", function: {...}}]`
- `tool_choice: "auto"` по дефолту
- Возвращается в потоке как `tool_calls` deltas
- Multi-step loop: tool_result → LLM → tool_call → ... → final text

### 10.5 Ограничения и fallback

- Free tier: 40 RPM, лимиты по токенам
- Retry с exponential backoff (max 3)
- При превышении лимита / 5xx — fallback на следующий провайдер
- При недоступности всех — `ToolResult.error` для пользователя

---

## 11. Identity & Security

### 11.1 Хранение секретов

- API-ключи LLM — `EncryptedSharedPreferences` (AES-256)
- Master key — Android Keystore (TEE / StrongBox если доступно)
- Никаких ключей в логах, коде, git

### 11.2 Локальные данные

- Все файлы — в `context.filesDir/agent/`
- База — внутренняя Room; шифрование SQLCipher (Stage 8, опц.)
- Логи — только в debug build, без PII

### 11.3 Сетевой трафик

- HTTPS only
- TLS pinning для LLM-провайдера (опц., Stage 8)
- Никакой телеметрии в v1
- Все исходящие запросы — только к LLM и web-тулам; никаких third-party аналитик

### 11.4 Sandboxing tool-ов

- `read_file` / `write_file` — только внутри `filesDir/agent/`, отказ при выходе за пределы
- `query_db` / `insert_db` — whitelist таблиц, prepared statements
- `web_fetch` — whitelist доменов (опц., Stage 8)

---

## 12. UI Shell

### 12.1 Экраны

- **`ChatScreen`** — основной: сообщения + input + send; закрепление сообщений (пины) ✅
- **`ConversationsListScreen`** — список чатов, переключение ✅
- **`OnboardingScreen`** — выбор персоны и профиль ✅
- **`RoadmapScreen` / `LessonScreen` / `LibraryScreen`** — AI Academy: путь обучения, уроки с markdown-теорией, библиотека ✅
- **`SettingsScreen`** — провайдеры, API-ключи, persona picker ✅
- **`ScheduledWorksScreen`** — запланированные фоновые задачи ✅
- **`MemoryScreen`** *(Stage 7, не сделан)* — просмотр фактов и эпизодов
- **`TemplatesScreen`** *(Stage 5, не сделан)* — список доступных шаблонов с превью

### 12.2 MessageBubble

- **Текст:** Markdown рендер (через `mikepenz multiplatform-markdown-renderer`)
- **Карточки:** `render_card` рендерится как markdown (таблицы / чеклисты), не DivKit-виджет — DivKit отложен
- **Tool trace:** collapsible список вызовов (для дебага)
- **Пины:** long-press сообщения → закрепить/открепить; панель «Закреплённые» сверху чата

### 12.3 Стиль

- Material 3
- Light + Dark theme
- Системные шрифты (Roboto / Inter)
- Поддержка dynamic color (Android 12+)

---

## 13. Уведомления

### 13.1 Типы

- Результат background-задачи (worker завершился)
- Напоминание от `schedule_task`
- LLM-initiated с action buttons (открыть карточку / ответить)

### 13.2 Каналы

- `agent_default` — обычные
- `agent_reminders` — напоминания (высокий приоритет)
- `agent_results` — результаты фоновых задач

### 13.3 Действия

- Tap → открывает `ChatScreen` с контекстом (deep link на сообщение)
- Action button → выполняет тулу / пишет в episodic

---

## 14. Background

### 14.1 WorkManager

- **One-time:** `schedule_task(trigger_at=...)` — точное время срабатывания
- **Periodic:** минимум 15 минут (ограничение Android)
- **Constraints:** network, battery, charging

### 14.2 Ограничения

- В фоне LLM-вызовы могут зависнуть на таймаут → не запускаем LLM из worker
- Worker пишет в episodic / notifications, юзер открывает приложение → видит результат
- Для длинных задач (sync, embedding) — foreground service

### 14.3 Job types

- `ReminderJob` — показать уведомление
- `ScheduledQueryJob` — выполнить тулу в фоне (например, периодический `web_fetch`)
- `EmbeddingJob` — обновить embeddings для новых фактов / эпизодов (Stage 7)

---

## 15. Жизненный цикл сессии

### 15.1 Старт

1. Загрузить активную `session_id` (или создать новую)
2. Подтянуть short-term память (последние 20 сообщений)
3. Опционально: top-3 relevant facts/episodes по текущему времени суток

### 15.2 Во время работы

- Каждое сообщение → `conversations` row
- Каждый `tool_call` → `episodes` row
- Каждый `remember_fact` → `facts` row + (опц.) embedding job

### 15.3 Завершение

- Session закрывается по таймауту (30 мин idle) или вручную
- Эпизоды остаются; short-term память остаётся в БД, но становится неактивной
- При возврате юзера — продолжение в новой сессии с reference на старую

---

## 16. Деплой и дистрибуция

### 16.1 Сборка

- `assembleDebug` — debug APK
- `bundleRelease` — AAB для Play Store
- R8 + resource shrinking в release
- ProGuard rules для DivKit, Room, kotlinx.serialization

### 16.2 Дистрибуция

- **Google Play** — Internal Testing → Closed → Open
- **GitHub Releases** — APK для sideload
- **F-Droid** — если open-source

### 16.3 Версионирование

- Semantic versioning: `MAJOR.MINOR.PATCH`
- `versionCode` инкремент в CI

---

## 17. Стейджи реализации

> 8 стейджей, ~10-12 недель до v1.0 production-ready.
> Stage 0 → 1 — последовательно. Stage 2-7 — можно частично параллелить после Stage 1. Stage 8 — в конце.

### Зависимости стейджей

```
Stage 0 (Foundation)
   ↓
Stage 1 (Engine spin) ← должен быть готов первым
   ↓
   ├── Stage 2 (Memory basic)
   ├── Stage 3 (Persona & Settings)
   ├── Stage 4 (Notifications + Work)
   ├── Stage 5 (More templates)
   └── Stage 6 (Web + DB)
            ↓
         Stage 7 (Memory 2.0 — embeddings)
            ↓
         Stage 8 (Production readiness)
```

---

### Stage 0 — Foundation · 3-5 дней

**Цель:** проект собирается, открывается пустой экран.

**Делаем:**
- Gradle multi-module setup (root + все модули как stubs)
- `gradle/libs.versions.toml` со всеми версиями из стека
- Hilt graph (Application + Activity)
- Compose shell: один экран с `Text("Agent ready")`
- `.gitignore`, `README.md`, baseline ProGuard rules
- `versionCode` / `versionName` в build.gradle
- Signing config (debug keystore автоматом, release — TBD)

**Готово когда:**
- `./gradlew assembleDebug` собирает APK без ошибок
- Установка на устройство/эмулятор → пустой экран с текстом
- `./gradlew test` проходит (даже если тестов нет)
- `./gradlew lint` без critical issues

---

### Stage 1 — Engine spin · 1-1.5 нед

**Цель:** LLM отвечает стримом, можно вызвать `render_card`, карточка рендерится.

**Делаем:**
- `:core:llm` — OpenAI-compat клиент, SSE streaming через OkHttp, function calling
- `:core:mcp` — tool registry, executor, JSON Schema для аргументов
- `:core:divui` — обёртка над DivKit, safe-render, валидация спеки
- `:core:identity` — `SecretsStore` через EncryptedSharedPrefs
- `:tools:render` — `render_card(template, params)` tool
- `:tools:files` — `read_file`, `write_file`, `list_dir` (для работы с sandbox)
- 1 темплейт: `todo_list.json` + schema
- `data/prompts/system_base.md` (правила тулов, безопасность)
- `app` — ChatScreen с потоковым выводом, MessageBubble (text + DivKit)
- `app` — SettingsScreen (минимальный: API key + выбор провайдера)

**Готово когда:**
- В SettingsScreen вводишь API key для NVIDIA NIM
- В чате пишешь «покажи туду с пунктами X, Y, Z»
- LLM стримит текст + tool_call → `render_card`
- В bubble появляется DivKit-карточка с чекбоксами
- «Запиши в файл todo.txt пункты X, Y, Z» → `write_file` → файл создан в `filesDir/agent/`

---

### Stage 2 — Memory basic · 1 нед

**Цель:** помнит между сессиями, можно remember/recall.

**Делаем:**
- `:core:memory` — Room DB, entities: `Conversation`, `Message`, `Fact`, `Episode`
- `:core:memory` — DAO + Repository
- `:tools:memory-tools` — `remember_fact`, `search_memory(mode=structured)`, `recall_episodes`
- Short-term: каждое сообщение в БД, восстановление сессии при запуске
- Long-term structured: `facts` table (key/value/category)
- Episodic: автоматическая запись каждого `tool_call` + результата
- Контекст для LLM: последние 20 сообщений + top-3 facts по ключу

**Готово когда:**
- Закрыл/открыл приложение → предыдущий диалог подгрузился
- «Запомни: я люблю короткие ответы» → `remember_fact` → в новой сессии «как мне ответить?» → вспоминает
- В эпизодах видны все прошлые tool_calls (через debug-экран или дамп)

---

### Stage 3 — Persona & Settings · 3-5 дней

**Цель:** характер сменяемый, провайдеры переключаются, persona работает.

**Делаем:**
- Persona loader: `assets/personas/*.md` → загрузка в `PersonaRepository`
- SettingsScreen: persona picker с превью (название + тон + пример диалога)
- SettingsScreen: провайдер LLM picker (NIM / Groq / GitHub Models)
- Multi-provider fallback chain (приоритеты, exponential backoff)
- 7 черновиков персон (см. [Приложение D](#приложение-d-образцы-персон))
- System prompt = конкатенация `system_base.md` + выбранная persona

**Готово когда:**
- В Settings переключаешь persona — поведение агента меняется без перезапуска
- LLM-провайдер падает (заглушили endpoint) → автоматически идёт на fallback
- Persona 3 (default) — агент отвечает коротко, с follow-up предложениями
- Persona 7 (strict_coach) — агент иногда подсвечивает паттерны

---

### Stage 4 — Notifications + Work · 1 нед

**Цель:** напоминания, фоновые задачи.

**Делаем:**
- `:tools:notifications` — `send_notification(title, body, actions[])`
- `:tools:work` — `schedule_task(name, trigger_at|cron, payload)`, `cancel_task`
- Notification channels: `agent_default`, `agent_reminders`, `agent_results`
- WorkManager + hilt-work, Worker для `ReminderJob` и `ScheduledQueryJob`
- Deep link из уведомления → `ChatScreen` с контекстом (что произошло)
- `<receiver>` для action buttons (открыть карточку, отметить выполненным)

**Готово когда:**
- «Поставь напоминание на завтра в 9:00 проверить почту» → WorkManager scheduled
- В 9:00 приходит уведомление на канале `agent_reminders`
- Тап → открывает `ChatScreen` с контекстом «напоминание сработало»
- «Каждый понедельник в 10:00 — проверь почту» → periodic job создан

---

### Stage 5 — More templates · 3-5 дней

**Цель:** LLM умеет рендерить 5+ типов карточек.

**Делаем:**
- Темплейты: `weather_card`, `timer`, `table`, `chart_bar`, `form` + schemas
- Утилита `TemplateLoader` с кэшированием
- TemplatesScreen (Stage 5.5) — список с превью, toggle вкл/выкл
- Расширение tool definition `render_card` с описанием каждого темплейта (для LLM)
- Edge cases: очень длинные списки, специальные символы, эмодзи

**Готово когда:**
- «Покажи погоду в Москве» → `render_card(weather_card, {city: "Москва", days: [...]})` (даже с мок-данными)
- «Нарисуй график: январь 10, февраль 25, март 18» → `chart_bar` рендерится
- «Сделай форму для отзыва» → `form` с полями
- TemplatesScreen показывает все 6 темплейтов с живым превью

---

### Stage 6 — Web + DB · 1 нед

**Цель:** агент ходит в веб, работает с пользовательской БД.

**Делаем:**
- `:tools:web` — `web_search(query)` (через провайдера типа Serper / Brave API) и `web_fetch(url)` с Markdown-парсингом
- `:tools:db` — `query_db(sql, params[])` с whitelist таблиц, `insert_db(table, values)`
- Sandbox в DB: только таблицы, начинающиеся с `user_`
- Markdown parser (например, `commonmark-java`) для `web_fetch`

**Готово когда:**
- «Найди в интернете погоду в Москве на завтра» → `web_search` → `web_fetch` → `render_card(weather_card)` с реальными данными
- «Создай таблицу user_tasks и добавь задачу X» → `insert_db` → в новой сессии «покажи мои задачи» → `query_db` → `render_card(todo_list)`
- SQL injection в `query_db` не работает (prepared statements)

---

### Stage 7 — Memory 2.0 · 1-1.5 нед

**Цель:** семантический поиск по памяти.

**Делаем:**
- Embeddings pipeline: фоновый Worker, который эмбеддит новые facts/episodes
- Embedding endpoint: NVIDIA NIM `nv-embedqa-e5-v5`
- sqlite-vec интеграция (если стабильно для Android), иначе FTS4 fallback с BM25
- `search_memory(mode=semantic)` — векторный поиск + re-ranking
- MemoryScreen — UI для просмотра facts и episodes, ручное удаление

**Готово когда:**
- «Найди разговоры про запуск продукта» — возвращает релевантные эпизоды за неделю назад по смыслу, а не по точному слову «запуск»
- Embeddings считаются в фоне (не блокируют UI)
- Если NIM недоступен — fallback на FTS4 с warning в логах

---

### Stage 8 — Production readiness · ongoing (1-2 нед на MVP)

**Цель:** релиз-готовое состояние.

**Делаем:**
- SQLCipher (опц.) — шифрование БД на устройстве
- ProGuard rules финализированы для DivKit, Room, kotlinx.serialization
- Тесты:
  - Unit: каждая тула, memory repository, schema validation
  - Integration: LLM flow с mock-сервером, DivKit рендер из спеки
  - UI: Compose-тесты на ChatScreen
- CI/CD: GitHub Actions (lint + test + build APK на каждый PR)
- Crash reporting (опц.) — Sentry self-hosted
- Privacy policy (markdown, для Play Store)
- A11y проверка: TalkBack проходит по DivKit-карточкам
- App icon и splash screen (финальные ассеты)
- Store listing: screenshots, description, keywords

**Готово когда:**
- `bundleRelease` → подписанный AAB, готов к загрузке в Play Console
- Все тесты зелёные, coverage ≥ 60%
- APK size ≤ 25 MB
- Первый cold start ≤ 2 сек на mid-range устройстве

---

## 18. Риски и митигации

| Риск | Вероятность | Импакт | Митигация |
|---|---|---|---|
| LLM возвращает кривой DivKit JSON | Высокая | Средний | JSON Schema validation + safe-render fallback на текст |
| LLM-провайдер падает / лимиты | Средняя | Высокий | Multi-provider fallback chain (Stage 3) |
| Latency на free-tier > 5s | Высокая | Средний | Skeleton UI, streaming text, optimistic UX |
| Free-tier отключает нужные модели | Средняя | Высокий | Абстракция провайдера, plug-in новых |
| WorkManager periodic ≥ 15 мин | Известно | Низкий | Foreground service для длинных задач |
| sqlite-vec нет стабильной Android-сборки | Средняя | Средний | Room FTS4 fallback для Stage 7 |
| DivKit интеграция с Compose | Низкая | Низкий | `AndroidView` wrapper, проверено сообществом |
| Tool-calling модель галлюцинирует tool names | Средняя | Средний | Strict tool schema, явные имена, whitelist |
| Утечка API-ключа через backup | Низкая | Высокий | `android:allowBackup="false"` для keystore |
| Большой размер APK из-за DivKit | Средняя | Низкий | R8 + bundle, AAB delivery |
| Persona switch ломает контракт с LLM | Низкая | Средний | Persona = markdown, не код; покрыт тестами |
| WorkManager + Doze убивает job в фоне | Средняя | Средний | Foreground service для критичных напоминаний |
| Embedding cost на NIM превышает ожидания | Средняя | Низкий | Батчинг + on-device альтернатива (Stage 7.5) |

---

## 19. Referents / Inspiration

Проекты и протоколы, на которые опираемся при дизайне:

### OpenClaw (openclaw.ai, github: openclaw/openclaw)

Локально-первый AI-агент с фокусом на приватность. Оттуда берём:
- **A2UI protocol** — декларативный JSON для UI-компонентов, похож на DivKit концептуально
- **Skill = папка с SKILL.md** — формат описания capability для LLM (похож на MCP)
- **Soul / persona separation** — `SOUL.md` отдельно от системных правил, переключаемо

**Чем НЕ подходит:** OpenClaw — Node.js, бежит на PC, Android-поддержка только companion app. Для нашего Android-only кейса не годится как рантайм — годится как источник идей.

### A2UI (Agent-to-User Interface)

Протокол декларативного UI от OpenClaw. Сообщения:
- `beginRendering` — старт рендера
- `surfaceUpdate` — обновить компонент
- `dataModelUpdate` — обновить state (без перерендера)
- `deleteSurface` — убрать

**Для v1 используем DivKit** (battle-tested, есть Android SDK). A2UI держим в голове как fallback, если захочется кросс-платформу.

### Yandex DivKit (divkit.tech, github: divkit/divkit)

Driven UI фреймворк от Yandex. JSON-спек → нативный рендер на Android/iOS/Web. Используется в Яндекс Go, Еда, Маркет и др.

**Что берём:** готовый Android SDK, шаблоны с variables/actions, JSON Schema валидация через `networknt/json-schema-validator`.

### Open Interpreter / Hermes Agent

Референсные архитектуры агент-фреймворков. Оттуда — понимание **паттерна "тулы + память + LLM loop"**.

### Anthropic Skills (Claude Skills, конец 2025)

Формат `SKILL.md` + код + ресурсы. Концептуально похож на MCP, но текстовый.

---

## 20. Открытые вопросы

1. **Package name** — `com.mavis.agent`? или свой бренд?
2. **Primary LLM провайдер** — NVIDIA NIM как договорились? Есть уже API-ключ?
3. **Embeddings** — где хостить? (тоже через NIM, или on-device через ONNX Runtime?)
4. **Имя агента** — Hermes / Nova / Atlas / без имени / своё?
5. **Аватар / визуал** — есть идея? Если нет — нарисую 5 концептов через `image_synthesize`
6. **Цветовая палитра** — синий / зелёный / монохром / dynamic?
7. **Persona default** — оставляем #3 «Резкий напарник»? (можно поменять в Settings)
8. **Монетизация** — open-source, freemium, платно?
9. **Play Store публикация** — нужен ли аккаунт разработчика (25$ разово)?
10. **Multi-device sync** — точно нет? (для memory было бы удобно)
11. **Voice input** — нужна v1.1, не раньше?
12. **Кастомные шаблоны юзером** — позволять добавлять свои JSON, или только встроенные (v1.1)?
13. **Privacy policy** — нужна для Play Store, готовим шаблон?
14. **Crash reporting** — нужен ли? Если да — Sentry self-hosted или опенсорс альтернативы?
15. **A11y** — нужен ли voice-over / TalkBack для DivKit карточек в v1?

---

## 21. Глоссарий

- **MCP** — Model Context Protocol, стандарт описания tools для LLM
- **Driven UI** — UI, описанный декларативной спекой (JSON), отрендеренный на клиенте
- **DivKit** — Yandex Driven UI фреймворк (Android / iOS / Web)
- **A2UI** — Agent-to-User Interface, OpenClaw-протокол для декларативного UI
- **Tool calling** — механизм LLM, когда модель вместо текста возвращает вызов функции
- **Episodic memory** — память о событиях («что было вчера»)
- **Semantic memory** — память о фактах («юзер любит короткие ответы»)
- **Foreground service** — Android-сервис с уведомлением, может работать долго
- **Tool trace** — лог всех tool_calls сессии, для дебага и аудита
- **Persona** — характер агента, отдельный markdown-файл, подключаемый к system prompt
- **SSE** — Server-Sent Events, протокол стриминга от сервера к клиенту
- **Stage** — единица реализации в этом ТЗ, с чёткими «готово когда»

---

## Приложение A: пример system prompt (draft)

```
Ты — локальный AI-агент на Android-устройстве пользователя.

# Базовые правила (не меняются при смене персоны)

1. Если задача подразумевает визуальный результат — вызови render_card(template, params).
   Доступные шаблоны: todo_list, weather_card, timer, table, chart_bar, form.
   Не выдумывай шаблоны. Если ничего не подходит — ответь текстом.
2. Если задача требует контекста из прошлого — сначала search_memory или recall_episodes.
3. Если пользователь сообщает факт о себе — вызови remember_fact.
4. Файлы — только в песочнице (context.filesDir/agent/). Не выходи за пределы.
5. Уведомления и фоновые задачи — send_notification, schedule_task, cancel_task.
6. Если params для шаблона неполные — спроси пользователя текстом.
7. Отвечай кратко. Подробности — только по запросу.
8. Никогда не показывай внутренние ID, пути, токены.
9. Подтверждай деструктивное (удаление, массовые уведомления).
10. Локально-первый: если данные есть в памяти — не лезь в веб.

# Характер (подставляется из personas/<id>.md)

[здесь — содержимое выбранной персоны]
```

---

## Приложение B: пример DivKit шаблона (todo_list.json)

```json
{
  "templates": {
    "todo_item": {
      "type": "container",
      "orientation": "horizontal",
      "items": [
        {
          "type": "text",
          "text": "{$item_text}",
          "font_size": 16,
          "margins": { "right": 8 }
        },
        {
          "type": "checkbox",
          "checked": "{$item_done}"
        }
      ]
    }
  },
  "card": {
    "type": "container",
    "background": "#FFFFFF",
    "corner_radius": 12,
    "paddings": { "left": 16, "right": 16, "top": 12, "bottom": 12 },
    "items": [
      {
        "type": "text",
        "text": "{$title}",
        "font_size": 22,
        "font_weight": "bold",
        "margins": { "bottom": 12 }
      },
      {
        "type": "list",
        "items": "{$items}",
        "item_template": "todo_item"
      }
    ]
  }
}
```

---

## Приложение C: пример tool definition (render_card)

```json
{
  "type": "function",
  "function": {
    "name": "render_card",
    "description": "Рендерит UI-карточку из шаблона. Используй когда задача подразумевает визуальный результат: список, таблица, график, таймер, форма.",
    "parameters": {
      "type": "object",
      "properties": {
        "template": {
          "type": "string",
          "enum": ["todo_list", "weather_card", "timer", "table", "chart_bar", "form"],
          "description": "ID зарегистрированного шаблона"
        },
        "params": {
          "type": "object",
          "description": "Параметры шаблона (зависят от template)"
        }
      },
      "required": ["template", "params"]
    }
  }
}
```

---

## Приложение D: образцы персон

Полные черновики лежат в `:app/src/main/assets/personas/`. Здесь — сокращённые превью.

### 01 — Тихий дворецкий (`silent_butler`)

**Тон:** минимальный, утилитарный.

**Принципы:**
- Если можно сказать одним словом — скажет
- Не лезет с мнениями, не предлагает follow-up
- Результат важнее процесса

**Пример:**
- Юзер: «покажи туду с пунктами A, B, C»
- Агент: «Готово.» (рендерит карточку молча)

---

### 02 — Внимательный секретарь (`attentive_secretary`)

**Тон:** вежливый, уточняющий.

**Принципы:**
- Один точечный вопрос — да, десять — нет
- Подтверждает перед действием, если есть неоднозначность
- Описывает последствия выбора (trade-off)

**Пример:**
- Юзер: «поставь напоминание на 9:00»
- Агент: «9:00 завтра, верно? WorkManager — ±15 мин, зависит от doze. Ставлю так?»

---

### 03 — Резкий напарник ⭐ default (`sharp_partner`)

**Тон:** деловой, проактивный.

**Принципы:**
- Делает сразу, потом предлагает осмысленный follow-up
- Действие + полезный next step, всегда
- Не переспрашивает, если можно разумно предположить

**Пример:**
- Юзер: «поставь напоминание на 9:00»
- Агент: «Поставил. Могу утром собрать непрочитанное в уведомление. Сделать?»

---

### 04 — Партнёр-эгалитарист (`egalitarian_partner`)

**Тон:** ровный, уважительный к автономии.

**Принципы:**
- Показывает трейд-оффы, не давит
- Решения с реальными последствиями — только через юзера
- Не делает выбор за юзера, даже если «очевидно»

**Пример:**
- Юзер: «поставь напоминание на 9:00»
- Агент: «WorkManager — 9:00 ± 15 мин, зависит от doze. Точность = foreground service, но батарея. Что важнее?»

---

### 05 — Дружелюбный компаньон (`friendly_companion`)

**Тон:** тёплый, по-человечески.

**Принципы:**
- Агент — собеседник, а не cron
- Может поболтать, поинтересоваться делами
- Использует эмодзи умеренно (это исключение из общего правила)

**Пример:**
- Юзер: «поставь напоминание на 9:00»
- Агент: «Окей, поставила на 9:00 🙂 Давно не виделись — как выходные?»

---

### 06 — Ироничный ассистент (`ironic_assistant`)

**Тон:** сухой юмор, без подхалимства.

**Принципы:**
- Не принимает себя всерьёз
- Помогает без сахара
- Ирония — лёгкая, не злая

**Пример:**
- Юзер: «поставь напоминание на 9:00»
- Агент: «Поставил. Утром получишь уведомление "проверить почту". Почта никуда не денется, но ритуал есть ритуал.»

---

### 07 — Строгий коуч (`strict_coach`)

**Тон:** прямой, поднимает зеркало.

**Принципы:**
- Не позволяет использовать себя как костыль
- Иногда подсвечивает паттерны
- Помогает, но не даёт расслабляться

**Пример:**
- Юзер: «поставь напоминание на 9:00»
- Агент: «Поставил. Но вчера тоже ставил — и что? Что реально хочешь сделать утром?»

---

## Приложение E: чеклист стейджей

Краткая сводка для трекинга прогресса.

> Статус на 2026-06-13: ✅ готово · 🟡 частично · ⬜ нет.

| Stage | Название | Срок | Done when | Статус |
|---|---|---|---|---|
| 0 | Foundation | 3-5 дн | APK собирается, пустой экран | ✅ |
| 1 | Engine spin | 1-1.5 нед | render_card работает в чате | 🟡 (работает, но как markdown; нет JSON-schema-валидации) |
| 2 | Memory basic | 1 нед | remember/recall, сессия восстанавливается | ✅ |
| 3 | Persona & Settings | 3-5 дн | переключаемая персона, multi-provider | ✅ |
| 4 | Notifications + Work | 1 нед | напоминания, фоновые задачи | 🟡 (нет action-кнопок, 3 каналов, deep-link) |
| 5 | More templates | 3-5 дн | 5+ типов карточек | 🟡 (6 шаблонов как markdown; нет TemplatesScreen) |
| 6 | Web + DB | 1 нед | web_fetch, query_db | 🟡 (query_db есть; web-тулов нет) |
| 7 | Memory 2.0 | 1-1.5 нед | семантический поиск | ⬜ |
| 8 | Production | 1-2 нед | подписанный AAB | ⬜ (тесты частично; R8/CI/AAB нет) |

---

*Конец ТЗ v0.2. Следующий шаг: Stage 0 — Foundation. Скажи "го" — начну скелет.*
