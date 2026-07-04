# Фоновые ходы агента (TurnManager в AppScope)

Дата: 2026-07-04
Ветка: `feature/background-turns` (от `fix/chat-lag`)
Статус: одобрено на брейншторме, ждёт ревью спеки

## Проблема

Ход агента (стриминг + тул-луп + запись ответа) живёт в `viewModelScope` экрана чата
(`ChatViewModel.launchTurn`). Выход с экрана → `onCleared` → `activeJob.cancel()` — запрос
умирает. Ответ пишется в Room только на `AgentEvent.Completed`, поэтому отменённый ход
теряется целиком, включая уже настримленный текст. Ретрай-контекст (`lastUserText` и т.д.)
тоже умирает вместе с ViewModel.

## Решение (выбранный подход)

Ход агента переезжает в **`TurnManager`** — синглтон уровня приложения (Metro
`@SingleIn(AppScope)`) со своим `CoroutineScope(SupervisorJob() + Dispatchers.Default)`.
Экран становится проектором состояния: подписался/отписался, ход живёт независимо.

Рассмотренные альтернативы:

- **Foreground Service** — единственное, что переживает смахивание из recents, но:
  обязательное уведомление во время работы, тип `dataSync` с таймаутами на Android 15,
  много ЖЦ-кода. Овперкилл для хода в 10–60 сек.
- **WorkManager** — для отложенных/гарантированных задач; латентность старта, стримить
  SSE в UI из воркера неудобно. Уже используется по назначению в scheduled works.
- **Бекенд** — меняет философию on-device агента, отдельный роадмап.
- **Room-first стриминг** (партиал в БД, UI наблюдает БД) — переживает смерть процесса,
  но: миграция схемы, постоянные записи при стриме, ломается инвариант «в истории только
  завершённые сообщения» для контекста LLM/памяти/поиска. Отклонено как отдельная фича,
  которую не заказывали.

**Принятая граница живучести:** ход живёт, пока жив процесс. Смерть процесса или
смахивание из recents — ход пропал (сообщение юзера в истории остаётся, оно записано в
начале хода). Это осознанный трейдофф в пользу простоты.

## Ключевые решения

1. **Параллельность:** один активный ход на диалог; между диалогами — параллельно.
   Внутри диалога новый ход делает `cancelAndJoin` предыдущего (как сейчас, но ключ —
   `conversationId`, а не единственный глобальный `activeJob`).
2. **Уведомления:** только когда приложение реально в фоне (`ProcessLifecycleOwner`).
   Внутри приложения — тихая индикация бейджем в списке диалогов. Уведомляем и об успехе,
   и о падении хода.
3. **Стрим в памяти:** `StateFlow` в сессии, Room — только финальная запись на
   `Completed` (как сейчас).

## Компоненты

### TurnManager (новый, `agent/loop/`)

- `@SingleIn(AppScope)`, скоуп `SupervisorJob() + Dispatchers.Default`.
- `sessions: StateFlow<Map<String, TurnSession>>` — активные и недавно завершённые
  сессии по `conversationId`.
- API:
  - `launchTurn(convId, userText, mode, planApproved, maxIterations)` — записывает
    сообщение юзера в Room, гоняет `AgentLoopOrchestrator.execute`, проецирует события
    в сессию, на `Completed` пишет ответ/карточки в Room.
  - `retry(convId)` — перезапуск последнего хода без повторного addMessage.
  - `cancel(convId)`, `clearError(convId)`, `invalidateRetry(convId, messageId)`.
  - `session(convId): Flow<TurnSession?>`.
- Вся логика текущих `launchTurn`/`runAgent` переезжает **без изменений поведения**:
  `cancelAndJoin` предыдущего хода, rethrow `CancellationException`, `Failed(error,
  canRetry)` на исключении, гейт публикации стрима (~20/сек, `StringBuilder`),
  хэндофф `pendingHandoffId`, маркер `[[NEED_INFO]]`, извлечение `render_card`,
  бар одобрения плана.

### TurnSession (новый)

Состояние одного хода — всё, что сейчас размазано по полям ViewModel:

- `status: Idle | Running | AwaitingPlanApproval | Completed | Failed(error, canRetry)`
- `streamingText: StateFlow<String>`, `toolTrace: StateFlow<List<ToolTrace>>`
- ретрай-контекст: `lastUserText`, `lastUserMessageId`, `lastMode`, `lastPlanApproved`
- `pendingHandoffId`

Завершённая сессия остаётся в мапе с финальным статусом (для бейджа «готово»),
сбрасывается при входе в диалог или новом ходе.

Бонус: ретрай становится живучее — сейчас после выхода/возврата на экран
`lastUserText = null` и кнопка «Повторить» пропадает; в сессии контекст переживает
пересоздание ViewModel.

### ChatViewModel (худеет)

- Выпиливаются: `activeJob`, `launchTurn`, `runAgent`, ретрай-поля, `pendingHandoffId`,
  `streamingBuilder` — их источником правды становится `TurnSession`.
- Подписка `turnManager.session(convId)` → проекция в `ChatUiState` (isLoading,
  toolTrace, error, canRetry, planAwaitingApproval) + прокидывание `streamingText`.
  Возврат на экран мид-стрим — переподключение к живым флоу, стрим продолжает бежать.
- Интенты `SendMessage` / `CancelRequest` / `RetryRequest` / `ApprovePlan` / `SetMode` /
  `DeleteMessage` — делегаты в менеджер.
- Порядок в `sendMessage`: `ensureConversation` остаётся в ViewModel и выполняется **до**
  `turnManager.launchTurn` (у маршрута `chat/new` пустой convId, а сессия ключуется по
  нему); после создания диалога ViewModel перецепляет подписку на новый convId.
- Экранное остаётся на месте: ввод, голос, уроки, поиск, пагинация.

### ConversationsListViewModel

Подписка на `turnManager.sessions` → бейдж у диалога: «варится» (Running),
«готово» (Completed, не просмотрен), «ошибка» (Failed).

### AppForegroundTracker (новый) + уведомления

- Обёртка над `ProcessLifecycleOwner` (новая зависимость
  `androidx.lifecycle:lifecycle-process`), отдаёт `isInForeground`.
- На `Completed`/`Failed` при фоне — `showAgentNotification` из существующего
  `NotificationHelper`: успех — заголовок диалога + начало ответа; падение — «Ход не
  удался, можно повторить». `notificationId = convId.hashCode()` (новое уведомление
  диалога заменяет старое). `PendingIntent` с extra `conversationId` → `MainActivity`
  роутит в диалог.
- POST_NOTIFICATIONS уже запрашивается в MainActivity; без разрешения хелпер вернёт
  false — ход всё равно сохранён в Room.

## Края и ошибки

- Удаление диалога с активным ходом → `cancel(convId)` перед удалением.
- Удаление сообщения, на которое смотрит ретрай → `invalidateRetry` (та же логика,
  что сейчас в `deleteMessage`).
- Параллельные записи в Room из двух ходов — у Room один writer-лок; тулы мини-агентов
  скоуплены по диалогу.
- Гоал-мод на много витков в фоне — штатный кейс.
- Смена режима (`SetMode`) сбрасывает `canRetry` — как сейчас.

## Тесты

Юнит-тесты `TurnManager` с фейковым оркестратором и фейковым foreground-трекером:

1. Ход переживает отписку экрана (никто не собирает флоу — ход дорабатывает, ответ в Room).
2. Два диалога — параллельные независимые сессии.
3. Новый ход в том же диалоге ждёт `cancelAndJoin` предыдущего.
4. Ретрай после падения; ретрай-контекст жив после «пересоздания ViewModel».
5. Хэндофф: `pendingHandoffId` снимается приходом сообщения.
6. Уведомление уходит только при фоне; при foreground — не уходит.
7. `invalidateRetry` гасит `canRetry` при удалении сообщения-источника.

Существующие тесты ChatViewModel адаптируются на делегацию.

## Вне скоупа

- Переживание смерти процесса (Room-first партиал, foreground service).
- Отмена хода из уведомления.
- Очередь ходов внутри диалога (остаётся «новый отменяет старый»).
