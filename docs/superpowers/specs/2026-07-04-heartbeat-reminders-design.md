# Heartbeat/cron агента + напоминалки с карточкой в чате

Дата: 2026-07-04. Ветка: mvp.

## Цель

Две связанные фичи:

1. **Напоминалки и heartbeat агента.** Агент умеет ставить отложенные/периодические задачи через
   WorkManager. Два режима: простой пуш («напомни в 18:00 позвонить маме») и настоящий heartbeat —
   воркер будит агента в фоне, тот выполняет LLM-задачу с тулзами и сам решает, беспокоить ли юзера.
   В чате постановка подтверждается живой карточкой со статусом и кнопкой отмены.
2. **Урок «Heartbeat и cron в агентах»** — JSON-урок в общем ряду, финал — живая практика:
   юзер ставит свой первый heartbeat прямо из урока.

## Что уже есть (переиспользуем)

- `NotificationsTool` — немедленный локальный пуш; `NotificationHelper.showAgentNotification`
  честно репортит отсутствие POST_NOTIFICATIONS.
- `WorkManagerTool` + `NotificationWorker` — статичные отложенные/периодические пуши.
  **Не трогаем** (обратная совместимость), но агенты ростера переключаются на новый тул.
- Ростер из 6 мини-агентов (`MiniAgentCatalog`): budget, languages (Репетитор/SRS), tasks,
  reminders, nutrition, diary. `MiniAgentCatalog.get(id)` — lookup по id (переиспользуем в воркере).
- Карточка `notification_permission` в `render_card`/DrivenUiEngine — запрос разрешения из чата.
- `ReactLoopRunner` — headless-движок ReAct-цикла (conversationId, messages, tools, emit).
- Мини-агент «Напоминалки» в ростере, экран ScheduledWorks.
- 18 JSON-уроков в `assets/lessons`, контракт «1 шаг = 1 сообщение».

## Решения (из брейнсторминга)

| Вопрос | Решение |
|---|---|
| Что делает heartbeat при срабатывании | Будит агента; опционально с LLM-задачей |
| Куда идёт результат фонового хода | Сообщение в исходный диалог + пуш (тап открывает диалог) |
| Тулзы в фоновом ходе | Безопасное подмножество (чтение), лимит витков как в goal-mode |
| Карточка в чате | Живая: статус из Room, кнопка «Отменить» |
| Урок | Теория + квиз + финал: постановка реального heartbeat |
| Архитектура | Подход A: Room-реестр + CoroutineWorker поверх ReactLoopRunner |

## Архитектура

### Тул `heartbeat`

Один тул покрывает и напоминалки, и heartbeat — режим задаёт поле `mode`:

- `mode: "push"` — в назначенное время показать пуш с заданным текстом (LLM не просыпается).
- `mode: "agent"` — воркер будит агента:
  - есть `prompt` → агент выполняет задачу, результат всегда в чат + пуш;
  - нет `prompt` → дефолтный «пульс»-промпт: «Это плановый heartbeat. Посмотри память и контекст;
    если есть что-то полезное для юзера — сообщи, иначе ответь HEARTBEAT_OK».
    На `HEARTBEAT_OK` — полная тишина (ни пуша, ни сообщения), обновляется только lastRunAt.
    Это паттерн OpenClaw/Claude Code и главная идея урока.

Действия: `schedule` (one-time `delay_minutes` ИЛИ periodic `interval_minutes` ≥ 15),
`cancel(heartbeat_id)`, `list`. Успешный `schedule` возвращает `heartbeat_id` и инструкцию
показать карточку `render_card(template="reminder", params={heartbeat_id})`.

**Доступность агентам.** Тул раздаётся всему ростеру (не только «Напоминалкам») — каждый
специалист ставит проактивные напоминания/heartbeat в своём домене: дневник — вечернее «как
прошёл день», таск-лист — проверку просроченных, Репетитор — напоминание о SRS-повторениях
(заменяет его прежний `work_manager`), бюджет — сводку трат, нутрициолог — приёмы пищи. В
фоновом ходе `agentId` из карточки поднимает системный промпт именно этого агента, так что
напоминание приходит его голосом. Фоновое подмножество тулз общее для всех (чтение + пуш) —
доменные записи агент делает уже на переднем плане, когда юзер откроет чат по тапу на пуш.

Без POST_NOTIFICATIONS `schedule` возвращает recoverable-ошибку с инструкцией показать
карточку `notification_permission` и повторить.

### Room: таблица `heartbeats`

`HeartbeatEntity`: `id` (uuid), `title`, `mode` (push/agent), `prompt?`, `agentId`
(какой мини-агент проснётся), `conversationId` (куда писать результат), `kind`
(one_time/periodic), `fireAt?`/`intervalMinutes?`, `status`
(scheduled/fired/cancelled/error), `createdAt`, `lastRunAt?`, `lastResult?`
(короткая выжимка для карточки). DAO с `observe(id): Flow` для живой карточки и
`listActive()` для тула. Миграция поверх свежего v1 baseline.

В input Data воркера кладётся ТОЛЬКО `heartbeat_id` — спека живёт в Room
(обходим 10KB-лимит Data и костыль с тегами из WorkManagerTool).

### HeartbeatWorker (CoroutineWorker)

1. Достать `HeartbeatEntity` по id; нет/cancelled → `Result.success()` (no-op).
2. `mode=push` → показать пуш (как NotificationWorker), обновить status/lastRunAt.
3. `mode=agent`:
   - собрать системный промпт мини-агента (`agentId`) + задачу (prompt или «пульс»);
   - тулзы: безопасное подмножество из ToolRegistry — чтение памяти, database_query,
     календарь (чтение), шагомер, notifications. Исключены: sms, email, запись файлов,
     alarm, сам heartbeat (запрет самовоспроизводства), render_card;
   - `ReactLoopRunner.run` headless (emit = no-op), лимит витков как в goal-mode;
   - `HEARTBEAT_OK` без prompt → тишина, обновить lastRunAt;
   - иначе → `MessageEntity` (assistant) в `conversationId` + пуш с первыми ~120 символами
     ответа; PendingIntent пуша открывает этот диалог.
4. Periodic: после хода обновить lastRunAt/lastResult, статус остаётся scheduled.

DI: воркеру нужны LlmClient, DAO, ToolRegistry — добавляем кастомную `WorkerFactory`,
привязанную к Metro-графу через `Configuration.Provider` в Application.
Существующий `NotificationWorker` (без зависимостей) продолжает работать через ту же фабрику.

### Карточка `reminder` (render_card)

Params: `{heartbeat_id}`. DrivenUiEngine подписывается на `HeartbeatDao.observe(id)`:

- заголовок, когда сработает («через 30 мин» / «каждые 2 ч»), бейдж режима (пуш/агент);
- статус: ждёт / сработало (lastRunAt + краткий lastResult) / отменено (карточка серая) / ошибка;
- кнопка «Отменить»: `WorkManager.cancelUniqueWork(id)` + `status=cancelled` в Room.

Живость — из Room Flow, WorkManager не опрашивается.

### Урок `heartbeat_and_cron.json`

По существующему контракту, в «пути агента» после урока про goal-mode. Содержание:

1. Откуда взялся cron; чем heartbeat агента отличается (агент сам решает, беспокоить ли —
   паттерн HEARTBEAT_OK).
2. Android-специфика: почему WorkManager, лимит 15 минут на periodic, doze, неточное время.
3. Квиз.
4. Финал-практика: агент предлагает поставить первый heartbeat и по-настоящему вызывает
   тул `heartbeat` — юзер видит живую карточку и позже получает первое срабатывание.

Пишется последним, когда тул работает.

## Обработка ошибок

- Нет сети при срабатывании → `Result.retry()` с экспоненциальным backoff.
- Нет API-ключа / LLM упал после ретраев → `status=error`, причина в lastResult,
  ошибка видна на карточке, пушом не спамим.
- Разрешение отозвано между постановкой и срабатыванием → failure + статус на карточке
  (как сейчас в NotificationWorker).
- Лимит витков исчерпан → в чат уходит то, что успели, с пометкой.

## Тестирование (TDD)

- Тул: schedule/cancel/list, валидация интервала (<15 → ошибка), recoverable-ошибка без разрешения.
- Воркер с фейковым LlmClient: задача → сообщение в диалог + пуш; HEARTBEAT_OK → тишина;
  сеть недоступна → retry; cancelled-спека → no-op.
- DAO: insert/observe/статусные переходы.
- Карточка: маппинг статусов Room → UI-состояния.

## Traceability

- Новый trace id для heartbeat-фичи (`traxer id`), запись в traceability.yaml с rationale,
  `@trace` в тул/воркер/entity/карточку.
- Урок — под существующий id уроков (`uroki-kontseptsii-v-chate-po-json-kontraktu-0a70c2`)
  либо ai-academy (по месту при имплементации).
- Коммиты с трейлером `Trace: <id>`.

## Вне скоупа

- FCM / серверные пуши — только локальные уведомления.
- Точное время (AlarmManager exact) — точность WorkManager достаточна.
- Изменение/перенос напоминания с карточки (только отмена; перенос — текстом через агента).
- Выпиливание старого WorkManagerTool.
