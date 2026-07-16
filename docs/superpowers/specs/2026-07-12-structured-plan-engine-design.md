# Structured Plan Engine для доменных агентов

Дата: 2026-07-12. Статус: дизайн согласован; реализация — в объединённом плане
`docs/superpowers/plans/2026-07-12-agents-platform.md` (Tasks 10–18, trace
`structured-plan-engine-907066`) вместе с user-agent-builder.

## 1. Цель

Заменить prompt-only PLAN/GOAL на общий структурированный механизм планирования,
исполнения и проверки, пригодный для:

- разовой задачи (`SINGLE_RUN`);
- долгой программы на дни или недели (`PROGRAM`);
- бюджетного агента;
- агента питания;
- языкового репетитора;
- будущих пользовательских агентов.

PLAN создаёт и изменяет план. GOAL исполняет утверждённую версию, собирает
доказательства и обновляет прогресс.

## 2. Зафиксированные решения

1. PLAN включается пользователем вручную.
2. Модель данных едина для разовой задачи и долгой программы.
3. PLAN и GOAL используют одну persisted Plan-сущность.
4. Создание плана и существенные изменения требуют одобрения пользователя.
5. Обновление прогресса, evidence и технических timestamps одобрения не требует.
6. План хранится как типизированные данные, а не только assistant markdown.
7. Планирование не получает action tools.
8. Исполнение работает только с утверждённой immutable версией.
9. Проверка результата опирается на evidence; фраза LLM «готово» недостаточна.
10. Один агент может иметь несколько планов, но один conversation исполняет не
    более одного plan run одновременно.

## 3. Текущая проблема

Сейчас PLAN состоит из двух LLM-запусков:

```text
prompt-only plan without tools → plain assistant message → approval message →
ReAct with tools
```

GOAL — ограниченный ReAct loop с prompt-инструкцией самостоятельно придумать
критерии и проверить их.

Хорошие существующие свойства сохраняются:

- planning физически не получает action tools;
- PLAN имеет user approval gate;
- GOAL имеет жёсткий iteration limit;
- tool allowlist агента сохраняется;
- tool trace остаётся источником аудита.

Недостающие свойства:

- нет `planId` и версии;
- нет persisted steps/status/evidence;
- план может выпасть из context window;
- исполнитель может отойти от текста плана;
- GOAL сам объявляет собственный успех;
- нет восстановления долгой программы после restart;
- нет отдельного состояния `NEEDS_REVISION`.

## 4. State machine

```text
DRAFT
  ├─ missing context → NEEDS_INFO → DRAFT
  └─ valid proposal → AWAITING_APPROVAL

AWAITING_APPROVAL
  ├─ edit/reject → DRAFT
  └─ approve → ACTIVE

ACTIVE
  ├─ due/runnable step → EXECUTING
  ├─ material revision → AWAITING_APPROVAL
  ├─ all criteria proven → COMPLETED
  └─ user pause → PAUSED

EXECUTING
  ├─ tool/user result → VERIFYING
  ├─ missing input → NEEDS_INFO
  └─ runtime error → FAILED_STEP

VERIFYING
  ├─ criterion proven → ACTIVE or COMPLETED
  ├─ retryable → ACTIVE
  └─ plan invalid → NEEDS_REVISION
```

Терминальные состояния плана: `COMPLETED`, `CANCELLED`, `FAILED`.
`PARTIAL` является результатом конкретного run, но не обязано закрывать долгую
программу.

## 5. Доменная модель

```kotlin
enum class PlanHorizon { SINGLE_RUN, PROGRAM }
enum class PlanStatus {
  DRAFT,
  NEEDS_INFO,
  AWAITING_APPROVAL,
  ACTIVE,
  PAUSED,
  NEEDS_REVISION,
  COMPLETED,
  CANCELLED,
  FAILED,
}

data class AgentPlan(
  val id: String,
  val agentId: String?,
  val conversationId: String,
  val horizon: PlanHorizon,
  val status: PlanStatus,
  val approvedVersion: Int?,
  val activeStepId: String?,
  val createdAt: Long,
  val updatedAt: Long,
)

data class PlanVersion(
  val planId: String,
  val version: Int,
  val objective: String,
  val assumptions: List<String>,
  val constraints: List<String>,
  val steps: List<PlanStep>,
  val criteria: List<PlanCriterion>,
  val createdBy: PlanAuthor,
  val approvedAt: Long?,
)

data class PlanStep(
  val id: String,
  val title: String,
  val instruction: String,
  val dependsOn: Set<String>,
  val expectedTools: Set<String>,
  val schedule: PlanSchedule?,
  val completionRule: CompletionRule,
)

data class PlanCriterion(
  val id: String,
  val description: String,
  val verifier: CriterionVerifier,
  val required: Boolean,
)
```

Runtime progress хранится отдельно от immutable `PlanVersion`:

```kotlin
data class PlanStepState(
  val planId: String,
  val version: Int,
  val stepId: String,
  val status: StepStatus,
  val attempts: Int,
  val nextDueAt: Long?,
  val startedAt: Long?,
  val completedAt: Long?,
)

data class PlanEvidence(
  val id: String,
  val planId: String,
  val version: Int,
  val criterionId: String?,
  val stepId: String?,
  val source: EvidenceSource,
  val reference: String,
  val summary: String,
  val createdAt: Long,
)
```

Evidence хранит ссылку/идентификатор результата, а не произвольное заявление
модели. Source первой версии:

- `TOOL_RESULT`;
- `MEMORY_FACT`;
- `USER_CONFIRMATION`;
- `MODEL_ASSESSMENT`.

`MODEL_ASSESSMENT` допускается только для семантических критериев и помечается в
UI как слабое доказательство.

## 6. Persistence

Room получает таблицы:

```text
agent_plans
plan_versions
plan_step_states
plan_evidence
plan_events
```

`plan_versions.snapshotJson` хранит immutable version целиком. Progress и evidence
нормализованы, потому что часто обновляются отдельно.

Каждое существенное изменение создаёт `version + 1` со статусом
`AWAITING_APPROVAL`. Текущая approved version продолжает действовать до одобрения
новой.

Material changes:

- objective, constraints или criteria;
- добавление/удаление/reorder steps;
- изменение expected tools;
- изменение schedule;
- изменение completion rule.

Non-material changes:

- step status;
- attempts;
- evidence;
- timestamps;
- next due time, рассчитанное по уже утверждённому schedule.

## 7. Planner contract

Planning phase больше не принимает план как свободный markdown. LLM получает
только внутренние control tools:

```text
submit_plan
request_plan_information
propose_plan_revision
```

Action tools (`memory_tool`, `alarm`, `heartbeat`, `srs_tool` и остальные)
planning phase не получает.

`submit_plan` валидирует:

- objective не пуст;
- IDs уникальны;
- dependency graph не имеет циклов;
- expected tools являются подмножеством tools агента;
- default limits по числу steps/criteria;
- schedule корректен;
- criterion имеет verifier;
- medical/financial safety constraints не удалены domain policy.

После успешного `submit_plan` UI показывает нормализованную PlanCard. Свободный
текст модели может быть пояснением, но не является источником исполнения.

Если контекста недостаточно, `request_plan_information` переводит план в
`NEEDS_INFO`. Старый строковый маркер `[[NEED_INFO]]` после миграции удаляется.

## 8. Approval и versioning

PlanCard показывает:

- objective и horizon;
- assumptions/constraints;
- steps;
- критерии;
- tools и risk;
- schedule;
- номер версии и изменения относительно approved version.

Действия:

```text
Approve
Request changes
Cancel
Pause/Resume (для ACTIVE)
```

Approval сохраняет `approvedAt` и immutable `approvedVersion`.
`Request changes` передаёт комментарий planner, который создаёт новую draft
version. Одобрение не симулируется user-сообщением
«Одобряю план, выполни его»; это typed intent к `PlanRepository`.

## 9. PLAN и GOAL после миграции

### PLAN

- создаёт новый план или revision;
- задаёт уточнения;
- не исполняет action tools;
- заканчивается на `AWAITING_APPROVAL`;
- после approval остаётся в Plan UI, но не запускает исполнение автоматически.

### GOAL

- принимает `planId`/approved version;
- выбирает следующий runnable/due step;
- исполняет только этот step;
- проверяет его completion rule;
- сохраняет evidence и progress;
- продолжает в пределах run budget;
- при material change создаёт revision и останавливается на approval.

Если пользователь включает GOAL без approved plan, runtime сначала создаёт plan
proposal и останавливается на `AWAITING_APPROVAL`.

## 10. Executor

`PlanExecutor` не отдаёт модели весь план как свободную задачу. Он выбирает
конкретный runnable step и формирует ограниченный execution context:

```text
objective summary
approved version
current step
relevant constraints
required criteria
existing evidence
remaining run budget
```

Tool schemas:

```text
agent.toolNames
∩ step.expectedTools
∩ execution policy
```

Пустой `expectedTools` означает, что step не должен выполнять tool. Неизвестный
tool call отвергается кодом.

Бюджет задаётся раздельно:

- max LLM iterations per step;
- max tool calls per run;
- max retries per step;
- optional deadline/time budget.

Это заменяет один неоднозначный `goalMaxIterations`.

## 11. Verification

`PlanVerifier` проверяет step после исполнения.

Детерминированные проверки имеют приоритет:

- tool завершился успешно;
- создан ожидаемый heartbeat/alarm;
- memory/srs содержит запись;
- пользователь подтвердил результат;
- наступил deadline/schedule.

LLM verifier используется только там, где результат нельзя проверить кодом:
например, качество объяснения или соответствие рациона заданным предпочтениям.

Критерий закрывается только при наличии `PlanEvidence`. Для required criterion
без evidence план не может стать `COMPLETED`.

Verifier не получает action tools и не изменяет план.

## 12. Долгие программы

`PROGRAM` не означает бесконечный foreground loop. Один GOAL run выполняет только
due/runnable steps в рамках бюджета и сохраняет состояние.

Следующий запуск происходит:

- вручную пользователем;
- существующим heartbeat для due checkpoint;
- при открытии агента, если есть due steps.

Heartbeat не меняет materially план без approval. Он может обновить progress,
добавить evidence, выполнить утверждённый low-risk step или уведомить
пользователя.

### 12.1 Typed heartbeat target

Текущий `HeartbeatEntity.prompt` подходит для generic background-задачи, но не
является надёжной ссылкой на шаг плана. Для `PROGRAM` heartbeat получает typed
target:

```kotlin
enum class HeartbeatTargetType { GENERIC_AGENT, PLAN_STEP }

data class PlanHeartbeatTarget(
  val planId: String,
  val planVersion: Int,
  val stepId: String,
)
```

В Room это хранится явными полями `targetType`, `planId`, `planVersion`,
`planStepId`. `PlanScheduler` — единственный компонент, который создаёт
`PLAN_STEP` heartbeat. LLM не передаёт произвольные `planId/version/stepId`
напрямую в `HeartbeatTool`.

При срабатывании:

```text
HeartbeatWorker
→ HeartbeatRunner
→ проверить agent и approved plan version
→ PlanRunner.resume(planId, stepId)
→ PlanExecutor
→ PlanVerifier
→ progress/evidence
→ reschedule или terminal notification
```

Если версия больше не approved, heartbeat не запускает старый шаг. Он отмечается
stale и пересоздаётся только после одобрения новой версии. Background run не
ждёт UI approval: действие, требующее подтверждения, переводит plan в
`NEEDS_INFO`/`NEEDS_REVISION` и уведомляет пользователя.

### 12.2 Hooks вокруг Plan Engine

Шесть runtime-событий (`BEFORE_TURN`, `AFTER_TURN`, `BEFORE_TOOL`,
`AFTER_TOOL`, `ON_ERROR`, `ON_COMPLETE`) сохраняются для низкоуровневого
ReAct/tool lifecycle. Для планов добавляются:

```kotlin
ON_PLAN_CREATED
ON_PLAN_APPROVED
BEFORE_PLAN_STEP
AFTER_PLAN_STEP
ON_PLAN_BLOCKED
ON_PLAN_COMPLETED
```

Порядок исполнения:

```text
PlanRunner selects step
→ BEFORE_PLAN_STEP
→ PlanExecutor
  → BEFORE_TURN / AFTER_TURN
  → BEFORE_TOOL / ExecutionPolicy / ToolExecutor / AFTER_TOOL
→ PlanVerifier
→ persist evidence/progress
→ AFTER_PLAN_STEP
→ ON_PLAN_COMPLETED или ON_PLAN_BLOCKED
→ PlanScheduler updates heartbeat
```

Plan hooks остаются декларативными. Они могут добавить ограниченную инструкцию,
потребовать approval, запретить/остановить действие или записать audit event, но
не могут:

- вызвать LLM/tool напрямую;
- изменить approved `PlanVersion`;
- самостоятельно добавить evidence;
- расширить tool scope;
- пересоздать schedule в обход `PlanScheduler`.

`DENY`/`STOP` в `BEFORE_PLAN_STEP` переводит step в blocked state с причиной.
`AFTER_PLAN_STEP` запускается после verifier и видит уже сохранённый результат.
Ошибка plan-hook фиксируется в `plan_events`; `ON_ERROR` не вызывает себя
рекурсивно.

## 13. Domain templates

Engine остаётся generic. Доменные правила живут в skills/templates.

### Budget

```text
objective: удержать расходы месяца в пределах лимита
recurring step: еженедельная сверка расходов
criteria: категории посчитаны; лимиты не превышены или показано превышение
evidence: memory facts/tool results
```

План не получает право придумывать отсутствующие суммы и не исполняет платежи.

### Nutrition

```text
objective: соблюдать согласованный недельный режим питания
steps: собрать ограничения → предложить меню → вести отметки → недельная сверка
criteria: меню учитывает ограничения; пользователь подтвердил выполнимость
evidence: user confirmation + meal log
```

Медицинская диагностика и лечение вне scope. Тревожные симптомы требуют
остановки и рекомендации обратиться к врачу.

### Languages

```text
objective: достичь измеримой учебной цели
steps: входная оценка → расписание → SRS sessions → checkpoint
criteria: выполнено N занятий; закрыт SRS backlog; checkpoint score достигнут
evidence: srs_tool results + user-confirmed speaking/writing checkpoint
```

## 14. Context и prompt layers

`PrePromptAssembler` не должен постоянно вставлять полную историю всех планов.
Новый `PlanContextProvider` добавляет только:

```text
active plan ID/version
objective summary
current step
relevant constraints
required criteria
compact progress
```

Полный snapshot читает только Planner/Executor/Verifier.

Approved plan является execution contract, но его текстовые поля всё равно
экранируются как структурированные данные. Они не могут расширить tool scope,
отменить system policy или изменить risk class.

Runtime composition для пользовательского агента:

```text
AgentDefinition
  ├─ prompt/persona
  ├─ allowedModes
  ├─ toolNames + ExecutionPolicy
  ├─ skillIds
  ├─ memoryPolicy
  ├─ hooks
  └─ widget allowlist

AgentPlan
  ├─ approved objective/steps/criteria
  ├─ progress/evidence
  └─ PlanSchedule → typed heartbeat
```

`AgentDefinition` отвечает за постоянные способности агента. `AgentPlan`
отвечает за конкретную цель и не может выдать агенту capability, отсутствующую
в definition.

## 15. UI

В чате:

- PlanCard вместо plain markdown plan;
- progress card для текущего run;
- кнопки approve/revise/pause/resume;
- evidence доступно через раскрытие;
- partial/failed показывает конкретный step и причину.

На экране агента:

- список active/completed/paused plans;
- одна active execution на conversation;
- отдельная detail view с timeline событий;
- программы показывают next due date.

## 16. Ошибки и восстановление

- Restart приложения: ACTIVE/PAUSED/NEEDS_INFO восстанавливаются из Room.
- Оборванный `EXECUTING` после process death переводится в retryable state, но
  side-effect tool не повторяется без idempotency evidence.
- Tool удалён из agent scope: план получает `NEEDS_REVISION`.
- Version conflict: run заканчивается на старом snapshot; новая version
  применяется только следующему run.
- Step исчерпал retries: `FAILED_STEP`, затем manual retry/revision/cancel.
- Пользователь отменил run: plan остаётся ACTIVE, текущий step не становится
  completed.
- Deadline пропущен: event + verifier решают retry/revision, но не скрывают
  просрочку.

## 17. Тестирование

- State machine transitions.
- Plan validation и cycle detection.
- Immutable version после approval.
- Material/non-material classification.
- Planner control tools и отсутствие action tools.
- Tool intersection на step.
- Evidence-required completion.
- Deterministic verifier и слабый model evidence.
- Restart/recovery/idempotency.
- Heartbeat due step без material revision.
- Typed heartbeat version/step validation и stale-target behavior.
- Background run не ожидает интерактивный approval.
- Plan hook ordering, action matrix, blocked-step transition и non-recursive error.
- Полный порядок `plan hook → runtime hook → policy → tool → verifier`.
- Budget/nutrition/languages template contract tests.
- Compose PlanCard/approval/progress/error states.
- Regression tests текущих CHAT/REFLECT и preset agent scoping.

## 18. Out of scope

- Автоматическое включение PLAN по решению модели.
- DAG с параллельным исполнением.
- Multi-agent delegation и coordinator.
- Автономное одобрение material revisions.
- Выполнение финансовых транзакций.
- Медицинские назначения.
- Полный календарный scheduler вместо существующего heartbeat.
- Marketplace plan templates.

## 19. Definition of Done

- PLAN сохраняет typed proposal и не получает action tools.
- User approval фиксирует immutable version без magic chat message.
- GOAL исполняет approved plan по steps.
- Required criteria нельзя закрыть без evidence.
- SINGLE_RUN и PROGRAM используют одну модель.
- Долгая программа восстанавливается после restart.
- Material revision всегда возвращается на approval.
- `PROGRAM` возобновляется typed heartbeat по `planId/version/stepId`, а не
  свободным background prompt.
- Plan lifecycle hooks реально вызываются движком и не могут мутировать
  approved version или обходить policy.
- Background execution не зависает в ожидании UI approval.
- Budget, nutrition и languages имеют testable domain templates.
- CHAT и REFLECT сохраняют прежнее поведение.
- Unit/UI tests, debug build и `traxer check` проходят.
