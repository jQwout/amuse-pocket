# Structured Plan Automation Implementation Plan

> **Исполняется как Фаза B объединённого плана**
> `docs/superpowers/plans/2026-07-12-agents-platform.md` — после его Фазы A
> (builder Tasks 3–9). Поправки согласования (сильнее текста этого файла) — в
> разделе «Фаза B» того плана: замороженный trace id
> `structured-plan-engine-907066` (в Task 0 `traxer id` НЕ запускать), БЕЗ
> `AppDatabaseMigrations.kt`/schema export (действует
> `fallbackToDestructiveMigration`), Plans-секция переезжает со шторки на
> полноэкранный AgentDetailScreen (Task 21 Фазы C).

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Заменить prompt-only PLAN/GOAL на persisted Structured Plan Engine и связать его с декларативными hooks, typed heartbeat, evidence-based verification и доменными шаблонами бюджета, питания и языкового обучения.

**Architecture:** PLAN создаёт versioned proposal только через внутренние control schemas и останавливается на typed approval. GOAL исполняет approved snapshot по одному runnable step через существующий `ReactLoopRunner`, но с пересечением agent/step/policy tools, после чего `PlanVerifier` сохраняет evidence. Долгие программы возобновляются typed heartbeat по `planId/version/stepId`; plan hooks оборачивают lifecycle, не вызывая LLM/tools и не мутируя approved version.

**Tech Stack:** Kotlin 2.3, Jetpack Compose Material 3, Room, Metro DI, Kotlinx Serialization, Coroutines/Flow, WorkManager, existing OpenAI-compatible `LlmClient`, JUnit 4, Robolectric, Roborazzi, Gradle, traxer.

## Global Constraints

- Source of truth: `docs/superpowers/specs/2026-07-12-structured-plan-engine-design.md`.
- Prerequisite plan: `docs/superpowers/plans/2026-07-12-user-agent-builder.md`.
- Не начинать Task 1, пока builder Tasks 3, 8 и 9 не завершены: runtime обязан получать immutable `AgentDefinition`; `HookEngine` и `ExecutionPolicy` должны существовать.
- Preset и user agents проходят через один `AgentResolver`; прямые обращения к `MiniAgentCatalog` в plan/heartbeat runtime запрещены.
- PLAN включается вручную. Автоматическое определение «задаче нужен план» вне scope.
- PLAN не получает action tools. Внутренние planner schemas не регистрируются в global `ToolRegistry`.
- Approval — typed repository command. Не сохранять magic user message «Одобряю план...».
- Approved `PlanVersion` immutable. Material revision всегда создаёт новую pending version.
- GOAL исполняет только approved version и только один selected step за раз.
- Tool scope шага: `agent.toolNames ∩ step.expectedTools ∩ ExecutionPolicy`.
- Required criterion нельзя закрыть без persisted `PlanEvidence`.
- Hooks декларативные: не вызывают LLM/tool, не добавляют evidence и не меняют approved plan/schedule.
- `PROGRAM` не запускает бесконечный foreground loop. Один run ограничен budget и сохраняет progress.
- Typed heartbeat содержит `planId`, `planVersion`, `stepId`; свободный heartbeat prompt не является ссылкой на план.
- Background run не ждёт UI approval. Approval-required действие блокирует step и уведомляет пользователя.
- Новые Kotlin-файлы размещать под `app/src/main/java/com/aiopenpocket/...`; существующие physical paths под `com/example/` не переносить.
- Перед каждым commit запускать `traxer check`; stale rationale bless-ить только после ручной проверки.
- Commit boundaries ниже рекомендательные. Не создавать commits без явного разрешения пользователя.

## Out of Scope

- Автоматическое включение PLAN.
- Параллельный DAG executor.
- Multi-agent delegation/coordinator.
- Arbitrary hook scripts.
- Автономное одобрение material revisions.
- Финансовые транзакции и медицинские назначения.
- Замена WorkManager собственным scheduler.
- MCP planner tools.
- Marketplace/import/export plan templates.

## Dependency Gate

До выполнения этого плана текущая ветка должна предоставлять:

```kotlin
interface AgentResolver {
  suspend fun resolve(id: String?): AgentDefinition?
  fun observeRoster(): Flow<List<AgentDefinition>>
}

class HookEngine {
  suspend fun evaluate(
    event: HookEvent,
    context: HookContext,
    hooks: List<AgentHook>,
  ): HookDecision
}

class ExecutionPolicy {
  fun evaluate(request: ToolExecutionRequest): PolicyDecision
}
```

И `ReactLoopRunner.run(...)` должен принимать `AgentDefinition?` snapshot и
`ExecutionMode`, а не только `boundAgentId`.

Если хотя бы один контракт отсутствует, сначала выполнить builder plan Tasks
3 → 8 → 9. Не создавать второй `AgentResolver`, `HookEngine`,
`ExecutionPolicy`, `AgentHookEntity` или approval coordinator в plan package.

## File Map

Новые production-файлы:

```text
app/src/main/java/com/aiopenpocket/agent/core/plan/model/
  AgentPlan.kt
  PlanVersion.kt
  PlanProgress.kt
  PlanEnums.kt
app/src/main/java/com/aiopenpocket/agent/core/plan/validation/
  PlanStateMachine.kt
  PlanValidator.kt
  PlanChangeClassifier.kt
app/src/main/java/com/aiopenpocket/agent/core/plan/persistence/
  AgentPlanEntity.kt
  PlanVersionEntity.kt
  PlanStepStateEntity.kt
  PlanEvidenceEntity.kt
  PlanEventEntity.kt
  PlanDao.kt
  PlanRepository.kt
app/src/main/java/com/aiopenpocket/agent/core/plan/planner/
  PlanControlSchemas.kt
  PlanPlanner.kt
  PlanPromptBuilder.kt
app/src/main/java/com/aiopenpocket/agent/core/plan/execution/
  PlanRunBudget.kt
  PlanRunner.kt
  PlanExecutor.kt
  PlanVerifier.kt
  DeterministicVerifierRegistry.kt
app/src/main/java/com/aiopenpocket/agent/core/plan/context/
  PlanContextProvider.kt
app/src/main/java/com/aiopenpocket/agent/core/plan/schedule/
  PlanScheduler.kt
app/src/main/java/com/aiopenpocket/agent/core/plan/template/
  PlanTemplate.kt
  PlanTemplateRegistry.kt
app/src/main/java/com/aiopenpocket/agent/loop/
  StructuredPlanOrchestrator.kt
app/src/main/java/com/aiopenpocket/ui/plan/
  PlanCard.kt
  PlanDetailScreen.kt
  PlanDetailViewModel.kt
  PlanListScreen.kt
  PlanListViewModel.kt
```

Новые assets:

```text
app/src/main/assets/plan_templates/budget.json
app/src/main/assets/plan_templates/nutrition.json
app/src/main/assets/plan_templates/languages.json
app/src/main/assets/prompts/plan/planner.md
app/src/main/assets/prompts/plan/executor.md
app/src/main/assets/prompts/plan/verifier.md
```

Основные изменяемые seams:

```text
app/src/main/java/com/example/data/database/AppDatabase.kt
app/src/main/java/com/example/di/DatabaseBindings.kt
app/src/main/java/com/example/di/TurnModule.kt
app/src/main/java/com/example/agent/loop/AgentMode.kt
app/src/main/java/com/example/agent/loop/TurnPorts.kt
app/src/main/java/com/example/agent/loop/TurnManager.kt
app/src/main/java/com/example/agent/loop/TurnSession.kt
app/src/main/java/com/example/agent/loop/TurnCompletion.kt
app/src/main/java/com/example/agent/loop/AgentLoopOrchestrator.kt
app/src/main/java/com/example/agent/loop/ReactLoopRunner.kt
app/src/main/java/com/example/agent/core/prompt/PrePromptAssembler.kt
app/src/main/java/com/example/agent/core/memory/entity/HeartbeatEntity.kt
app/src/main/java/com/example/agent/core/memory/dao/HeartbeatDao.kt
app/src/main/java/com/example/agent/tools/HeartbeatTool.kt
app/src/main/java/com/example/agent/workers/HeartbeatRunner.kt
app/src/main/java/com/example/ui/chat/DrivenUiEngine.kt
app/src/main/java/com/example/ui/chat/ChatIntent.kt
app/src/main/java/com/example/ui/chat/ChatUiState.kt
app/src/main/java/com/example/ui/chat/ChatViewModel.kt
app/src/main/java/com/example/ui/chat/ChatScreen.kt
app/src/main/java/com/example/ui/agents/AgentInspectorSheet.kt
app/src/main/java/com/example/MainActivity.kt
```

---

### Task 0: Verify prerequisites and register traceability

**Files:**
- Modify: `traceability.yaml`
- Modify: future plan entry points with `@trace <new-id>`.

**Interfaces:**
- Consumes: completed builder core through ExecutionPolicy.
- Produces: baseline report and one frozen trace ID for Structured Plan Engine.

- [ ] **Step 1: Verify working tree and baseline**

Run:

```powershell
git branch --show-current
git status --short
.\gradlew.bat :app:testDebugUnitTest
traxer check
```

Expected: branch and existing user changes recorded; baseline failures, if any,
listed before new work. Never reset/clean/checkout user files.

- [ ] **Step 2: Verify dependency contracts**

Run:

```powershell
rg "interface AgentResolver|class DefaultAgentResolver" app/src/main/java
rg "class HookEngine|sealed.*HookContext|data class HookDecision" app/src/main/java
rg "class ExecutionPolicy|sealed interface PolicyDecision|enum class ExecutionMode" app/src/main/java
rg "agent: AgentDefinition" app/src/main/java/com/example/agent/loop app/src/main/java/com/example/agent/core/prompt
```

Expected: all four searches return production definitions and runtime wiring.
If not, stop and execute builder plan Tasks 3, 8, 9.

- [ ] **Step 3: Create trace ID**

Run:

```powershell
traxer id
```

Add `Structured Plan Engine` to `traceability.yaml` with rationale:

```text
Превратить PLAN/GOAL из prompt-only поведения в persisted, утверждаемый и
проверяемый контракт, который поддерживает разовые задачи и долгие программы
доменных и пользовательских агентов.
```

- [ ] **Step 4: Run focused precondition tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.aiopenpocket.agent.core.agent.AgentResolverTest" --tests "com.aiopenpocket.agent.core.hook.HookEngineTest" --tests "com.aiopenpocket.agent.core.policy.ExecutionPolicyTest"
```

Expected: PASS.

---

### Task 1: Define serializable plan domain and pure state machine

**Files:**
- Create: `app/src/main/java/com/aiopenpocket/agent/core/plan/model/PlanEnums.kt`
- Create: `app/src/main/java/com/aiopenpocket/agent/core/plan/model/AgentPlan.kt`
- Create: `app/src/main/java/com/aiopenpocket/agent/core/plan/model/PlanVersion.kt`
- Create: `app/src/main/java/com/aiopenpocket/agent/core/plan/model/PlanProgress.kt`
- Create: `app/src/main/java/com/aiopenpocket/agent/core/plan/validation/PlanStateMachine.kt`
- Test: `app/src/test/java/com/aiopenpocket/agent/core/plan/PlanStateMachineTest.kt`

**Interfaces:**
- Produces: all immutable domain types used by persistence/planner/executor/UI.
- Produces: `PlanStateMachine.transition(status, command): PlanStatus`.

- [ ] **Step 1: Write failing transition tests**

Cover at least:

```kotlin
assertEquals(AWAITING_APPROVAL, transition(DRAFT, ProposalValidated))
assertEquals(ACTIVE, transition(AWAITING_APPROVAL, Approve))
assertEquals(PAUSED, transition(ACTIVE, Pause))
assertEquals(ACTIVE, transition(PAUSED, Resume))
assertEquals(NEEDS_REVISION, transition(ACTIVE, MaterialChangeDetected))
assertFailsWith<InvalidPlanTransition> { transition(COMPLETED, Resume) }
```

Also assert that `PARTIAL` is a `PlanRunOutcome`, not a `PlanStatus`.

- [ ] **Step 2: Verify RED**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.aiopenpocket.agent.core.plan.PlanStateMachineTest"
```

Expected: FAIL because domain/state machine do not exist.

- [ ] **Step 3: Implement enums**

```kotlin
@Serializable enum class PlanHorizon { SINGLE_RUN, PROGRAM }
@Serializable enum class PlanStatus {
  DRAFT, NEEDS_INFO, AWAITING_APPROVAL, ACTIVE, PAUSED,
  NEEDS_REVISION, COMPLETED, CANCELLED, FAILED,
}
@Serializable enum class StepStatus {
  PENDING, READY, EXECUTING, VERIFYING, BLOCKED, COMPLETED, FAILED, SKIPPED,
}
@Serializable enum class PlanRunOutcome { PARTIAL, COMPLETED, FAILED, CANCELLED }
@Serializable enum class PlanAuthor { USER, AGENT }
@Serializable enum class EvidenceSource {
  TOOL_RESULT, MEMORY_FACT, USER_CONFIRMATION, MODEL_ASSESSMENT,
}
@Serializable enum class PlanEventType {
  CREATED, PROPOSED, APPROVED, REVISION_REQUESTED,
  STEP_STARTED, STEP_VERIFIED, STEP_BLOCKED,
  PLAN_PAUSED, PLAN_RESUMED, PLAN_COMPLETED, PLAN_CANCELLED, ERROR,
}
```

- [ ] **Step 4: Implement value objects**

```kotlin
@Serializable
data class PlanSchedule(
  val kind: ScheduleKind,
  val delayMinutes: Long? = null,
  val intervalMinutes: Long? = null,
)

@Serializable
data class CompletionRule(
  val type: CompletionRuleType,
  val config: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class CriterionVerifier(
  val type: VerifierType,
  val config: JsonObject = JsonObject(emptyMap()),
)
```

Define explicit enum values; do not encode types in free strings:

```text
ScheduleKind: ONE_TIME, PERIODIC
CompletionRuleType: TOOL_SUCCEEDED, EVIDENCE_PRESENT, USER_CONFIRMED, MODEL_ASSESSMENT
VerifierType: TOOL_RESULT, MEMORY_FACT, USER_CONFIRMATION, MODEL_ASSESSMENT
```

- [ ] **Step 5: Implement aggregate types**

Use the exact fields from the spec for `AgentPlan`, `PlanVersion`, `PlanStep`,
`PlanCriterion`, `PlanStepState`, `PlanEvidence`. Add:

```kotlin
@Serializable
data class PlanEvent(
  val id: String,
  val planId: String,
  val version: Int?,
  val stepId: String?,
  val type: PlanEventType,
  val payload: JsonObject,
  val createdAt: Long,
)

data class PlanSnapshot(
  val plan: AgentPlan,
  val version: PlanVersion,
  val stepStates: List<PlanStepState>,
  val evidence: List<PlanEvidence>,
)
```

- [ ] **Step 6: Implement state machine and run tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.aiopenpocket.agent.core.plan.PlanStateMachineTest"
```

Expected: PASS.

---

### Task 2: Validate plan graphs, scopes and material changes

**Files:**
- Create: `app/src/main/java/com/aiopenpocket/agent/core/plan/validation/PlanValidator.kt`
- Create: `app/src/main/java/com/aiopenpocket/agent/core/plan/validation/PlanChangeClassifier.kt`
- Test: `app/src/test/java/com/aiopenpocket/agent/core/plan/PlanValidatorTest.kt`
- Test: `app/src/test/java/com/aiopenpocket/agent/core/plan/PlanChangeClassifierTest.kt`

**Interfaces:**
- Consumes: `PlanVersion`, `AgentDefinition`.
- Produces: `validate(version, agent, requiredConstraints): List<PlanValidationIssue>`.
- Produces: `classify(old, new): PlanChangeKind`.

- [ ] **Step 1: Write failing validator tests**

Test:

```text
blank objective
duplicate step/criterion IDs
missing dependency
dependency cycle
expected tool outside agent.toolNames
more than 30 steps
more than 20 criteria
PROGRAM periodic interval below 15 minutes
PROGRAM scheduled step when agent lacks the heartbeat capability
criterion without valid verifier
default domain safety constraint removed
```

- [ ] **Step 2: Write failing classifier tests**

Material:

```text
objective/constraints/criteria changed
step add/remove/reorder
expectedTools changed
schedule changed
completionRule changed
```

Non-material progress fields are absent from `PlanVersion` and therefore cannot
produce a material diff.

- [ ] **Step 3: Verify RED**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.aiopenpocket.agent.core.plan.PlanValidatorTest" --tests "com.aiopenpocket.agent.core.plan.PlanChangeClassifierTest"
```

Expected: FAIL.

- [ ] **Step 4: Implement cycle detection**

Use deterministic DFS with `visiting`/`visited`; report the concrete cycle path:

```kotlin
data class PlanValidationIssue(
  val code: String,
  val message: String,
  val path: String? = null,
)
```

- [ ] **Step 5: Implement scope and safety validation**

`expectedTools` must be a subset of `agent.toolNames`; planner control schemas
are not legal step tools. `validate` receives `requiredConstraints: Set<String>`
as an explicit argument and verifies that every required constraint is present;
Task 4 supplies this set from the selected `PlanTemplate`.

A `PROGRAM` with any scheduled step is valid only when
`"heartbeat" in agent.toolNames`. `PlanScheduler` remains an internal service,
but the selected heartbeat capability is the user's consent for proactive
background execution.

- [ ] **Step 6: Run tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.aiopenpocket.agent.core.plan.PlanValidatorTest" --tests "com.aiopenpocket.agent.core.plan.PlanChangeClassifierTest"
```

Expected: PASS.

---

### Task 3: Persist immutable versions, progress, evidence and events

**Files:**
- Create: `app/src/main/java/com/aiopenpocket/agent/core/plan/persistence/AgentPlanEntity.kt`
- Create: `app/src/main/java/com/aiopenpocket/agent/core/plan/persistence/PlanVersionEntity.kt`
- Create: `app/src/main/java/com/aiopenpocket/agent/core/plan/persistence/PlanStepStateEntity.kt`
- Create: `app/src/main/java/com/aiopenpocket/agent/core/plan/persistence/PlanEvidenceEntity.kt`
- Create: `app/src/main/java/com/aiopenpocket/agent/core/plan/persistence/PlanEventEntity.kt`
- Create: `app/src/main/java/com/aiopenpocket/agent/core/plan/persistence/PlanDao.kt`
- Create: `app/src/main/java/com/aiopenpocket/agent/core/plan/persistence/PlanRepository.kt`
- Create: `app/src/main/java/com/aiopenpocket/data/database/AppDatabaseMigrations.kt`
- Modify: `app/src/main/java/com/example/data/database/AppDatabase.kt`
- Modify: `app/src/main/java/com/example/di/DatabaseBindings.kt`
- Modify: `app/build.gradle.kts`
- Test: `app/src/test/java/com/aiopenpocket/agent/core/plan/PlanRepositoryTest.kt`

**Interfaces:**
- Produces: transactional plan aggregate repository and observable flows.

- [ ] **Step 1: Write failing in-memory Room tests**

Required cases:

```kotlin
repository.saveProposal(plan, version)
assertEquals(AWAITING_APPROVAL, repository.snapshot(plan.id)!!.plan.status)

repository.approve(plan.id, version.version)
assertEquals(version.version, repository.snapshot(plan.id)!!.plan.approvedVersion)

repository.addEvidence(evidence)
assertEquals(evidence.id, repository.snapshot(plan.id)!!.evidence.single().id)
```

Also test:

```text
version snapshot cannot be updated after approval
wrong pending version cannot be approved
new revision leaves old approved version active
one active execution per conversation
archive/delete conversation cascades according to explicit FK policy
```

- [ ] **Step 2: Verify RED**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.aiopenpocket.agent.core.plan.PlanRepositoryTest"
```

Expected: FAIL.

- [ ] **Step 3: Add Room entities**

Use:

```text
agent_plans: current mutable metadata
plan_versions: (planId, version) + snapshotJson + approvedAt
plan_step_states: (planId, version, stepId)
plan_evidence: UUID primary key
plan_events: UUID primary key, ordered by createdAt
```

Index:

```text
agent_plans(conversationId, status)
agent_plans(agentId, status)
plan_step_states(planId, version, status, nextDueAt)
plan_evidence(planId, version, criterionId)
plan_events(planId, createdAt)
```

- [ ] **Step 4: Implement repository API**

```kotlin
interface PlanRepository {
  suspend fun snapshot(planId: String): PlanSnapshot?
  fun observe(planId: String): Flow<PlanSnapshot?>
  fun observeByConversation(conversationId: String): Flow<List<PlanSnapshot>>
  fun observeByAgent(agentId: String): Flow<List<PlanSnapshot>>
  suspend fun saveProposal(plan: AgentPlan, version: PlanVersion)
  suspend fun approve(planId: String, version: Int): PlanSnapshot
  suspend fun requestRevision(planId: String, feedback: String)
  suspend fun updateStepState(state: PlanStepState)
  suspend fun addEvidence(evidence: PlanEvidence)
  suspend fun appendEvent(event: PlanEvent)
  suspend fun pause(planId: String)
  suspend fun resume(planId: String)
  suspend fun cancel(planId: String)
}
```

All state transitions call `PlanStateMachine`; no DAO caller writes status
directly.

- [ ] **Step 5: Coordinate schema version**

Prerequisite builder tasks should leave DB at version 5
(`user_agents`, `user_skills`, `agent_hooks`). Bump to version 6 and export the
schema. Add `MIGRATION_5_6` creating all five plan tables. Keep
`fallbackToDestructiveMigration()` only for unsupported pre-release versions;
version 5 user agents/hooks must survive.

- [ ] **Step 6: Run repository and migration tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.aiopenpocket.agent.core.plan.PlanRepositoryTest"
.\gradlew.bat :app:assembleDebug
```

Expected: PASS / `BUILD SUCCESSFUL`.

---

### Task 4: Implement planner-only control schemas

**Files:**
- Create: `app/src/main/java/com/aiopenpocket/agent/core/plan/planner/PlanControlSchemas.kt`
- Create: `app/src/main/java/com/aiopenpocket/agent/core/plan/planner/PlanPromptBuilder.kt`
- Create: `app/src/main/java/com/aiopenpocket/agent/core/plan/planner/PlanPlanner.kt`
- Create: `app/src/main/java/com/aiopenpocket/agent/core/plan/template/PlanTemplate.kt`
- Create: `app/src/main/java/com/aiopenpocket/agent/core/plan/template/PlanTemplateRegistry.kt`
- Create: `app/src/main/assets/prompts/plan/planner.md`
- Test: `app/src/test/java/com/aiopenpocket/agent/core/plan/PlanPlannerTest.kt`

**Interfaces:**
- Consumes: `LlmClient`, `AgentResolver`, `PlanValidator`, templates.
- Produces: `PlanProposalResult`.

- [ ] **Step 1: Define outcomes and write failing parser tests**

```kotlin
sealed interface PlanProposalResult {
  data class Proposed(val version: PlanVersion, val explanation: String?) : PlanProposalResult
  data class NeedsInfo(val question: String) : PlanProposalResult
  data class Invalid(val issues: List<PlanValidationIssue>) : PlanProposalResult
  data class Failed(val message: String) : PlanProposalResult
}
```

Test exactly one recognized control call, malformed arguments, duplicate calls,
text-only response and provider error.

- [ ] **Step 2: Define internal schemas**

Create schemas for:

```text
submit_plan
request_plan_information
propose_plan_revision
```

Do not implement `Tool` and do not register them in `ToolRegistry`. They are
`FunctionSchema` values passed only by `PlanPlanner` to `LlmClient.streamChat`.

- [ ] **Step 3: Build planner prompt**

Prompt layers:

```text
immutable system_base
planner.md contract
agent role
selected domain template
agent tools as names/descriptions only
relevant profile/memory as untrusted data
existing approved/pending version for revision
user request/feedback
```

Never provide action tool schemas.

- [ ] **Step 4: Implement template model and registry**

```kotlin
@Serializable
data class PlanTemplate(
  val id: String,
  val agentId: String,
  val supportedHorizons: Set<PlanHorizon>,
  val requiredConstraints: List<String>,
  val suggestedSteps: List<PlanStepTemplate>,
  val suggestedCriteria: List<PlanCriterionTemplate>,
  val forbiddenToolNames: Set<String>,
)
```

```kotlin
interface PlanTemplateRegistry {
  suspend fun getForAgent(agentId: String?): PlanTemplate?
}
```

The asset-backed implementation returns `null` when an agent has no template;
it does not silently substitute another agent's template. Task 13 adds the
three initial assets using this frozen schema.

- [ ] **Step 5: Implement stream collection**

Collect text and control calls. Accept exactly one terminal control call.
`submit_plan` maps arguments to `PlanVersion`, merges required domain constraints,
then calls `PlanValidator`.

- [ ] **Step 6: Verify planner isolation**

Add a test asserting planner tool names equal:

```kotlin
setOf("submit_plan", "request_plan_information", "propose_plan_revision")
```

and have empty intersection with `AgentDefinition.toolNames`.

- [ ] **Step 7: Run tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.aiopenpocket.agent.core.plan.PlanPlannerTest"
```

Expected: PASS.

---

### Task 5: Replace magic approval with typed plan commands

**Files:**
- Create: `app/src/main/java/com/aiopenpocket/agent/core/plan/execution/PlanCommands.kt`
- Modify: `app/src/main/java/com/example/agent/loop/TurnManager.kt`
- Modify: `app/src/main/java/com/example/agent/loop/TurnSession.kt`
- Modify: `app/src/main/java/com/example/agent/loop/TurnCompletion.kt`
- Modify: `app/src/main/java/com/example/ui/chat/ChatIntent.kt`
- Modify: `app/src/main/java/com/example/ui/chat/ChatUiState.kt`
- Modify: `app/src/main/java/com/example/ui/chat/ChatViewModel.kt`
- Test: `app/src/test/java/com/aiopenpocket/agent/core/plan/PlanApprovalTest.kt`
- Test: `app/src/test/java/com/aiopenpocket/agent/loop/TurnManagerTest.kt`

**Interfaces:**
- Consumes: `PlanRepository`.
- Produces: typed approve/revision/pause/resume/cancel commands.

- [ ] **Step 1: Write failing command tests**

```kotlin
commands.approve(planId, version)
commands.requestChanges(planId, version, "Снизить нагрузку")
commands.pause(planId)
commands.resume(planId)
commands.cancel(planId)
```

Assert no conversation message is inserted for approval.

- [ ] **Step 2: Implement commands**

```kotlin
class PlanCommands @Inject constructor(
  private val repository: PlanRepository,
  private val scheduler: PlanScheduleSync,
) {
  suspend fun approve(planId: String, version: Int)
  suspend fun requestChanges(planId: String, version: Int, feedback: String)
  suspend fun pause(planId: String)
  suspend fun resume(planId: String)
  suspend fun cancel(planId: String)
}
```

Define the port in the same file:

```kotlin
interface PlanScheduleSync {
  suspend fun sync(planId: String)
  suspend fun cancel(planId: String)
}
```

Task 5 wires a no-op Metro implementation. Task 11 replaces that binding with
the WorkManager-backed `PlanScheduler`.

- [ ] **Step 3: Replace Chat intents**

```kotlin
data class ApprovePlan(val planId: String, val version: Int) : ChatIntent
data class RequestPlanChanges(
  val planId: String,
  val version: Int,
  val feedback: String,
) : ChatIntent
data class PausePlan(val planId: String) : ChatIntent
data class ResumePlan(val planId: String) : ChatIntent
data class CancelPlan(val planId: String) : ChatIntent
```

- [ ] **Step 4: Remove TurnManager approval hack**

Delete `TurnTexts.APPROVE_PLAN_MESSAGE`, `TurnManager.approvePlan`,
`lastPlanApproved` and `planAwaitingApproval`. Do not yet delete
`planApproved` from every execution signature; Task 10 performs the atomic
orchestrator migration after structured PLAN/GOAL exists.

- [ ] **Step 5: Run tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.aiopenpocket.agent.core.plan.PlanApprovalTest" --tests "com.aiopenpocket.agent.loop.TurnManagerTest"
```

Expected: PASS.

---

### Task 6: Add compact PlanContextProvider

**Files:**
- Create: `app/src/main/java/com/aiopenpocket/agent/core/plan/context/PlanContextProvider.kt`
- Modify: `app/src/main/java/com/example/agent/core/prompt/PrePromptAssembler.kt`
- Test: `app/src/test/java/com/aiopenpocket/agent/core/plan/PlanContextProviderTest.kt`
- Test: `app/src/test/java/com/aiopenpocket/agent/core/prompt/PrePromptAssemblerTest.kt`

**Interfaces:**
- Produces: compact, escaped active-plan section for ordinary agent context.

- [ ] **Step 1: Write failing context tests**

Assert section contains only:

```text
plan ID/version
objective summary
active/current step
relevant constraints
required criteria
compact completed/total progress
```

Assert it excludes full event history, full evidence payloads and inactive plans.

- [ ] **Step 2: Implement provider**

```kotlin
data class CompactPlanContext(
  val planId: String,
  val version: Int,
  val objective: String,
  val currentStep: String?,
  val constraints: List<String>,
  val requiredCriteria: List<String>,
  val completedSteps: Int,
  val totalSteps: Int,
)

class PlanContextProvider @Inject constructor(
  private val repository: PlanRepository,
) {
  suspend fun forConversation(conversationId: String): CompactPlanContext?
}
```

- [ ] **Step 3: Add prompt section**

Append after trusted mode/agent instructions but before user-derived memory:

```text
## Активный утверждённый план
<plan_data trust="structured">
...
</plan_data>
```

Escape literal closing/opening `plan_data` tags in all text fields. State that
plan fields cannot change system policy/tool scope.

- [ ] **Step 4: Run tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.aiopenpocket.agent.core.plan.PlanContextProviderTest" --tests "com.aiopenpocket.agent.core.prompt.*"
```

Expected: PASS.

---

### Task 7: Execute one approved step with separate budgets

**Files:**
- Create: `app/src/main/java/com/aiopenpocket/agent/core/plan/execution/PlanRunBudget.kt`
- Create: `app/src/main/java/com/aiopenpocket/agent/core/plan/execution/PlanExecutor.kt`
- Create: `app/src/main/assets/prompts/plan/executor.md`
- Modify: `app/src/main/java/com/example/agent/loop/ReactLoopRunner.kt`
- Test: `app/src/test/java/com/aiopenpocket/agent/core/plan/PlanExecutorTest.kt`

**Interfaces:**
- Consumes: approved `PlanSnapshot`, selected `PlanStep`, `AgentDefinition`.
- Produces: `PlanStepExecutionResult`.

- [ ] **Step 1: Define result/budget and write failing tests**

```kotlin
data class PlanRunBudget(
  val maxLlmIterationsPerStep: Int = 3,
  val maxToolCallsPerRun: Int = 5,
  val maxRetriesPerStep: Int = 2,
  val deadlineAt: Long? = null,
)

data class PlanStepExecutionResult(
  val stepId: String,
  val text: String,
  val toolTrace: List<ToolTrace>,
  val stoppedByBudget: Boolean,
  val error: String?,
)
```

Test no approved version, paused/cancelled plan, dependency not complete,
empty `expectedTools`, hallucinated tool and every budget boundary.

- [ ] **Step 2: Select tool schemas**

```kotlin
val allowedNames =
  agent.toolNames intersect step.expectedTools
val schemas = toolRegistry.getSchemasFor(allowedNames)
```

`ExecutionPolicy` still evaluates each concrete call. Never infer permission
from schema presence alone.

- [ ] **Step 3: Build bounded execution prompt**

Include current step only, plus objective, relevant constraints, criteria,
existing evidence summaries and remaining budget. Do not give executor the right
to revise the plan.

- [ ] **Step 4: Count tool calls**

Extend `ReactLoopRunner.run` with optional:

```kotlin
val maxToolCalls: Int = Int.MAX_VALUE
```

Stop before the next `ToolExecutor.execute` when budget is exhausted and return
an explicit budget result, preserving valid assistant/tool protocol pairs.

- [ ] **Step 5: Persist step start safely**

Before execution atomically move `READY → EXECUTING`, increment attempts and
append a plan event. Re-read approved version under transaction to prevent a
stale runner from starting after revision.

- [ ] **Step 6: Run tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.aiopenpocket.agent.core.plan.PlanExecutorTest" --tests "com.aiopenpocket.agent.loop.ReactLoopRunnerTest"
```

Expected: PASS.

---

### Task 8: Verify steps and require evidence for completion

**Files:**
- Create: `app/src/main/java/com/aiopenpocket/agent/core/plan/execution/DeterministicVerifierRegistry.kt`
- Create: `app/src/main/java/com/aiopenpocket/agent/core/plan/execution/PlanVerifier.kt`
- Create: `app/src/main/assets/prompts/plan/verifier.md`
- Test: `app/src/test/java/com/aiopenpocket/agent/core/plan/PlanVerifierTest.kt`

**Interfaces:**
- Consumes: execution result, completion rules, criteria and repositories.
- Produces: evidence plus `VerificationOutcome`.

- [ ] **Step 1: Define outcomes and write failing tests**

```kotlin
sealed interface VerificationOutcome {
  data class Proven(val evidence: List<PlanEvidence>) : VerificationOutcome
  data class Retryable(val reason: String) : VerificationOutcome
  data class NeedsInfo(val question: String) : VerificationOutcome
  data class NeedsRevision(val reason: String) : VerificationOutcome
  data class Failed(val reason: String) : VerificationOutcome
}
```

Required tests:

```text
successful expected tool produces TOOL_RESULT evidence
failed tool does not prove criterion
memory fact reference must exist
user confirmation required when verifier says USER_CONFIRMATION
MODEL_ASSESSMENT is marked weak
required criterion without evidence blocks COMPLETED
verifier receives zero action schemas
```

- [ ] **Step 2: Implement deterministic verifiers**

Registry keys:

```text
TOOL_RESULT
MEMORY_FACT
USER_CONFIRMATION
```

Each verifier returns references to persisted source records/tool trace IDs,
not model-generated prose.

- [ ] **Step 3: Implement model verifier fallback**

Use `LlmClient` without tools and strict internal response schema. Persist
`MODEL_ASSESSMENT` only for criteria explicitly configured with that verifier.
Never allow it to prove a deterministic criterion.

- [ ] **Step 4: Persist verification atomically**

Transaction order:

```text
EXECUTING → VERIFYING
insert evidence
evaluate step completion
VERIFYING → COMPLETED / BLOCKED / FAILED / READY
evaluate whole-plan completion
append event
```

- [ ] **Step 5: Run tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.aiopenpocket.agent.core.plan.PlanVerifierTest"
```

Expected: PASS.

---

### Task 9: Add plan lifecycle events to HookEngine

**Files:**
- Modify: `app/src/main/java/com/aiopenpocket/agent/core/hook/AgentHook.kt`
- Modify: existing `app/src/main/java/com/aiopenpocket/agent/core/hook/HookEngine.kt`
- Modify: `app/src/main/java/com/aiopenpocket/agent/core/agent/AgentValidator.kt`
- Modify: `app/src/main/java/com/aiopenpocket/ui/agents/builder/AgentBuilderScreen.kt`
- Create: `app/src/main/java/com/aiopenpocket/agent/core/plan/execution/PlanHookContext.kt`
- Test: `app/src/test/java/com/aiopenpocket/agent/core/plan/PlanHookIntegrationTest.kt`
- Test: `app/src/test/java/com/aiopenpocket/ui/agents/builder/AgentBuilderPlanHooksTest.kt`

**Interfaces:**
- Consumes: existing builder `HookEngine`.
- Produces: plan events and restricted action matrix.

- [ ] **Step 1: Extend events**

```kotlin
enum class HookEvent {
  BEFORE_TURN, AFTER_TURN, BEFORE_TOOL, AFTER_TOOL, ON_ERROR, ON_COMPLETE,
  ON_PLAN_CREATED, ON_PLAN_APPROVED, BEFORE_PLAN_STEP, AFTER_PLAN_STEP,
  ON_PLAN_BLOCKED, ON_PLAN_COMPLETED,
}
```

- [ ] **Step 2: Define typed context**

```kotlin
data class PlanHookContext(
  val planId: String,
  val planVersion: Int,
  val stepId: String?,
  val planStatus: PlanStatus,
  val stepStatus: StepStatus?,
  val evidenceIds: List<String>,
  val background: Boolean,
)
```

Do not expose mutable repositories/DAOs to hook evaluation.

- [ ] **Step 3: Define action matrix**

```text
ON_PLAN_CREATED: LOG
ON_PLAN_APPROVED: LOG
BEFORE_PLAN_STEP: ADD_INSTRUCTION, REQUIRE_APPROVAL, DENY, LOG, STOP
AFTER_PLAN_STEP: LOG
ON_PLAN_BLOCKED: LOG
ON_PLAN_COMPLETED: LOG
```

`REQUIRE_APPROVAL` in background becomes blocked; it never suspends WorkManager
waiting for UI.

- [ ] **Step 4: Expose plan events in the builder**

The existing declarative hook editor lists the six new plan events. Its action
picker is derived from the same action matrix as `AgentValidator`, so UI and
publish validation cannot diverge. Hide plan events when both PLAN and GOAL are
absent from `AgentDefinition.allowedModes`.

- [ ] **Step 5: Write ordering tests**

Assert:

```text
BEFORE_PLAN_STEP
→ PlanExecutor
→ low-level turn/tool hooks and policy
→ PlanVerifier
→ evidence/progress commit
→ AFTER_PLAN_STEP
→ completion/blocked event
```

Assert `DENY > ALLOW`, no recursion from `ON_ERROR`, and hook cannot add
evidence or alter version.

- [ ] **Step 6: Run tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.aiopenpocket.agent.core.plan.PlanHookIntegrationTest" --tests "com.aiopenpocket.agent.core.hook.HookEngineTest"
.\gradlew.bat :app:testDebugUnitTest --tests "com.aiopenpocket.ui.agents.builder.AgentBuilderPlanHooksTest"
```

Expected: PASS.

---

### Task 10: Build PlanRunner and migrate PLAN/GOAL orchestration

**Files:**
- Create: `app/src/main/java/com/aiopenpocket/agent/core/plan/execution/PlanRunner.kt`
- Create: `app/src/main/java/com/aiopenpocket/agent/loop/StructuredPlanOrchestrator.kt`
- Modify: `app/src/main/java/com/example/di/TurnModule.kt`
- Modify: `app/src/main/java/com/example/agent/loop/TurnPorts.kt`
- Modify: `app/src/main/java/com/example/agent/loop/TurnManager.kt`
- Modify: `app/src/main/java/com/example/agent/loop/TurnSession.kt`
- Modify: `app/src/main/java/com/example/agent/loop/AgentMode.kt`
- Test: `app/src/test/java/com/aiopenpocket/agent/core/plan/PlanRunnerTest.kt`
- Test: `app/src/test/java/com/aiopenpocket/agent/loop/StructuredPlanOrchestratorTest.kt`

**Interfaces:**
- Produces: PLAN proposal flow and GOAL step execution flow.

- [ ] **Step 1: Define runner contract**

```kotlin
data class PlanRunRequest(
  val conversationId: String,
  val planId: String?,
  val requestedStepId: String? = null,
  val background: Boolean = false,
  val budget: PlanRunBudget = PlanRunBudget(),
)

data class PlanRunResult(
  val planId: String,
  val outcome: PlanRunOutcome,
  val executedStepIds: List<String>,
  val blockedReason: String?,
)
```

- [ ] **Step 2: Write failing selection tests**

Test topological runnable selection, due date, paused status, one active execution
per conversation, version snapshot, budget partial outcome and all criteria
complete.

- [ ] **Step 3: Implement PlanRunner loop**

For each allowed step:

```text
reload approved snapshot
select one runnable/due step
BEFORE_PLAN_STEP
execute
verify/persist
AFTER_PLAN_STEP
decrement budget
stop on blocked/revision/complete/exhausted
```

Use per-conversation `Mutex` or repository lease so foreground and heartbeat
cannot execute the same plan concurrently.

- [ ] **Step 4: Implement StructuredPlanOrchestrator**

```kotlin
fun propose(
  conversationId: String,
  userMessage: String,
  provider: LlmProvider,
): Flow<AgentEvent>

fun executeGoal(
  conversationId: String,
  planId: String?,
  provider: LlmProvider,
): Flow<AgentEvent>
```

If GOAL has no approved plan, call planner and stop at proposal.

- [ ] **Step 5: Dispatch modes**

In `TurnModule`:

```text
CHAT/TUTOR → AgentLoopOrchestrator
REFLECT → ReflectionOrchestrator
PLAN → StructuredPlanOrchestrator.propose
GOAL → StructuredPlanOrchestrator.executeGoal
```

Remove `planApproved` and `goalMaxIterations` from `TurnExecutor`, `TurnManager`,
`TurnSession` and retry context. Retry stores `planId` and `PlanRunBudget`.

- [ ] **Step 6: Add plan AgentEvents**

```kotlin
data class PlanProposed(val planId: String, val version: Int) : AgentEvent
data class PlanProgressed(val planId: String, val stepId: String) : AgentEvent
data class PlanBlocked(val planId: String, val reason: String) : AgentEvent
```

TurnManager persists a concise assistant message with `agent_plan`/plan ID card
for proposal/progress terminal events.

- [ ] **Step 7: Run orchestration tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.aiopenpocket.agent.core.plan.PlanRunnerTest" --tests "com.aiopenpocket.agent.loop.StructuredPlanOrchestratorTest" --tests "com.aiopenpocket.agent.loop.TurnManagerTest"
```

Expected: PASS.

---

### Task 11: Refactor heartbeat scheduling into a typed PlanScheduler

**Files:**
- Create: `app/src/main/java/com/aiopenpocket/agent/core/plan/schedule/PlanScheduler.kt`
- Create: `app/src/main/java/com/aiopenpocket/agent/core/plan/schedule/HeartbeatScheduler.kt`
- Modify: `app/src/main/java/com/example/agent/core/memory/entity/HeartbeatEntity.kt`
- Modify: `app/src/main/java/com/example/agent/core/memory/dao/HeartbeatDao.kt`
- Modify: `app/src/main/java/com/example/agent/tools/HeartbeatTool.kt`
- Modify: `app/src/main/java/com/example/agent/workers/HeartbeatRunner.kt`
- Modify: `app/src/main/java/com/example/data/database/AppDatabase.kt`
- Test: `app/src/test/java/com/aiopenpocket/agent/core/plan/PlanSchedulerTest.kt`
- Test: `app/src/test/java/com/aiopenpocket/agent/workers/HeartbeatRunnerTest.kt`

**Interfaces:**
- Produces: typed WorkManager scheduling and plan-step resume.

- [ ] **Step 1: Add target model**

```kotlin
enum class HeartbeatTargetType { GENERIC_AGENT, PLAN_STEP }

data class PlanHeartbeatTarget(
  val planId: String,
  val planVersion: Int,
  val stepId: String,
)
```

Add nullable Room fields:

```text
targetType
planId
planVersion
planStepId
```

Existing rows migrate to `GENERIC_AGENT`.

- [ ] **Step 2: Extract WorkManager mechanics**

Move enqueue/cancel construction from `HeartbeatTool` into:

```kotlin
interface HeartbeatScheduler {
  suspend fun schedule(entity: HeartbeatEntity)
  suspend fun cancel(heartbeatId: String)
}
```

`HeartbeatTool` remains the LLM-facing generic scheduler. `PlanScheduler` calls
the same service directly and does not invoke a tool through the model.

- [ ] **Step 3: Implement PlanScheduler.sync**

```kotlin
interface PlanScheduler {
  suspend fun sync(planId: String)
  suspend fun cancel(planId: String)
}
```

For each approved scheduled step, create/update one heartbeat keyed by
plan/version/step. Cancel stale version rows. Never schedule DRAFT,
AWAITING_APPROVAL, PAUSED, CANCELLED or COMPLETED plans.

`PlanScheduler` implements the `PlanScheduleSync` port from Task 5; remove the
temporary no-op Metro binding.

- [ ] **Step 4: Branch HeartbeatRunner**

```kotlin
when (hb.targetType) {
  GENERIC_AGENT -> fireAgent(hb, attempt)
  PLAN_STEP -> firePlanStep(hb, attempt)
}
```

`firePlanStep` validates:

```text
agent exists and enabled
plan status ACTIVE
plan approvedVersion == heartbeat.planVersion
step exists and is due/runnable
no concurrent execution lease
```

Then calls `PlanRunner` with `background = true`.

- [ ] **Step 5: Enforce background approval behavior**

If hook/policy returns approval required:

```text
step → BLOCKED
plan event → approval_required_background
notification → open plan detail
WorkManager result → success (not retry loop)
```

Only transient DB/network/provider failures return retry.

- [ ] **Step 6: Add migration**

Task 3 establishes DB version 6. Add `MIGRATION_6_7` for heartbeat target
columns and bump `AppDatabase` to version 7. Never use destructive migration for
existing user agents/plans.

- [ ] **Step 7: Run scheduler tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.aiopenpocket.agent.core.plan.PlanSchedulerTest" --tests "com.aiopenpocket.agent.workers.HeartbeatRunnerTest" --tests "com.aiopenpocket.agent.tools.HeartbeatToolTest"
```

Expected: PASS.

---

### Task 12: Render live plan cards and plan detail UI

**Files:**
- Create: `app/src/main/java/com/aiopenpocket/ui/plan/PlanCard.kt`
- Create: `app/src/main/java/com/aiopenpocket/ui/plan/PlanDetailScreen.kt`
- Create: `app/src/main/java/com/aiopenpocket/ui/plan/PlanDetailViewModel.kt`
- Create: `app/src/main/java/com/aiopenpocket/ui/plan/PlanListScreen.kt`
- Create: `app/src/main/java/com/aiopenpocket/ui/plan/PlanListViewModel.kt`
- Modify: `app/src/main/java/com/example/ui/chat/DrivenUiEngine.kt`
- Modify: `app/src/main/java/com/example/ui/chat/ChatScreen.kt`
- Modify: `app/src/main/java/com/example/ui/chat/ChatViewModel.kt`
- Modify: `app/src/main/java/com/example/ui/agents/AgentInspectorSheet.kt`
- Modify: `app/src/main/java/com/example/MainActivity.kt`
- Test: `app/src/test/java/com/aiopenpocket/ui/plan/PlanCardTest.kt`
- Test: `app/src/test/java/com/aiopenpocket/ui/plan/PlanDetailViewModelTest.kt`
- Test: relevant Roborazzi screenshots.

**Interfaces:**
- Consumes: repository flows and typed commands.
- Produces: live proposal/progress/detail/list UI.

- [ ] **Step 1: Extend card parser**

Add:

```kotlin
data class AgentPlanCard(val planId: String) : DrivenUiCard()
```

Card spec:

```json
{"template":"agent_plan","params":{"plan_id":"<uuid>"}}
```

Parser rejects blank `plan_id`.
`agent_plan` is an internal application card, not a `render_card` template:
the model cannot forge it through `RenderCardTool`, and it is not controlled by
`allowedWidgetTemplates`.

- [ ] **Step 2: Render live state**

Like `ReminderCardView`, `AgentPlanCardView` subscribes to
`PlanRepository.observe(planId)`. Show:

```text
objective/horizon/version/status
steps and current step
required criteria
progress
next due date
Approve / Request changes / Pause / Resume / Cancel
```

Never trust fields copied into card JSON; render repository state by ID.

- [ ] **Step 3: Make plan cards interactive in ordinary chat**

Add `AgentPlanCard` to the existing interactive-card allowlist in
`MessageBubble`; plan actions must work outside tutorial mode.

- [ ] **Step 4: Add plan routes**

```text
plans/{agentId}
plan/{planId}
```

Agent Inspector gets a real Plans section with active/paused/completed counts
and navigation. Do not mix this with the hooks placeholder.

- [ ] **Step 5: Remove plain-chat-only mode restriction**

Show PLAN/GOAL chips according to `AgentDefinition.allowedModes`, including
preset/custom agent chats. Do not show a mode that the definition excludes.

- [ ] **Step 6: Add Compose and screenshot tests**

Test tags:

```text
plan_card_<id>
plan_approve
plan_request_changes
plan_pause
plan_resume
plan_cancel
plan_open_detail
```

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.aiopenpocket.ui.plan.*" --tests "com.aiopenpocket.ui.chat.DrivenUiEngineTest"
.\gradlew.bat :app:verifyRoborazziDebug
```

Expected: PASS.

---

### Task 13: Add budget, nutrition and languages plan templates

**Files:**
- Create: `app/src/main/assets/plan_templates/budget.json`
- Create: `app/src/main/assets/plan_templates/nutrition.json`
- Create: `app/src/main/assets/plan_templates/languages.json`
- Test: `app/src/test/java/com/aiopenpocket/agent/core/plan/PlanTemplateValidationTest.kt`

**Interfaces:**
- Produces: validated domain constraints/examples for planner prompt.

- [ ] **Step 1: Write failing asset contract tests**

Load the three expected asset paths through `PlanTemplateRegistry` and assert
their `id`, `agentId`, supported horizons, required constraints, suggested
steps/criteria and forbidden tools. Templates guide planning; they are not plans
and do not auto-publish.

- [ ] **Step 2: Author budget template**

Required constraints:

```text
never invent amounts
never initiate financial transactions
distinguish missing data from zero spending
show period/currency/source for totals
```

Evidence uses memory/tool results. Suggested recurring checkpoint: weekly
spending reconciliation.

- [ ] **Step 3: Author nutrition template**

Required constraints:

```text
no diagnosis/treatment
respect allergies and explicit restrictions
urgent symptoms stop the plan and advise professional help
material diet changes require approval
```

Evidence uses meal log and user confirmation.

- [ ] **Step 4: Author languages template**

Suggested steps:

```text
baseline
schedule
SRS sessions
checkpoint
revision after checkpoint
```

Evidence uses `srs_tool` results and user-confirmed speaking/writing checkpoints.

- [ ] **Step 5: Add validation tests**

Each template must:

```text
match an existing agent ID
reference registered tools only
contain at least one required constraint
contain no cyclic suggested dependencies
use both SINGLE_RUN and/or PROGRAM as declared
```

- [ ] **Step 6: Run tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.aiopenpocket.agent.core.plan.PlanTemplateValidationTest"
```

Expected: PASS.

---

### Task 14: Recover safely after process death and avoid duplicate side effects

**Files:**
- Create: `app/src/main/java/com/aiopenpocket/agent/core/plan/execution/PlanRecovery.kt`
- Modify: `app/src/main/java/com/aiopenpocket/agent/core/plan/persistence/PlanRepository.kt`
- Modify: `app/src/main/java/com/aiopenpocket/agent/core/plan/execution/PlanExecutor.kt`
- Modify: `app/src/main/java/com/aiopenpocket/agent/core/plan/execution/PlanRunner.kt`
- Test: `app/src/test/java/com/aiopenpocket/agent/core/plan/PlanRecoveryTest.kt`

**Interfaces:**
- Produces: deterministic startup recovery and idempotency evidence rules.

- [ ] **Step 1: Write recovery matrix tests**

```text
READY remains READY
EXECUTING with no tool trace → READY if retry budget remains
EXECUTING with completed side-effect trace → VERIFYING, never re-execute
VERIFYING → rerun verifier only
BLOCKED remains blocked
version mismatch → NEEDS_REVISION
expired deadline → blocked event
```

- [ ] **Step 2: Persist execution IDs**

Each attempt gets `executionId`. Tool traces/evidence reference it. Before a
side-effect call, append intent event; after result, append completion event.
Recovery checks both before retry.

- [ ] **Step 3: Implement startup recovery**

```kotlin
class PlanRecovery @Inject constructor(
  private val repository: PlanRepository,
  private val scheduler: PlanScheduler,
) {
  suspend fun recover()
}
```

Invoke once from app initialization after Room is available. Recovery never
starts foreground LLM work automatically; it repairs states and schedules due
heartbeats.

- [ ] **Step 4: Run tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.aiopenpocket.agent.core.plan.PlanRecoveryTest"
```

Expected: PASS.

---

### Task 15: Remove legacy prompt-only PLAN/GOAL contract

**Files:**
- Modify: `app/src/main/java/com/example/agent/loop/AgentMode.kt`
- Modify: `app/src/main/java/com/example/agent/loop/TurnCompletion.kt`
- Modify: `app/src/main/java/com/example/agent/core/prompt/PrePromptAssembler.kt`
- Delete after replacement: `app/src/main/assets/prompts/modes/plan_planning.md`
- Delete after replacement: `app/src/main/assets/prompts/modes/plan_executing.md`
- Delete after replacement: `app/src/main/assets/prompts/modes/goal.md`
- Modify: `app/src/test/java/com/aiopenpocket/agent/loop/AgentModeTest.kt`
- Modify: `app/src/test/java/com/aiopenpocket/agent/loop/TurnCompletionTest.kt`
- Delete/replace: `app/src/test/java/com/aiopenpocket/agent/core/prompt/PrePromptAssemblerModeAssetsTest.kt`

**Interfaces:**
- Consumes: complete StructuredPlanOrchestrator and UI.
- Produces: one non-duplicated PLAN/GOAL implementation.

- [ ] **Step 1: Add regression tests before deletion**

Assert:

```text
PLAN invokes planner-only schemas
GOAL requires/creates typed plan
CHAT/REFLECT/TUTOR behavior unchanged
no NEED_INFO marker parsing
no magic approval message
no goalMaxIterations prompt suffix
```

- [ ] **Step 2: Simplify AgentMode contract**

Keep `AgentMode` enum for UI/routing. Remove `PromptMode.PLAN_PLANNING`,
`PLAN_EXECUTING`, `GOAL` and legacy `resolveTurnExecutionPlan` branches used only
by structured modes. CHAT/TUTOR behavior remains explicit.

- [ ] **Step 3: Delete legacy markers**

Remove:

```text
[[NEED_INFO]]
TurnTexts.APPROVE_PLAN_MESSAGE
planAwaitingApproval
planApproved
goalMaxIterations
GOAL_ITERATION_LIMIT_PARTIAL
```

Only after tests prove no production reference remains:

```powershell
rg "NEED_INFO|APPROVE_PLAN_MESSAGE|planAwaitingApproval|planApproved|goalMaxIterations|PromptMode\\.GOAL|PLAN_EXECUTING" app/src
```

Expected: no production matches.

- [ ] **Step 4: Run mode regressions**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.aiopenpocket.agent.loop.*" --tests "com.aiopenpocket.agent.core.prompt.*"
```

Expected: PASS.

---

### Task 16: Security, documentation and final verification

**Files:**
- Create: `app/src/test/java/com/aiopenpocket/agent/security/StructuredPlanSecurityTest.kt`
- Modify: `AGENTS.md`
- Modify: `traceability.yaml`
- Modify: `docs/superpowers/specs/2026-07-12-structured-plan-engine-design.md` status only.

**Interfaces:**
- Produces: release evidence and synchronized documentation.

- [ ] **Step 1: Add consolidated security regressions**

Test:

```text
planner cannot receive action tools
step cannot call tool outside agent/step scope
plan text cannot widen policy
hook cannot mutate version/evidence/schedule
background approval cannot suspend
stale heartbeat cannot run old version
foreign agent cannot resume another agent's plan
required criterion cannot complete without evidence
process recovery does not replay side effect
MODEL_ASSESSMENT cannot prove deterministic criterion
```

- [ ] **Step 2: Run full unit suite**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Verify screenshots and APK**

Run:

```powershell
.\gradlew.bat :app:verifyRoborazziDebug
.\gradlew.bat :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Run traceability**

Run:

```powershell
traxer check
```

Expected: no broken links/features without code; stale rationale reviewed before
`traxer bless`.

- [ ] **Step 5: Manual smoke test**

1. Budget: create PROGRAM, approve, complete weekly reconciliation, inspect
   evidence and next heartbeat.
2. Nutrition: create plan with restriction, request material revision, confirm
   old approved version remains active until approval.
3. Languages: execute SRS step, verify tool evidence and checkpoint progress.
4. Force-stop/relaunch app during VERIFYING; confirm no duplicate tool action.
5. Make heartbeat stale by approving revision; confirm old step does not run.
6. Trigger plan hook `STOP`; confirm step becomes BLOCKED with audit event.
7. Trigger approval-required action from background; confirm notification and no
   suspended worker.
8. Open plan card in ordinary agent chat and use approve/pause/resume.
9. Confirm CHAT and REFLECT still work for plain and scoped agent chats.

- [ ] **Step 6: Update docs**

Document:

```text
typed PLAN/GOAL lifecycle
Room plan tables and DB version
evidence semantics
plan hooks versus runtime hooks
typed heartbeat targets
domain templates
remaining out-of-scope items
```

Set design spec status to implemented only after every verification command and
manual case has evidence.

## Recommended Commit Boundaries

1. `feat(plan): define structured plan domain`
2. `feat(plan): validate plan graph and revisions`
3. `feat(plan): persist versions progress and evidence`
4. `feat(plan): add planner control schemas`
5. `refactor(plan): replace message approval with commands`
6. `feat(prompt): add compact approved plan context`
7. `feat(plan): execute scoped plan steps`
8. `feat(plan): verify steps with evidence`
9. `feat(hooks): add plan lifecycle events`
10. `feat(plan): route PLAN and GOAL through structured engine`
11. `feat(heartbeat): resume typed plan steps`
12. `feat(plan): render live plan UI`
13. `feat(plan): add domain planning templates`
14. `fix(plan): recover interrupted executions safely`
15. `refactor(plan): remove prompt-only mode contract`
16. `docs(plan): verify structured automation`

Each implementation commit includes:

```text
Trace: <structured-plan-engine-id-from-task-0>
```

When a commit also changes heartbeat/hooks/builder behavior, include the
corresponding existing trace trailer as well.

## Definition of Done

- PLAN creates typed proposal using internal schemas and zero action tools.
- Approval is a repository command and freezes a version.
- GOAL executes approved steps with separate LLM/tool/retry budgets.
- Required criteria need persisted evidence.
- Material revisions return to approval while old approved version stays active.
- Plan hooks fire in deterministic order and cannot bypass policy/mutate plan.
- PROGRAM uses typed heartbeat `planId/version/stepId`.
- Stale heartbeat and background approval are safe.
- Budget, nutrition and languages templates are validated and usable.
- Plan cards work in scoped agent chats.
- Restart recovery does not replay completed side effects.
- Legacy prompt-only markers/assets are removed after replacement.
- CHAT/REFLECT/TUTOR regressions pass.
- `:app:testDebugUnitTest`, `:app:verifyRoborazziDebug`,
  `:app:assembleDebug` and `traxer check` pass.
