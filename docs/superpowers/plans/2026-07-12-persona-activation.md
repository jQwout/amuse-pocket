# Persona Activation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Сделать выбранную в онбординге или Settings персону единым, заметным и устойчивым источником стиля для каждого следующего хода обычного чата.

**Architecture:** `PersonaCatalog` становится единственным источником контента персоны. Чистый `PersonaPromptBuilder` переводит тон, принципы, многословность и примеры голоса в system prompt; `PrePromptAssembler` на каждом ходе читает глобальный `defaultPersonaId`, но для чатов мини-агентов по-прежнему использует только их собственную роль. `ConversationEntity.personaId` остаётся в схеме для совместимости, но больше не влияет на prompt.

**Tech Stack:** Kotlin 2.3, Metro DI (конструкторная инъекция уже есть), Room, DataStore Preferences, JUnit 4, Robolectric, kotlinx-coroutines-test.

## Global Constraints

- Scope: только активация и устойчивость персоны. Rolling summary и переработка фактического long-context не входят в этот план.
- Семантика: персона глобальная; изменение в Settings применяется к существующему обычному чату со следующего хода.
- Мини-агенты: `MiniAgent.systemPrompt` полностью заменяет пользовательскую персону, как сейчас.
- Уроки без `agentId` используют выбранную персону, но их tutorial/mode инструкции сохраняются.
- `ConversationEntity.personaId` и `Conversation.personaId` не удалять и миграцию Room не создавать.
- Новых зависимостей не добавлять.
- Новый production-файл размещать по совпадающему package-пути: `app/src/main/java/com/aiopenpocket/agent/core/persona/`.
- Трассировка: существующая фича `swappable-persona-e5e65e`; новый production-файл получает `// @trace swappable-persona-e5e65e`.
- Перед каждым коммитом запускать `traxer check`; commit message должен содержать трейлер `Trace: swappable-persona-e5e65e`.
- Точечные тесты: `.\gradlew.bat :app:testDebugUnitTest --tests "<FQCN>"`.
- Финальная проверка: `.\gradlew.bat :app:testDebugUnitTest`, `.\gradlew.bat :app:compileDebugKotlin`, `traxer check`.

---

### Task 1: Сделать PersonaCatalog полным источником поведения

**Files:**
- Modify: `app/src/main/java/com/example/agent/core/persona/PersonaCatalog.kt`
- Create: `app/src/test/java/com/aiopenpocket/agent/core/persona/PersonaCatalogTest.kt`

**Interfaces:**
- Consumes: существующие `PersonaInfo`, `PersonaStats`, `PersonaQuote`, `PersonaCatalog.all`.
- Produces: `PersonaInfo.principles: List<String>`; Task 2 передаёт это поле в prompt.

- [ ] **Step 1: Написать падающий контрактный тест каталога**

Создать `PersonaCatalogTest.kt`:

```kotlin
package com.aiopenpocket.agent.core.persona

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaCatalogTest {

  @Test
  fun `catalog has seven unique valid personas`() {
    val personas = PersonaCatalog.all

    assertEquals(7, personas.size)
    assertEquals(personas.size, personas.map { it.id }.distinct().size)
    personas.forEach { persona ->
      assertTrue("${persona.id}: tone is blank", persona.tone.isNotBlank())
      assertTrue("${persona.id}: principles are empty", persona.principles.isNotEmpty())
      assertTrue(
        "${persona.id}: blank principle",
        persona.principles.all { it.isNotBlank() },
      )
      assertTrue("${persona.id}: fewer than two voice examples", persona.quotes.size >= 2)
      assertTrue(
        "${persona.id}: incomplete voice example",
        persona.quotes.all { it.situation.isNotBlank() && it.line.isNotBlank() },
      )
      with(persona.stats) {
        listOf(talkativeness, skepticism, criticism, proactivity, humor, empathy)
          .forEach { value -> assertTrue("${persona.id}: stat $value outside 0..100", value in 0..100) }
      }
    }
  }

  @Test
  fun `default persona exists`() {
    assertTrue(PersonaCatalog.findById("sharp_partner") != null)
  }
}
```

- [ ] **Step 2: Запустить тест и подтвердить RED**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.aiopenpocket.agent.core.persona.PersonaCatalogTest"
```

Expected: compilation FAIL — `Unresolved reference: principles`.

- [ ] **Step 3: Добавить поле principles и полный контент семи персон**

В `PersonaInfo` между `tone` и `stats` добавить:

```kotlin
  val principles: List<String>,
```

В каждый `PersonaInfo` после `tone` добавить соответствующий список:

```kotlin
// silent_butler
principles = listOf(
  "Если можно ответить одним словом — ответь одним словом.",
  "Не добавляй приветствия, пояснения и предложения продолжить разговор без необходимости.",
  "Не навязывай мнение и follow-up: результат важнее описания процесса.",
),

// attentive_secretary
principles = listOf(
  "Будь вежливым и обращайся к пользователю на «ты».",
  "Если без уточнения есть реальный риск ошибиться — задай один точечный вопрос, не анкету.",
  "Перед неоднозначным действием подтверди выбранный вариант и коротко назови последствия.",
),

// sharp_partner
principles = listOf(
  "Давай прямую оценку сразу, без смягчающих вступлений.",
  "Не извиняйся и не благодари за вопрос ради вежливости.",
  "Если не согласен — скажи это прямо и объясни одним конкретным аргументом.",
  "Сначала сделай то, что можно сделать безопасно, затем предложи один полезный следующий шаг.",
  "Не переспрашивай, если недостающее можно разумно и безопасно предположить.",
),

// egalitarian_partner
principles = listOf(
  "Показывай варианты и реальные trade-offs, не дави на пользователя.",
  "Решения с заметными последствиями оставляй пользователю.",
  "Не выдавай своё предпочтение за единственно правильный выбор.",
),

// friendly_companion
principles = listOf(
  "Будь тёплым живым собеседником, а не безличным интерфейсом.",
  "Можно поддержать разговор и поинтересоваться делами, если это уместно.",
  "Используй эмодзи умеренно и только когда они подходят ситуации.",
),

// ironic_assistant
principles = listOf(
  "Используй лёгкий сухой юмор без подхалимства.",
  "Ирония должна быть дружеской и не направленной на уязвимости пользователя.",
  "Шутка не должна заменять полезный ответ или скрывать важный риск.",
),

// strict_coach
principles = listOf(
  "Говори прямо и не маскируй слабое решение похвалой.",
  "Не поддерживай оправдания и прокрастинацию; возвращай разговор к проверяемому действию.",
  "Указывай на повторяющийся паттерн только когда он подтверждён контекстом.",
  "Завершай конкретным следующим шагом, сроком или критерием результата.",
),
```

Не переносить из старого `attentive_secretary.md` фразу про «проблемы с фоновыми процессами»: это инфраструктурная деталь, а не черта характера.

- [ ] **Step 4: Запустить тест и подтвердить GREEN**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.aiopenpocket.agent.core.persona.PersonaCatalogTest"
```

Expected: BUILD SUCCESSFUL, 2 tests pass.

- [ ] **Step 5: Проверить трассировку**

Run: `traxer check`

Expected: без broken link и без feature-without-code для `swappable-persona-e5e65e`.

- [ ] **Step 6: Commit**

```powershell
git add app/src/main/java/com/example/agent/core/persona/PersonaCatalog.kt app/src/test/java/com/aiopenpocket/agent/core/persona/PersonaCatalogTest.kt
git commit -m @'
feat(persona): сделать каталог источником поведения

Персона теперь хранит явные принципы, которые можно одинаково использовать
в карточке выбора и системном промпте.

Trace: swappable-persona-e5e65e
'@
```

---

### Task 2: Добавить чистый PersonaPromptBuilder

**Files:**
- Create: `app/src/main/java/com/aiopenpocket/agent/core/persona/PersonaPromptBuilder.kt`
- Create: `app/src/test/java/com/aiopenpocket/agent/core/persona/PersonaPromptBuilderTest.kt`

**Interfaces:**
- Consumes: `PersonaInfo` из Task 1.
- Produces: `object PersonaPromptBuilder { fun build(persona: PersonaInfo): String }`.
- Contract: возвращает тело секции персоны без заголовка `## Персона`; заголовком управляет `PrePromptAssembler`.

- [ ] **Step 1: Написать падающие тесты builder**

Создать `PersonaPromptBuilderTest.kt`:

```kotlin
package com.aiopenpocket.agent.core.persona

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaPromptBuilderTest {

  @Test
  fun `every persona prompt contains shared catalog content`() {
    PersonaCatalog.all.forEach { persona ->
      val prompt = PersonaPromptBuilder.build(persona)

      assertTrue("${persona.id}: missing identity anchor", prompt.contains("тон и стиль каждого ответа"))
      assertTrue("${persona.id}: missing tone", prompt.contains(persona.tone))
      persona.principles.forEach { principle ->
        assertTrue("${persona.id}: missing principle '$principle'", prompt.contains(principle))
      }
      persona.quotes.forEach { quote ->
        assertTrue("${persona.id}: missing situation", prompt.contains(quote.situation))
        assertTrue("${persona.id}: missing voice line", prompt.contains(quote.line))
      }
    }
  }

  @Test
  fun `talkativeness selects one explicit length rule`() {
    val silent = PersonaPromptBuilder.build(required("silent_butler"))
    val sharp = PersonaPromptBuilder.build(required("sharp_partner"))
    val friendly = PersonaPromptBuilder.build(required("friendly_companion"))

    assertTrue(silent.contains("односложно"))
    assertTrue(sharp.contains("Отвечай по делу"))
    assertTrue(friendly.contains("Можно отвечать живее и поддерживать разговор"))
  }

  @Test
  fun `sharp partner has directness markers`() {
    val prompt = PersonaPromptBuilder.build(required("sharp_partner"))

    assertTrue(prompt.contains("не извиняйся", ignoreCase = true))
    assertTrue(prompt.contains("скажи это прямо", ignoreCase = true))
  }

  @Test
  fun `builder does not recreate global shortness command`() {
    PersonaCatalog.all.forEach { persona ->
      val prompt = PersonaPromptBuilder.build(persona)
      assertFalse("${persona.id}: forbidden duplicate", prompt.contains("Действуй кратко"))
    }
  }

  private fun required(id: String): PersonaInfo =
    requireNotNull(PersonaCatalog.findById(id))
}
```

- [ ] **Step 2: Запустить тест и подтвердить RED**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.aiopenpocket.agent.core.persona.PersonaPromptBuilderTest"
```

Expected: compilation FAIL — `Unresolved reference: PersonaPromptBuilder`.

- [ ] **Step 3: Реализовать builder**

Создать `app/src/main/java/com/aiopenpocket/agent/core/persona/PersonaPromptBuilder.kt`:

```kotlin
package com.aiopenpocket.agent.core.persona

// @trace swappable-persona-e5e65e

object PersonaPromptBuilder {

  fun build(persona: PersonaInfo): String = buildString {
    appendLine("Это твой характер: он определяет тон и стиль каждого ответа.")
    appendLine("Сохраняй этот голос на протяжении всего диалога, даже если старые ответы звучат иначе.")
    appendLine()
    appendLine("Тон: ${persona.tone}.")
    appendLine()
    appendLine("Принципы:")
    persona.principles.forEach { appendLine("- $it") }
    appendLine()
    appendLine("Длина ответа:")
    appendLine("- ${talkativenessRule(persona.stats.talkativeness)}")
    appendLine()
    appendLine("Примеры голоса:")
    persona.quotes.forEach { quote ->
      appendLine("- Пользователь: «${quote.situation}»")
      appendLine("  Ты: ${quote.line}")
    }
  }.trim()

  private fun talkativenessRule(value: Int): String = when {
    value <= 20 -> "Отвечай односложно, без приветствий и лишних пояснений."
    value <= 60 -> "Отвечай по делу; подробности добавляй, когда они нужны задаче или запрошены."
    else -> "Можно отвечать живее и поддерживать разговор, не раздувая ответ без пользы."
  }
}
```

Builder намеренно не разворачивает численные `skepticism`, `criticism`, `proactivity`, `humor`, `empathy`: характер задают вручную написанные principles и общие quotes, а не нестабильная генерация правил из чисел.

- [ ] **Step 4: Запустить тесты каталога и builder**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.aiopenpocket.agent.core.persona.PersonaCatalogTest" --tests "com.aiopenpocket.agent.core.persona.PersonaPromptBuilderTest"
```

Expected: BUILD SUCCESSFUL, 6 tests pass.

- [ ] **Step 5: Проверить трассировку**

Run: `traxer check`

Expected: новый `PersonaPromptBuilder.kt` связан с `swappable-persona-e5e65e`; broken links отсутствуют.

- [ ] **Step 6: Commit**

```powershell
git add app/src/main/java/com/aiopenpocket/agent/core/persona/PersonaPromptBuilder.kt app/src/test/java/com/aiopenpocket/agent/core/persona/PersonaPromptBuilderTest.kt
git commit -m @'
feat(persona): собирать устойчивый системный голос

Чистый builder превращает единый каталог в явные правила длины,
поведения и few-shot примеры голоса.

Trace: swappable-persona-e5e65e
'@
```

---

### Task 3: Переключать персону в существующем обычном чате

**Files:**
- Modify: `app/src/main/java/com/example/agent/core/prompt/PrePromptAssembler.kt:5-12,34-77,121-132,194-195`
- Create: `app/src/test/java/com/aiopenpocket/agent/core/prompt/PrePromptAssemblerPersonaTest.kt`

**Interfaces:**
- Consumes: `PersonaCatalog.findById(id)` и `PersonaPromptBuilder.build(persona)` из Tasks 1–2; `UserPreferencesRepository.getDefaultPersonaId()`.
- Produces: `PrePromptAssembler.assemble()` всегда резолвит глобальную персону на текущем ходе обычного чата; неизвестный id безопасно падает в `sharp_partner`.
- Preserves: при `conversation.agentId != null` prompt содержит `## Личность и роль`, но не содержит `## Персона`.

- [ ] **Step 1: Написать интеграционные тесты prompt assembly**

Создать `PrePromptAssemblerPersonaTest.kt`:

```kotlin
package com.aiopenpocket.agent.core.prompt

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aiopenpocket.agent.core.identity.UserPreferencesRepository
import com.aiopenpocket.agent.core.memory.repository.ConversationRepository
import com.aiopenpocket.agent.core.memory.repository.MemoryRepository
import com.aiopenpocket.agent.core.persona.PersonaCatalog
import com.aiopenpocket.agent.core.persona.PersonaPromptBuilder
import com.aiopenpocket.agent.core.skill.SkillRegistry
import com.aiopenpocket.data.database.AppDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PrePromptAssemblerPersonaTest {

  private lateinit var context: Context
  private lateinit var db: AppDatabase
  private lateinit var conversations: ConversationRepository
  private lateinit var preferences: UserPreferencesRepository
  private lateinit var assembler: PrePromptAssembler

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    conversations = ConversationRepository(
      db.conversationDao(),
      db.conversationTagDao(),
      db.messageDao(),
    )
    preferences = UserPreferencesRepository(context)
    assembler = PrePromptAssembler(
      assets = AssetSource { path ->
        when (path) {
          "prompts/system_base.md" -> "BASE RULES"
          else -> null
        }
      },
      memoryRepository = MemoryRepository(db.factDao(), db.episodeDao()),
      conversationRepository = conversations,
      skillRegistry = SkillRegistry(emptySet()),
      userPreferencesRepository = preferences,
    )
  }

  @After
  fun tearDown() = db.close()

  @Test
  fun `settings change replaces persona in an existing ordinary chat`() = runTest {
    val conversationId = conversations.createConversation(
      title = "Старый чат",
      personaId = "silent_butler",
    )
    preferences.setDefaultPersonaId("friendly_companion")

    val prompt = assembler.assemble(conversationId, "Как дела?").systemPrompt
    val expected = PersonaPromptBuilder.build(requiredPersona("friendly_companion"))
    val stale = PersonaPromptBuilder.build(requiredPersona("silent_butler"))

    assertTrue(prompt.contains(expected))
    assertFalse(prompt.contains(stale))
  }

  @Test
  fun `unknown setting falls back to sharp partner`() = runTest {
    val conversationId = conversations.createConversation(title = "Чат")
    preferences.setDefaultPersonaId("removed_persona")

    val prompt = assembler.assemble(conversationId, "Проверка").systemPrompt

    assertTrue(prompt.contains(PersonaPromptBuilder.build(requiredPersona("sharp_partner"))))
  }

  @Test
  fun `mini agent role replaces user persona`() = runTest {
    val conversationId = conversations.createConversation(
      title = "Бюджет",
      agentId = "budget",
    )
    preferences.setDefaultPersonaId("friendly_companion")

    val prompt = assembler.assemble(conversationId, "Покажи расходы").systemPrompt

    assertTrue(prompt.contains("## Личность и роль"))
    assertFalse(prompt.contains("## Персона"))
    assertFalse(prompt.contains(PersonaPromptBuilder.build(requiredPersona("friendly_companion"))))
  }

  @Test
  fun `ordinary prompt has no duplicate global shortness block`() = runTest {
    val conversationId = conversations.createConversation(title = "Чат")
    preferences.setDefaultPersonaId("sharp_partner")

    val prompt = assembler.assemble(conversationId, "Проверка").systemPrompt

    assertFalse(prompt.contains("## Инструкции"))
    assertFalse(prompt.contains("Действуй кратко"))
  }

  private fun requiredPersona(id: String) = requireNotNull(PersonaCatalog.findById(id))
}
```

- [ ] **Step 2: Запустить тест и подтвердить RED**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.aiopenpocket.agent.core.prompt.PrePromptAssemblerPersonaTest"
```

Expected: минимум три assertion FAIL:

- существующий чат содержит `silent_butler`, а не выбранный `friendly_companion`;
- неизвестный id даёт старую fallback-строку вместо builder для `sharp_partner`;
- prompt содержит `## Инструкции` и `Действуй кратко`.

- [ ] **Step 3: Перевести PrePromptAssembler на глобальную персону и builder**

Добавить imports:

```kotlin
import com.aiopenpocket.agent.core.persona.PersonaCatalog
import com.aiopenpocket.agent.core.persona.PersonaPromptBuilder
```

В параллельные чтения рядом с `languageDeferred` добавить:

```kotlin
      val personaIdDeferred = async { userPreferencesRepository.getDefaultPersonaId() }
```

После `val language = languageDeferred.await()` добавить:

```kotlin
      val personaId = personaIdDeferred.await()
```

Удалить старый блок строк 68–72:

```kotlin
      // Зависит от conversation (выше), поэтому идёт последовательно за ним.
      // Персона: у диалога своя (если задана), иначе — выбранная пользователем на онбординге, иначе дефолт.
      val personaId = conversation?.personaId ?: userPreferencesRepository.getDefaultPersonaId()
      val persona = loadAsset("personas/$personaId.md")
        ?: "Характер: Деловой напарник по умолчанию."
```

Вместо него использовать:

```kotlin
      // Персона — глобальная настройка и резолвится на каждом ходе. Сохранённый personaId диалога
      // остаётся только для совместимости схемы и не замораживает голос существующего чата.
      val persona = PersonaCatalog.findById(personaId)
        ?: requireNotNull(PersonaCatalog.findById(UserPreferencesRepository.DEFAULT_PERSONA_ID))
      val personaPrompt = PersonaPromptBuilder.build(persona)
```

В обычной ветке заменить:

```kotlin
          appendLine(persona)
```

на:

```kotlin
          appendLine(personaPrompt)
```

Удалить целиком финальный дублирующий блок:

```kotlin
        appendLine("## Инструкции")
        appendLine("Действуй кратко. Используй инструменты когда нужно. Не показывай внутренние ID.")
```

- [ ] **Step 4: Запустить prompt-тест**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.aiopenpocket.agent.core.prompt.PrePromptAssemblerPersonaTest"
```

Expected: BUILD SUCCESSFUL, 4 tests pass.

- [ ] **Step 5: Запустить связанные regression-тесты**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.aiopenpocket.agent.loop.ReflectionOrchestratorTest" --tests "com.aiopenpocket.agent.core.prompt.PrePromptAssemblerModeAssetsTest"
```

Expected: BUILD SUCCESSFUL. `ReflectionOrchestratorTest` продолжает собирать настоящий `PrePromptAssembler`; mode assets остаются доступны.

- [ ] **Step 6: Проверить трассировку**

Run: `traxer check`

Expected: без broken link. `PrePromptAssembler.kt` не обязан получать второй marker, потому что файл уже относится к другим prompt-фичам, а новый builder содержит marker персоны.

- [ ] **Step 7: Commit**

```powershell
git add app/src/main/java/com/example/agent/core/prompt/PrePromptAssembler.kt app/src/test/java/com/aiopenpocket/agent/core/prompt/PrePromptAssemblerPersonaTest.kt
git commit -m @'
fix(persona): применять настройку к текущему чату

Ассемблер резолвит глобальную персону на каждом ходе, сохраняет override
мини-агента и больше не добавляет конфликтующий блок общей краткости.

Trace: swappable-persona-e5e65e
'@
```

---

### Task 4: Удалить старый источник prompt-контента

**Files:**
- Modify: `app/src/main/assets/prompts/system_base.md:5-32`
- Delete: `app/src/main/assets/personas/attentive_secretary.md`
- Delete: `app/src/main/assets/personas/egalitarian_partner.md`
- Delete: `app/src/main/assets/personas/friendly_companion.md`
- Delete: `app/src/main/assets/personas/ironic_assistant.md`
- Delete: `app/src/main/assets/personas/sharp_partner.md`
- Delete: `app/src/main/assets/personas/silent_butler.md`
- Delete: `app/src/main/assets/personas/strict_coach.md`
- Create: `app/src/test/java/com/aiopenpocket/agent/core/persona/PersonaAssetRetirementTest.kt`

**Interfaces:**
- Consumes: `PersonaCatalog` и `PersonaPromptBuilder` уже полностью заменяют `personas/*.md`.
- Produces: один источник правды; базовые правила больше не задают одинаковую длину всем персонам.

- [ ] **Step 1: Написать падающий regression-тест удаления старого источника**

Создать `PersonaAssetRetirementTest.kt`:

```kotlin
package com.aiopenpocket.agent.core.persona

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaAssetRetirementTest {

  private val assetsDir: File = sequenceOf(File("src/main/assets"), File("app/src/main/assets"))
    .firstOrNull { it.isDirectory }
    ?: error("Не найден src/main/assets; cwd=${File(".").absolutePath}")

  @Test
  fun `legacy persona prompt assets are absent`() {
    val legacyDir = File(assetsDir, "personas")
    val markdown = legacyDir.listFiles { file -> file.extension == "md" }.orEmpty()

    assertTrue("Старые persona assets снова стали вторым источником правды: $markdown", markdown.isEmpty())
  }

  @Test
  fun `system base does not force one response length on every persona`() {
    val systemBase = File(assetsDir, "prompts/system_base.md").readText()

    assertFalse(systemBase.contains("Отвечай кратко"))
    assertTrue(systemBase.contains("Персона и эти правила заданы в системном контексте"))
  }
}
```

- [ ] **Step 2: Запустить тест и подтвердить RED**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.aiopenpocket.agent.core.persona.PersonaAssetRetirementTest"
```

Expected: 2 tests FAIL — семь `.md` существуют, а `system_base.md` содержит `Отвечай кратко`.

- [ ] **Step 3: Удалить глобальную краткость и перенумеровать правила**

В `system_base.md` удалить текущее правило 7:

```markdown
7. Отвечай кратко. Подробности — только по запросу.
```

Перенумеровать бывшие правила 8–13 в 7–12. Содержание правил не менять: защита от смены персоны через переписку и недоверенных `<user_data>`/`<tool_output>` должна сохраниться байт-в-байт, кроме номера.

- [ ] **Step 4: Удалить семь старых persona assets**

```powershell
git rm app/src/main/assets/personas/attentive_secretary.md app/src/main/assets/personas/egalitarian_partner.md app/src/main/assets/personas/friendly_companion.md app/src/main/assets/personas/ironic_assistant.md app/src/main/assets/personas/sharp_partner.md app/src/main/assets/personas/silent_butler.md app/src/main/assets/personas/strict_coach.md
```

Если после удаления каталог `app/src/main/assets/personas/` пуст, отдельно сохранять его не нужно.

- [ ] **Step 5: Запустить regression-тест**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.aiopenpocket.agent.core.persona.PersonaAssetRetirementTest"
```

Expected: BUILD SUCCESSFUL, 2 tests pass.

- [ ] **Step 6: Проверить отсутствие runtime-ссылок на persona assets**

Run:

```powershell
rg 'personas/|loadAsset\("personas' app/src/main app/src/test
```

Expected: пусто. Упоминания в `docs/superpowers/specs/2026-07-04-persona-activation-design.md` допустимы, потому что документ объясняет удаление.

- [ ] **Step 7: Проверить трассировку**

Run: `traxer check`

Expected: без broken link. Если `swappable-persona-e5e65e` помечена stale, вручную подтвердить, что rationale «переключать одну из 7 персон в Settings без перекомпиляции» всё ещё верен, и только затем выполнить:

```powershell
traxer bless swappable-persona-e5e65e
traxer check
```

- [ ] **Step 8: Commit**

```powershell
git add -A
git commit -m @'
refactor(persona): удалить второй источник промптов

Контент семи персон теперь живёт только в PersonaCatalog, а базовые правила
не навязывают одинаковую краткость всем характерам.

Trace: swappable-persona-e5e65e
'@
```

---

### Task 5: Полная проверка поведения и документации

**Files:**
- Verify: `docs/superpowers/specs/2026-07-04-persona-activation-design.md`
- Verify: `traceability.yaml`
- Verify: все production/test файлы из Tasks 1–4

**Interfaces:**
- Produces: проверенный end-to-end контракт без новых изменений архитектуры.

- [ ] **Step 1: Запустить весь JVM test suite**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL, все тесты зелёные.

- [ ] **Step 2: Запустить compile smoke**

Run:

```powershell
.\gradlew.bat :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Проверить итоговый diff**

Run:

```powershell
git diff --check
git status --short
git diff -- app/src/main/java/com/example/agent/core/persona/PersonaCatalog.kt app/src/main/java/com/aiopenpocket/agent/core/persona/PersonaPromptBuilder.kt app/src/main/java/com/example/agent/core/prompt/PrePromptAssembler.kt app/src/main/assets/prompts/system_base.md app/src/test/java/com/aiopenpocket/agent/core/persona app/src/test/java/com/aiopenpocket/agent/core/prompt/PrePromptAssemblerPersonaTest.kt
```

Expected:

- `git diff --check` ничего не выводит;
- diff не меняет Room schema;
- diff не добавляет `conversation.personaId` обратно в persona resolution;
- мини-агентная ветка по-прежнему не добавляет `## Персона`;
- в production-коде нет загрузки `personas/*.md`.

- [ ] **Step 4: Финальный traxer check**

Run: `traxer check`

Expected: без broken link, feature-without-code и неподтверждённых stale warning.

- [ ] **Step 5: Ручной smoke на устройстве или эмуляторе**

Для одного существующего обычного чата отправить одинаковый запрос после каждой смены в Settings:

```text
Я снова сорвал срок. Что делать?
```

Проверить:

1. `silent_butler` отвечает предельно коротко, без follow-up.
2. `sharp_partner` прямо оценивает ситуацию и предлагает один следующий шаг.
3. `friendly_companion` отвечает теплее и разговорнее.
4. Переключение действует в том же чате со следующего сообщения.
5. В чате мини-агента смена Settings не заменяет его доменную роль.

Если пункты 1–3 не различаются заметно, не увеличивать случайно все prompt-секции. Сначала сверить фактически отправленный system prompt с `PersonaPromptBuilderTest`: tone, principles, length rule и quotes должны присутствовать ровно один раз.

- [ ] **Step 6: Зафиксировать результат проверки**

Если Tasks 1–4 уже закоммичены и Step 5 не потребовал правок, отдельный пустой commit не создавать. В описании PR или handoff указать:

```text
Verification:
- .\gradlew.bat :app:testDebugUnitTest
- .\gradlew.bat :app:compileDebugKotlin
- traxer check
- manual: three personas differ in one existing chat; mini-agent override preserved
```

