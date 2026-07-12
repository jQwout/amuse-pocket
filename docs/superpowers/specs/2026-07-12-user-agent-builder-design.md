# Конструктор пользовательских агентов: sandbox и runtime rails

Дата: 2026-07-12. Статус: дизайн согласован, готов к декомпозиции.

## 1. Цель

Замкнуть продуктовую петлю AI Academy:

```text
изучить устройство агента → собрать своего агента → безопасно проверить →
сохранить → использовать в отдельном чате
```

Целевая аудитория первой версии — продвинутый пользователь. Ему доступен
редактор system prompt, tools, skills, memory, widgets, lifecycle hooks и режима
цикла, но системная safety-рамка и runtime-политики не редактируются.

## 2. Текущий разрыв

В приложении уже есть:

- `MiniAgentCatalog` с шестью preset-агентами;
- `Conversation.agentId`, связывающий чат с агентом;
- `PrePromptAssembler`, tool scoping и `ReactLoopRunner`;
- PLAN, GOAL и REFLECT;
- встроенные tools, skills, memory, heartbeat и Driven UI;
- read-only `AgentInspectorSheet`;
- общий файловый sandbox.

Пользователь пока не может создать, сохранить или изменить определение агента.
Preset-каталог захардкожен в Kotlin, skills статически зарегистрированы в Metro,
hooks в Inspector являются заглушкой, память и файлы не изолированы по
пользовательским агентам.

## 3. Зафиксированные продуктовые решения

1. В первой версии создаётся один самостоятельный агент, а не swarm/workflow.
2. Preset-агенты остаются read-only Kotlin templates.
3. Пользовательские агенты хранятся в Room.
4. Runtime получает оба источника через единый `AgentResolver`.
5. Созданный агент появляется в roster и открывается как отдельный чат.
6. Память имеет обязательный namespace агента; общий профиль подключается
   отдельным toggle и остаётся read-only.
7. Пользователь выбирает встроенные skills и может создать свой `SKILL.md` в
   приложении.
8. MCP не реализуется в первой версии, но модель источника tools допускает его
   позднее.
9. Поддерживаются события `BEFORE_TURN`, `AFTER_TURN`, `BEFORE_TOOL`,
   `AFTER_TOOL`, `ON_ERROR`, `ON_COMPLETE`.
10. Hooks декларативные; пользовательский JavaScript/Kotlin не исполняется.
11. Builder — wizard с rails, а не свободный node-based canvas.
12. Preview использует тот же ReAct runtime, но физически блокирует side effects.

## 4. Термины lifecycle

- **Run** — полный ответ агента на одно сообщение пользователя.
- **Turn** — одна LLM-итерация внутри ReAct run.
- **Tool execution** — один конкретный вызов tool между LLM-итерациями.
- **Complete** — терминальное успешное завершение run.
- **Error** — терминальная или перехваченная ошибка runtime/hook/tool.

Это различие устраняет пересечение `AFTER_TURN` и `ON_COMPLETE`.

## 5. Архитектурный подход

Используется эволюционный гибрид:

```text
MiniAgentCatalog (read-only presets) ─┐
                                      ├─ AgentResolver ─ AgentDefinition snapshot
Room UserAgentRepository ─────────────┘
```

Preset-каталог не переносится в Room. Пользователь может только открыть preset,
посмотреть его или клонировать в custom agent. ID custom agent не может
пересекаться с preset ID.

### 5.1 Доменная модель

```kotlin
enum class AgentSource { PRESET, USER }

data class AgentDefinition(
  val id: String,
  val source: AgentSource,
  val name: String,
  val description: String,
  val iconKey: String,
  val systemPrompt: String,
  val toolNames: Set<String>,
  val skillIds: Set<String>,
  val defaultMode: AgentMode,
  val allowedModes: Set<AgentMode>,
  val memoryPolicy: AgentMemoryPolicy,
  val allowedWidgetTemplates: Set<String>,
  val hooks: List<AgentHook>,
  val configVersion: Long,
  val enabled: Boolean,
)

data class AgentMemoryPolicy(
  val namespace: String,
  val includeSharedProfile: Boolean,
)
```

`systemPrompt` — пользовательский слой. Он добавляется после неизменяемого
`system_base.md` и не может заменить или скрыть его.

`AgentResolver` предоставляет:

```kotlin
interface AgentResolver {
  suspend fun resolve(id: String?): AgentDefinition?
  fun observeRoster(): Flow<List<AgentDefinition>>
}
```

Каждый run получает immutable snapshot. Изменение агента во время streaming
вступает в силу только со следующего run.

## 6. Хранилище

### 6.1 Таблицы

`user_agents`:

- `id`, `name`, `description`, `iconKey`;
- `systemPrompt`;
- `toolNames`, `skillIds`, `allowedModes`, `allowedWidgetTemplates`;
- `defaultMode`;
- `memoryNamespace`, `includeSharedProfile`;
- `status`: `DRAFT | PUBLISHED | ARCHIVED`;
- `configVersion`, `createdAt`, `updatedAt`.

`user_skills`:

- `id`, `agentId`;
- `name`, `description`, `whenToUse`;
- `instructionsMarkdown`;
- `createdAt`, `updatedAt`.

`agent_hooks`:

- `id`, `agentId`, `event`, `conditionType`, `conditionJson`;
- `actionType`, `actionJson`;
- `priority`, `enabled`, `createdAt`, `updatedAt`.

`agent_hook_runs`:

- `id`, `hookId`, `conversationId`, `runId`, `timestamp`;
- `status`, `summary`, `errorMessage`.

Сохранение агента, его custom skills и hooks выполняется одной Room transaction.
Draft и опубликованный snapshot логически разделены: незавершённый draft не
появляется в roster.

Удаление в UI означает `ARCHIVED`. Это сохраняет имя и конфигурацию для старых
conversations. Физическое удаление — отдельное явное действие вне первой версии.

### 6.2 Миграции

Фактическая база — `ai_open_pocket_db`, `AppDatabase.version = 2`, несмотря на
устаревшие значения в `AGENTS.md`. До релиза проект использует
`fallbackToDestructiveMigration`; перед реализацией нужно явно решить, допустима
ли потеря первых custom agents при следующих schema bumps. Для пользовательской
фичи рекомендуется включить schema export и явные migrations начиная с версии,
добавляющей builder.

## 7. Prompt и skills

Порядок prompt layers:

```text
immutable system_base
→ mode instructions
→ user agent systemPrompt
→ selected skill catalog
→ optional shared profile (untrusted user_data)
→ namespaced agent memory (untrusted user_data)
→ conversation history
```

Текущая связь `skillId == agentId` удаляется. Доступность `load_skill(id)`
проверяется по `AgentDefinition.skillIds`.

`SkillRegistry` становится composite registry:

- asset skills из Metro остаются read-only;
- Room skills загружаются динамически;
- custom skill принадлежит ровно одному custom agent;
- custom agent может ссылаться на встроенный skill и на собственные skills;
- custom SKILL.md ограничен по размеру и валидируется до публикации.

## 8. Memory и file sandbox

### 8.1 Memory

Namespace имеет канонический вид:

```text
agent:{agentId}:
```

`memory_tool` автоматически добавляет этот prefix при записи и не принимает
попытку обратиться к чужому namespace. Поиск и prompt injection фильтруются тем
же prefix на уровне DAO/repository, а не только инструкцией модели.

При `includeSharedProfile = true` агент получает общий профиль для чтения.
Запись в общие `profile`/`preference` из custom agent запрещена первой версией.

### 8.2 Files

Корень custom agent:

```text
filesDir/agents/{agentId}/
```

`FileTools` и `AgentFilesRepository` получают agent context и всегда разрешают
путь относительно этого корня. Path traversal остаётся запрещён. Старый общий
`filesDir/agent` не показывается custom agents; его миграция не выполняется
автоматически.

## 9. Tools, widgets и ExecutionPolicy

Tool допускается к исполнению только если одновременно:

1. зарегистрирован в global `ToolRegistry`;
2. входит в `AgentDefinition.toolNames`;
3. разрешён текущим mode;
4. разрешён `ExecutionPolicy`;
5. прошёл `BEFORE_TOOL` hooks;
6. пользователь подтвердил действие, если policy требует approval.

`Tool.riskClass` недостаточно для составных tools: например, `file_tool`
совмещает чтение и запись, а `memory_tool` — поиск и сохранение. Контракт tools
расширяется методом `riskFor(arguments)`, который по умолчанию возвращает
`riskClass`, но переопределяется для mixed-action tools. Policy принимает
фактический risk конкретного вызова, а не максимальный risk всего tool.

### 9.1 Режимы исполнения

- `PREVIEW`: `WRITE`, `DESTRUCTIVE`, `EXTERNAL` не исполняются; runtime возвращает
  синтетический результат с именем tool и аргументами.
- `LIVE`: READ может исполняться автоматически; остальные risk classes следуют
  policy агента и глобальному safety floor.

Пользователь не может ослабить глобальный safety floor.

### 9.2 Widgets

`render_card` отдельно проверяет `template` по
`allowedWidgetTemplates`. Фильтрация schema помогает модели, но не считается
границей безопасности; неизвестный или запрещённый template отвергается runtime.
Lesson-only templates не предлагаются builder по умолчанию.

## 10. Declarative HookEngine

```kotlin
enum class HookEvent {
  BEFORE_TURN, AFTER_TURN, BEFORE_TOOL, AFTER_TOOL, ON_ERROR, ON_COMPLETE
}

enum class HookActionType {
  ADD_INSTRUCTION, REQUIRE_APPROVAL, ALLOW, DENY, LOG, STOP
}
```

Для каждого event действует матрица допустимых actions. Например,
`REQUIRE_APPROVAL`, `ALLOW`, `DENY` допустимы только в `BEFORE_TOOL`;
`ADD_INSTRUCTION` — до LLM-вызова; `LOG` — везде; `STOP` — до следующего side
effect или LLM-вызова.

Правила:

- сортировка: `priority`, затем `id`;
- `DENY` сильнее `ALLOW`;
- hook не вызывает tool, LLM или другой hook;
- hook не изменяет immutable base policy;
- число hook executions на run ограничено;
- ошибка hook записывается в trace и вызывает `ON_ERROR`;
- `ON_ERROR` не вызывает себя рекурсивно;
- все решения hook дополнительно проверяет `ExecutionPolicy`.

Этот дизайн заменяет для builder прежнюю концепцию
`2026-07-04-agent-hooks-design.md`, где hook был отдельным LLM-миниходом только
после ответа и мог вызывать tools. Два дизайна нельзя реализовывать параллельно
под одним названием `HookEntity`.

## 11. UX

### 11.1 Точки входа

- FAB «Создать агента» на `AgentsScreen`;
- «Дублировать» на preset inspector;
- «Изменить» на custom agent inspector;
- CTA «Собрать этого агента» после `agent_blueprint`.

### 11.2 Wizard

1. **Основа** — имя, иконка, цель, описание.
2. **Инструкции** — system prompt и read-only preview базовых правил.
3. **Возможности** — tools, built-in/custom skills, memory, widgets.
4. **Цикл** — default mode и allowed modes.
5. **Hooks** — карточки `Когда → Если → Сделать`.
6. **Проверка** — тестовый prompt, trace, validation summary, publish.

Draft автосохраняется. Back не теряет заполненные данные.

### 11.3 Rails в UI

- tools сгруппированы по `RiskClass`;
- показываются Android permission и реальные последствия;
- опасные инструменты требуют отдельного consent;
- неизвестные tools/skills/modes/hooks блокируют publish;
- custom skill имеет Markdown editor и validation errors;
- memory явно показывает изоляцию и read-only shared profile;
- preview и live test визуально различаются;
- custom agent может быть опубликован только после успешной структурной
  валидации, но LLM-качество ответа не является publish gate.

## 12. Chat и Inspector

Опубликованный custom agent:

- появляется в «Мои агенты»;
- открывает conversation с существующим `agentId`;
- использует только `allowedModes`;
- показывает tool trace, hook decisions и approvals;
- может быть изменён, клонирован, отключён или архивирован.

Inspector становится реальным источником конфигурации вместо placeholder:

- Prompt;
- Tools и risk;
- Skills;
- Memory policy;
- Hooks и журнал;
- Files;
- текущая `configVersion`.

## 13. Heartbeat

Heartbeat custom agent разрешается только опубликованному и enabled агенту.
Background run использует сохранённый `AgentDefinition` snapshot и пересечение:

```text
agent toolNames ∩ HeartbeatRunner.SAFE_TOOL_NAMES ∩ ExecutionPolicy
```

Approval-requiring tool в background не исполняется; run завершается
recoverable policy error и пишет журнал.

## 14. Academy bridge

`agent_blueprint` остаётся учебным чеклистом. После завершения появляется CTA,
открывающий новый builder draft. Первая версия может передать только source
metadata `ACADEMY_CAPSTONE`; автоматический парсинг свободного ответа ученика в
конфигурацию не обязателен. Prefill из ответов — последующая итерация.

## 15. MCP extension point

Первая версия не хранит endpoint, credentials и remote schemas. В доменной модели
резервируется:

```kotlin
enum class ToolSource { BUILTIN, MCP }
```

В builder отображается только `BUILTIN`. Поддержка MCP требует отдельной threat
model, credential storage, discovery, trust labels и lifecycle соединения.

## 16. Ошибки и edge cases

- Preset ID collision блокирует сохранение.
- Неизвестный tool/skill после обновления APK помечает agent invalid, но не
  удаляет его.
- Архивированный agent не запускает новый run; старый chat остаётся читаемым.
- Изменение config во время run не влияет на snapshot.
- Ошибка custom skill возвращает понятную ошибку `load_skill`, не роняет app.
- Отказ approval возвращается модели как policy denial без исполнения tool.
- Cross-agent memory/file request отвергается кодом.
- Запрещённый widget не сохраняется в message card spec.
- Hook cycle/recursion завершается deterministic error.

## 17. Тестовая стратегия

Обязательные уровни:

- pure unit: validator, resolver precedence, hook ordering/action matrix,
  execution policy, namespace/path resolution;
- repository/Room: transaction, status transitions, archive semantics,
  composite skill lookup;
- runtime integration: scoped prompt/tools/skills, preview denial, approval,
  widget allowlist, edit-during-run snapshot;
- ViewModel: wizard draft, validation, publish, clone;
- Compose: six steps, risk consent, preview/live state, roster sections;
- security regressions: foreign namespace/path, hallucinated tool, forged card,
  recursive hook, background approval;
- migration test from current database version;
- screenshot tests for roster, builder summary and inspector.

## 18. Out of scope

- MCP client/runtime;
- arbitrary user scripts;
- workflow graph, swarm, coordinator and multi-agent routing;
- marketplace/import package/export package;
- cloud sync;
- automatic LLM generation of a complete config from one sentence;
- automatic migration of files from the old shared sandbox;
- hard delete and cascade purge;
- editing built-in presets or asset skills.

## 19. Definition of Done

- Пользователь создаёт custom agent без изменения Kotlin/JSON-файлов проекта.
- Agent переживает перезапуск приложения и появляется в roster.
- Agent запускается через существующий ReAct runtime со scoped tools/skills.
- Memory и files изолированы по agent ID.
- Preview физически не выполняет side effects.
- Live tools проходят risk policy и approvals.
- Все шесть lifecycle events исполняются декларативно и видны в trace.
- Custom SKILL.md загружается только разрешившим его агентом.
- Widget allowlist enforced runtime.
- Capstone ведёт в builder.
- Preset agents продолжают работать без миграции в Room.
- Тесты, debug build и `traxer check` проходят.
