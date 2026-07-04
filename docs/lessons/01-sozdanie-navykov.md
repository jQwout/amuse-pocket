# Урок: создание навыков (skills) для AI-ассистента

> **Цель урока.** Понять, что такое «навык» AI-ассистента, научиться добавлять
> новый навык в это приложение и увидеть, что та же самая концепция работает в
> любых других системах (Claude Code Skills, function-calling, веб-приложения).
>
> **Можно ли сделать навык, который работает в моём приложении, но концептуально
> переносится в другие?** Да. Именно этому и посвящён урок: навык — это не код
> под конкретный фреймворк, а *контракт между моделью и хостом*. Код меняется,
> контракт — нет.

---

## 1. Что такое «навык» и чем он отличается от персоны

В нашем приложении уже есть два разных понятия, и их важно не путать:

| Понятие | Файл / место | Отвечает за вопрос | Пример |
|---|---|---|---|
| **Персона** | `app/src/main/assets/personas/*.md` | *Кто* ассистент (тон, характер) | «Тихий дворецкий», «Строгий коуч» |
| **Навык** | `render_card`-шаблоны: объявлены в `RenderCardTool.kt`, отрисованы в `DrivenUiEngine.kt` | *Что* ассистент умеет сделать | показать `todo_list`, запустить `timer`, нарисовать `chart_bar` |

**Навык — это конкретное умение, которое модель может вызвать по-настоящему —
не текстом, а вызовом инструмента (tool call) со структурированными
аргументами.** Модель не «рисует» карточку и не вставляет JSON в текст ответа —
она вызывает инструмент `render_card` с аргументами `template` и `params`, а
*хост* (наше приложение) получает этот вызов и по нему рисует карточку.

Сейчас в приложении 10 навыков (шаблонов карточки). Найдите их в
`RenderCardTool.kt` (список шаблонов в `schema` и в `description`) и в
`DrivenUiEngine.kt` (ветки `when (template)` и `@Composable *CardView`):
`todo_list`, `weather_card`, `timer`, `chart_bar`, `table`, `form`, `choice`,
`lesson_complete`, `switch`, `notification_permission`.

> Не путайте это с интерфейсом `Tool` в коде: `render_card` — лишь один из
> примерно десятка зарегистрированных инструментов (наравне с `FileTools`,
> `CalendarTool`, `EmailTool` и др. — см. `AgentToolsModule.kt`). Просто
> `render_card` устроен как «один инструмент с полем-дискриминатором
> `template`», поэтому внутри него живёт сразу 10 навыков-карточек, а не
> потому что каждый навык — это отдельный `Tool`.

---

## 2. Универсальная анатомия навыка (работает в любой системе)

Любой навык, в любом фреймворке, состоит из четырёх частей:

1. **Триггер / объявление** — как модель узнаёт, что навык существует и когда
   его применять. У нас это JSON Schema инструмента `render_card`
   (`RenderCardTool.schema`) — модель получает её как часть настоящего
   function-calling API, а не как подсказку в тексте промпта.
2. **Контракт (схема)** — формат данных навыка: имя + параметры. У нас это
   `{ "template": "...", "params": { ... } }`, причём `template` ограничен
   реальным `enum` в схеме, а не просто примером в описании.
3. **Парсер / валидатор** — превращает результат вызова инструмента в
   типизированный объект и отсекает мусор. У нас это
   `DrivenUiEngine.parseJsonString`.
4. **Обработчик / рендерер** — то, что реально *делает* работу. У нас это
   `@Composable`-функции, рисующие карточку.

```
        ОБЪЯВЛЕНИЕ                 КОНТРАКТ                ПАРСЕР            ОБРАБОТЧИК
  (схема инструмента)  →  (tool call: template+params) →  (parseJsonString) →  (Composable)
   RenderCardTool.schema        аргументы вызова            текст → объект       объект → UI
```

Запомните эти 4 части — в разделе 5 мы покажем, что в Claude Code Skills и в
«обычном» function-calling они устроены ровно так же — благо у нас самих это
уже настоящий function-calling, а не его имитация.

---

## 3. Как навык устроен в нашем приложении (разбор существующего)

Проследим путь навыка `timer` через код.

**(1) Объявление** — `RenderCardTool.kt`. Шаблон `timer` — один из значений
`enum` на параметре `template` внутри `schema`:

```kotlin
"template" to JsonObject(mapOf(
    "type" to JsonPrimitive("string"),
    "enum" to JsonArray(listOf(
        JsonPrimitive("todo_list"), JsonPrimitive("weather_card"), JsonPrimitive("timer"),
        // ...остальные шаблоны
    )),
    "description" to JsonPrimitive("ID шаблона карточки"),
)),
```

Инструмент регистрируется через DI (`AgentToolsModule.kt`) в общий
`Set<Tool>` → `ToolRegistry` → `getAllSchemas()`, и именно этот список схем
`ReactLoopRunner` передаёт модели вызовом `llmClient.streamChat(messages,
tools, provider)`. Когда пользователь просит таймер, модель делает настоящий
tool call:

```json
{
  "name": "render_card",
  "arguments": {
    "template": "timer",
    "params": { "label": "Таймер фокуса", "duration_sec": 120 }
  }
}
```

Это приходит в `ReactLoopRunner` как `StreamEvent.ToolCall(id, name,
arguments)` — уже разобранные, типизированные аргументы, а не текст, из
которого нужно что-то вычленять. `ToolExecutor` находит инструмент по имени
и вызывает `RenderCardTool.execute(arguments)`, который просто собирает
`template` и `params` обратно в JSON-строку `{"template":"timer","params":{...}}`
и возвращает её как `ToolResult.Success`.

**(2) Контракт** — это и есть сам JSON: ключ `template` выбирает навык, `params`
несёт его аргументы. Оба поля — `required` в схеме инструмента.

**(3) Парсер** — `DrivenUiEngine.parseJsonString` читает `template` и собирает
типизированный объект:

```kotlin
"timer" -> {
    val label = params.optString("label", "Таймер")
    val durationSec = params.optInt("duration_sec", 60)
    DrivenUiCard.TimerCard(label, durationSec)
}
```

Обратите внимание на `optString(..., "Таймер")` — у каждого поля есть **значение
по умолчанию**. Это защищает хост, если модель что-то пропустила. Неизвестный
`template` падает в `ErrorCard`, а битый JSON ловится `catch`. Навык обязан быть
устойчивым к «почти правильному» вводу модели — здесь ничего не изменилось:
`enum` в схеме — это сильная подсказка модели, но не гарантия во время
выполнения, поэтому `parseJsonString` всё равно должен уметь получить
значение `template`, которого не ждали.

**(4) Обработчик** — `TimerCardView`, который рисует круг прогресса и кнопки
Старт/Пауза/Сброс. Он подключается веткой `is DrivenUiCard.TimerCard ->
TimerCardView(card)` внутри `RenderDrivenUiCard`.

Есть и пятая точка, которой в этом разборе легко не заметить —
`DrivenUiCard.toMarkdown()`. Это отдельный `when`, который рисует ту же
карточку как markdown-текст; им пользуется `ChatScreen.kt`, когда в
настройках выключены интерактивные карточки. Для `timer` он выглядит так:
`is DrivenUiCard.TimerCard -> "**$label** — таймер на $durationSec сек."`.

Итого, чтобы навык существовал, нужны: значение в `enum` схемы
`RenderCardTool`, вариант в `sealed class DrivenUiCard`, ветка в
`parseJsonString`, ветка в `toMarkdown()`, ветка в `RenderDrivenUiCard` и
`@Composable`-рендерер. Шесть точек — ровно их мы и тронем дальше (пять из
них — в `DrivenUiEngine.kt`, одна — в `RenderCardTool.kt`).

---

## 4. Практика: добавляем новый навык `progress` за 6 шагов

Сделаем навык «прогресс к цели» — подпись + текущее/целевое значение + полоса
прогресса. Это отдельный навык, не дублирующий `chart_bar` (там несколько
столбцов) и `timer` (там обратный отсчёт).

Контракт, который мы хотим:

```json
{
  "template": "progress",
  "params": { "label": "Прочитано книг за год", "current": 7, "target": 12, "unit": "шт" }
}
```

### Шаг 1. Объявить шаблон в схеме инструмента

В `RenderCardTool.kt` добавьте `"progress"` в `enum` параметра `template`
внутри `schema`:

```kotlin
"enum" to JsonArray(listOf(
    JsonPrimitive("todo_list"), JsonPrimitive("weather_card"), JsonPrimitive("timer"),
    JsonPrimitive("chart_bar"), JsonPrimitive("table"), JsonPrimitive("form"),
    JsonPrimitive("choice"), JsonPrimitive("lesson_complete"), JsonPrimitive("switch"),
    JsonPrimitive("notification_permission"), JsonPrimitive("progress"),
)),
```

И допишите имя шаблона в `description` инструмента (строка
`"Доступные шаблоны: todo_list, ..."`), чтобы он тоже упоминал `progress`.

> Совет: `params` в схеме объявлен как обычный `object` — **без** вложенной
> схемы на каждый шаблон, так что единственное текстовое место, где модель
> может прочитать про форму `params` конкретного шаблона — это `description`
> инструмента. Для большинства шаблонов хватает и имени (`current`/`target`/
> `unit` в целом понятны), но если бы поля были неочевидны, стоило бы
> добавить короткое пояснение прямо в `description` — как уже сделано для
> `switch` и `notification_permission`.

### Шаг 2. Описать данные навыка

В `DrivenUiEngine.kt`, в `sealed class DrivenUiCard`, добавьте вариант:

```kotlin
data class ProgressCard(
    val label: String,
    val current: Float,
    val target: Float,
    val unit: String
) : DrivenUiCard()
```

### Шаг 3. Распарсить контракт

В `parseJsonString`, в блоке `when (template)`, добавьте ветку (перед `else`):

```kotlin
"progress" -> {
    val label = params.optString("label", "Прогресс")
    val current = params.optDouble("current", 0.0).toFloat()
    val target = params.optDouble("target", 1.0).toFloat()
    val unit = params.optString("unit", "")
    DrivenUiCard.ProgressCard(label, current, target, unit)
}
```

Каждое поле — со значением по умолчанию, как и у остальных навыков.

### Шаг 4. Добавить markdown-фолбэк

В `DrivenUiEngine.kt`, в `fun DrivenUiCard.toMarkdown()`, добавьте ветку:

```kotlin
is DrivenUiCard.ProgressCard -> {
    val pct = if (target > 0f) (current / target * 100).toInt() else 0
    "**$label**: ${current.toInt()}/${target.toInt()} $unit ($pct%)"
}
```

> `toMarkdown()` — это исчерпывающий `when (this)` по `sealed class
> DrivenUiCard`, без `else`. Забудете эту ветку — проект просто не
> скомпилируется, Kotlin сам укажет на пропущенный `ProgressCard`. То же
> верно и для следующего шага (`RenderDrivenUiCard`). А вот `parseJsonString`
> из шага 3 компилятор не проверяет — там `when` по обычной строке, так что
> забытый шаблон тихо провалится в `ErrorCard`, и заметить это придётся
> самому.

### Шаг 5. Подключить рендерер

В `RenderDrivenUiCard`, в `when (card)`, добавьте строку:

```kotlin
is DrivenUiCard.ProgressCard -> ProgressCardView(card)
```

### Шаг 6. Написать обработчик (рендерер)

Добавьте `@Composable`-функцию (рядом с другими `*CardView`):

```kotlin
@Composable
fun ProgressCardView(card: DrivenUiCard.ProgressCard) {
    val fraction = if (card.target > 0f) (card.current / card.target).coerceIn(0f, 1f) else 0f
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = card.label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(6.dp))
        )
        Text(
            text = "${card.current.toInt()} / ${card.target.toInt()} ${card.unit} " +
                "(${(fraction * 100).toInt()}%)",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline
        )
    }
}
```

Готово. Перезапустите приложение, попросите ассистента «покажи мой прогресс по
чтению: 7 из 12 книг» — модель вызовет инструмент `render_card` с
`template: "progress"`.

> **Контрольная проверка навыка.** Спросите себя по каждому новому навыку:
> 1. Добавлено ли значение в `enum` схемы `RenderCardTool` (и, если нужно,
>    пояснение в `description`)? 2. Есть ли вариант в `DrivenUiCard`?
> 3. Есть ли ветка в `parseJsonString` со значениями по умолчанию? 4. Есть ли
>    ветка в `toMarkdown()`? 5. Есть ли строка в `RenderDrivenUiCard`? 6. Есть
>    ли `@Composable`-рендерер?

---

## 5. Та же концепция в других системах (перенос навыка)

Это и есть ответ на «концептуально могли так же работать в других». Меняется
синтаксис — четыре части остаются.

**Claude Code Skills (`SKILL.md`).**
- *Объявление* — YAML-frontmatter `name` + `description` (по описанию агент решает,
  когда применить навык).
- *Контракт* — тело `SKILL.md`: инструкции и при необходимости вспомогательные
  скрипты/файлы.
- *Парсер/обработчик* — сам агент исполняет инструкции и запускает скрипты.

**Function calling / tools (Claude API, OpenAI — и наш `render_card`).**
- *Объявление + контракт* — JSON Schema инструмента (`name`, `description`,
  `input_schema`/`parameters`). У нас это ровно `RenderCardTool.schema` — то
  есть это не аналогия, а буквально тот же механизм. Отличие от «типичного»
  function-calling лишь в том, что вместо N отдельных инструментов (условные
  `show_todo_list`, `show_timer`, ...) мы завели один инструмент `render_card`
  с полем-дискриминатором `template` — экономим на числе объявленных
  инструментов ценой более слабой типизации: `params` остаётся простым
  `object` без отдельной схемы на каждый шаблон.
- *Парсер* — SDK/хост уже отдаёт распарсенные аргументы (`arguments:
  Map<String, JsonElement>` в нашем `StreamEvent.ToolCall`). У нас есть и
  второй, внутренний парсер — `DrivenUiEngine.parseJsonString` — именно
  потому, что `params` не типизирован в самой схеме и должен быть разобран
  вручную после того, как аргументы уже пришли.
- *Обработчик* — ваша функция на сервере; у нас — `RenderCardTool.execute()` и
  дальше `DrivenUiEngine`.

**Веб-приложение / другой клиент.**
- Тот же JSON-контракт `{template, params}` можно отрисовать React-компонентом
  вместо `@Composable`. Контракт переносится без изменений — меняется только
  рендерер.

Вывод для проектирования: **держите контракт навыка независимым от платформы**
(простой, самодостаточный JSON со значениями по умолчанию), и тогда один и тот же
навык переносится между вашим приложением и любым другим хостом.

---

## 6. Задание

1. Добавьте навык `progress` по шагам из раздела 4 и проверьте его в чате.
2. Самостоятельно добавьте ещё один навык — например `rating` (звёзды 1–5) или
   `countdown` (дата-цель). Пройдите все 6 точек подключения.
3. Наш `render_card` держит `params` нетипизированным (`object` без вложенной
   схемы). Опишите `params` вашего нового навыка так, как если бы он был
   отдельным function-calling-инструментом со строгим `input_schema`
   (`properties`, `required`, типы полей) — и сравните с тем, что реально
   проверяет (и чего не проверяет) схема `RenderCardTool`.

## 7. Частые ошибки

- **Забыли добавить шаблон в `enum` схемы `RenderCardTool`** — код есть, но
  модель либо не вызывает навык (не знает о нём), либо схема на некоторых
  провайдерах отклонит вызов с незнакомым значением `template`.
- **Забыли ветку в `toMarkdown()` или `RenderDrivenUiCard`** — тут вас спасёт
  компилятор: оба `when` исчерпывающие по `sealed class DrivenUiCard`, без
  ветки проект не соберётся. А вот пропущенную ветку в `parseJsonString`
  компилятор не заметит — она просто тихо превратится в `ErrorCard`.
- **Нет значений по умолчанию в парсере** — навык падает, если модель пропустила
  поле. Всегда задавайте дефолты.
- **Слишком сложный контракт** — чем больше обязательных полей, тем чаще модель
  ошибается. Делайте навык маленьким и устойчивым.
- **Логика навыка завязана на платформу** — тогда его нельзя перенести. Держите
  бизнес-смысл в контракте, а не в рендерере.
