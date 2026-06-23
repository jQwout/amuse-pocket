# ТЗ v0.7: Библиотека блоков и модель данных (фундамент Driven UI)

> **Версия:** 0.7 · **Дата:** 2026-06-15 · **Статус:** черновик на подумать (не согласовано)
> Фундаментальный документ. Под него [TZ_v05](TZ_v05_widgets_apps.md) (апы-композиции) и [TZ_v06](TZ_v06_chat_projects.md) (чат-проекты) встают как частные случаи. Воскрешает модель данных из запаркованного [TZ_v04](TZ_v04_DivKit.md) (§5, таблица `records`) **без DivKit**.

---

## 1. TL;DR

Один атом данных — **заметка** — и три независимых словаря над ним: **способности** (что заметка умеет), **операции** (как её агрегировать) и **UI-блоки** (как её показать). Приложение = тонкий **шаблон**, связывающий эти три словаря.

```
Приложение = данные(тип + способности) × операции × UI-блоки
```

Это снимает деление на «архетипы»: заметка, напоминалка, туду, бюджет — один объект с разными способностями и агрегацией. Рендерим существующими Compose-виджетами (`DrivenUiEngine`), храним в одной таблице, привязываем к тулколингу.

| Решение | Почему |
|---|---|
| Один атом `Note` + одна таблица `records` | Любой ап — это заметки; нет кода под каждый ап |
| Три отдельных словаря (способности / операции / блоки) | Ортогональность: новый ап = комбинация, а не новый движок |
| Блоки отвязаны от данных (`items[]` или `source`) | Один блок работает и как украшение чата, и как живой виджет апа |
| Шаблоны — предзаполненные, тонкие | LLM выбирает шаблон или собирает из примитивов (2 режима) |
| Без DivKit, без сервера | v1-граница (см. v05); рендер — `DrivenUiEngine` |

---

## 2. Позиционирование среди ТЗ

| Док | Роль | Отношение к v07 |
|---|---|---|
| v04 (DivKit) | ⏸️ запаркован | даёт модель данных (`records` + словарь экшенов); DivKit выкинут |
| **v07 (этот)** | **фундамент** | слои данных + операций + блоков + шаблонов |
| v05 (композиции) | частный случай | шаблон с `view: [блоки]` без слоя данных (display-only) |
| v06 (чат-проекты) | частный случай | шаблон, где главный блок — `chat` + заметки-память по namespace |

---

## 3. Слой 1 — Атом данных: `Note`

```kotlin
data class Note(
  val id: Long,
  val type: String,                  // note | reminder | todo_item | expense | flashcard | ...
  val title: String,
  val tags: List<String>,
  val content: String,
  // поля-способности (null, пока тип их не включит):
  val due: Long?      = null,        // schedulable
  val notify: Boolean? = null,       // schedulable
  val done: Boolean?  = null,        // checkable
  val amount: Double? = null,        // valued
  val unit: String?   = null,        // valued
  val fields: JsonObject? = null,    // structured (front/back, ингредиенты…)
  val createdAt: Long,
  val updatedAt: Long,
)
```

### Таблица `records` (Room, новая)

```sql
records(
  id          INTEGER PRIMARY KEY,
  type        TEXT NOT NULL,
  title       TEXT,
  tags        TEXT,          -- JSON array
  content     TEXT,
  due         INTEGER, notify INTEGER,   -- частые поля = колонки (для фильтров/сорта)
  done        INTEGER,
  amount      REAL, unit TEXT,
  fields      TEXT,          -- JSON для типизированных extra
  created_at  INTEGER, updated_at INTEGER
)
```
Гибрид: частые способности (`due/done/amount`) — колонки (быстрый `WHERE`/`ORDER BY`), редкое типизированное — в `fields` JSON. Индексы по `type`, `due`, `created_at`.

---

## 4. Слой 2 — Способности (миксины на заметку)

| Способность | Поля | Делает заметку |
|---|---|---|
| `schedulable` | due, notify | напоминалкой |
| `checkable` | done | пунктом туду |
| `valued` | amount, unit | строкой бюджета / замером |
| `structured` | fields{} | рецептом / флешкартой |

`TypeDescriptor` (в коде или assets) сопоставляет `type` → набор включённых способностей + JSON-схема `fields` для `structured`. Валидатор проверяет, что заметка не использует выключенные поля.

---

## 5. Слой 3 — Операции (словарь действий)

Наследник 8 экшенов из v04 §5. Фиксированный словарь, `when` на клиенте, без reflection.

| Операция | Аргументы | Делает |
|---|---|---|
| `create` | type, поля | INSERT в records |
| `update` | id, patch | UPDATE |
| `delete` | id | DELETE |
| `query` | type, filter, sort, limit | SELECT → данные для блока |
| `summarize` | type, field, op(sum/avg/count), groupBy(tag/day/…) | агрегат → данные для chart/stat |
| `schedule` | id, due, notify, repeat? | WorkManager + notification |
| `cancel_schedule` | id | отмена WorkManager |
| `toggle` | id, field(done) | переключить булево |

---

## 6. Слой 4 — Библиотека UI-блоков

Презентация, **отвязанная от данных**. Каждый блок: `name` + JSON-схема пропсов + способ привязки данных + интеракции. Реестр блоков — это и есть поверхность тулколинга для UI.

### Привязка данных у блока — два варианта
- **Статический:** `items: [...]` прямо в спеке (украшение чата, снапшот).
- **Живой:** `source: { query | summarize }` — клиент резолвит в данные из `records` при рендере (виджет апа, обновляется).

```jsonc
// живой блок: таблица последних расходов
{ "block": "table", "source": { "query": { "type": "expense", "sort": "-created_at", "limit": 20 } } }

// живой блок: график суммы по тегам
{ "block": "chart_bar", "source": { "summarize": { "type": "expense", "field": "amount", "op": "sum", "groupBy": "tag" } } }
```

### Реестр блоков

| Семья | Блок | Пропсы (схема, кратко) | Статус в коде |
|---|---|---|---|
| Атомы | `text` | text, style | есть |
| | `titled_card` | title, subtitle, icon? | **новый** |
| | `progress_card` | label, value, max | частично (timer) |
| | `checkbox_row` | label, checked, noteId? | есть (в todo) |
| | `input` | label, field, hint?, type | есть (form) |
| | `tag_chips` | tags[] | **новый** |
| | `stat` | label, value, unit?, delta? | **новый** |
| | `choice` | question, options[], answer? | есть |
| Коллекции | `todo_list` | title, items[] \| source | есть |
| | `table` | headers[], rows[][] \| source | есть |
| | `chart_bar` | title, labels[], values[] \| source | есть |
| | `sequential_steps` | title, steps[]{title,done} | **новый** (~roadmap) |
| | `card_list` | source, item_block | **новый** |
| Оболочка | `chat` | — | есть |
| | `input_bar` | — | есть |
| | `app_container` | title, blocks[] | = `AppCard` (v05) |
| Спец. | `flip_card` | front, back | **новый** |

Каждый блок отдаёт LLM JSON-схему пропсов — так же, как сейчас `RenderCardTool` отдаёт enum шаблонов, только по-блочно.

---

## 7. Слой 5 — Шаблоны приложений

Шаблон = тонкая декларация, связывающая данные + операции + блоки. Предзаполнен (`assets/apps/<id>.json`), LLM открывает через `open_app(id)`.

```jsonc
{
  "id": "budget",
  "title": "Бюджет",
  "data":  { "type": "expense", "capabilities": ["valued"] },
  "ops":   ["create", "query", "summarize"],
  "view": [
    { "block": "input", "props": { "fields": ["title", "amount"] }, "onSubmit": "create" },
    { "block": "chart_bar", "source": { "summarize": { "type": "expense", "field": "amount", "op": "sum", "groupBy": "tag" } } },
    { "block": "table", "source": { "query": { "type": "expense", "sort": "-created_at", "limit": 20 } } }
  ]
}
```

### Все апы каталога через формулу `данные × операции × блоки`

| Ап | type | Способности | Операции | Блоки |
|---|---|---|---|---|
| Заметка | note | — | create | titled_card / input |
| Напоминалка | reminder | schedulable | create, schedule | titled_card + due-toggle |
| Туду | todo_item | checkable (+schedulable) | create, query, toggle | todo_list |
| Бюджет | expense | valued | create, query, summarize | input + chart + table |
| Рецепт | recipe | structured(ingredients, steps) | create, query | table + sequential_steps |
| Флешкарты | flashcard | structured(front, back) | query + SR ⚠️ | flip_card ⚠️ |
| Лекарства | medication | schedulable | schedule(recurring ⚠️), log | todo_list + schedule |
| Шагомер | steps | valued | summarize (данные ← сенсор ⚠️) | stat + chart |

⚠️ = единственное реально новое сверх базы.

---

## 8. Привязка к тулколингу

| Уровень | Тулы | Режим LLM |
|---|---|---|
| **Данные** (над `records`) | `data_op(op, args)` — диспетчер по словарю §5 (или отдельные `data_create`/`data_query`/`data_summarize`/`data_schedule`) | работает с примитивами напрямую |
| **UI** | `render_view(blocks[])` — собрать экран; расширение нынешнего `render_card` | собирает экран из блоков |
| **Шаблоны** | `open_app(app_id)` — открыть предзаполненную связку | выбирает готовый ап |

**Два режима** (как открытый вопрос v05, но на двух слоях):
- **A — свобода:** LLM сам зовёт `data_op` + `render_view` (гибко, шире поверхность).
- **B — предзаполненные:** LLM зовёт `open_app(id)` (безопасно, предсказуемо). **Дефолт v1.**

---

## 9. Маппинг на существующий код

| reuse | `DrivenUiEngine` (виджеты → семя реестра блоков), `RenderCardTool` (→ `render_view`), `MessageEntity.cardSpec` (хранит спеку view), Room, Metro DI / `Tool` / `ToolRegistry`, WorkManager (`schedule`), `MemoryRepository` |
|---|---|
| **new** | `records` entity + DAO + `RecordsRepository`; словарь операций (`data_op`); резолвер `source` → данные в рендерере (живые блоки); реестр блоков с JSON-схемами; формат и загрузчик шаблонов; новые блоки (titled/progress/stat/steps/card_list/flip_card) по мере спроса |

Важно: живые блоки (`source`) делают карточку **не статическим снапшотом** — она перезапрашивает `records`. Статические блоки (`items[]`) — для украшений чата. Оба пути сосуществуют.

---

## 10. Безопасность / границы / квоты

- Операции — фиксированный словарь; невалидная операция не проходит (как v04).
- `type` шаблона и `app_id` — из реестра; свободная композиция (режим A) по умолчанию off в v1.
- Блоков в view ≤ 12, вложенность `app_container` = 1 (как v05).
- `source`-запросы только к `records`, лимит строк (напр. ≤ 200), без сырого SQL от LLM.
- Любая ошибка парсинга/резолва → markdown-fallback, никогда пустой бабл.
- Нет сети сверх LLM; сенсоры (шагомер) — отдельный адаптер, вне v1.

---

## 11. Стейджи

### Stage 1 — Ядро данных · ~2–3 дня
`records` entity + DAO + `RecordsRepository`; тулы `data_create/update/delete/query/summarize`; `schedule` через WorkManager. Без UI.
**Done when:** агент через тулы создаёт/запрашивает/суммирует заметки; данные переживают рестарт.

### Stage 2 — Реестр блоков + `render_view` · ~2 дня
Формализовать существующие виджеты как реестр с JSON-схемами; блок `app_container`; тул `render_view(blocks[])`. Пока только статические блоки.
**Done when:** LLM собирает экран из нескольких блоков в одном бабле.

### Stage 3 — Привязка данных · ~1.5 дня
Резолвер `source` (`query`/`summarize`) в рендерере → живые блоки.
**Done when:** `table`/`chart` с `source` показывают реальные данные из `records` и обновляются.

### Stage 4 — Шаблоны · ~2 дня
Формат шаблона + загрузчик + `open_app(id)`; портировать 3 базовых апа: заметка, туду, бюджет.
**Done when:** «открой бюджет» → ап из шаблона работает end-to-end (ввод → запись → график/таблица).

### Stage 5 — Новые блоки/операции · инкрементально
`titled_card`, `progress_card`, `stat`, `sequential_steps`, `card_list`; затем `flip_card` + spaced-repetition (флешкарты), recurring schedule (лекарства) — по спросу.

### Stage 6 — Полировка / тесты · ~1 день
Пустые состояния, длинные списки, битый `source`, fallback; unit-тесты на резолвер/операции/валидатор.

**Итого ядро (Stage 1–4): ~7.5–8.5 дней.**

---

## 12. Что осознанно НЕ делаем (v1)

- DivKit, сервер, свободная LLM-композиция по умолчанию (режим A — off).
- Сенсорные адаптеры (шагомер), голос/мультимодальность.
- Пользовательские шаблоны «с нуля», связи между апами, шаринг.
- Сырой SQL от LLM — только словарь операций.

---

## 13. Открытые вопросы

1. Способности как колонки vs всё в `fields` JSON? (предлагаю гибрид — §3)
2. Один тул `data_op(op, args)` vs набор отдельных тулов? (меньше поверхность vs яснее схемы)
3. Дефолт блока — статический или живой? (предлагаю: в чате статический, в апах живой)
4. Где живут шаблоны — `assets/apps/*.json` (как v05) — и как с ними связан namespace чат-проектов (v06)?
5. `render_card` мигрируем в `render_view` или оставляем рядом на переходный период?
6. Единый стор: `records` отдельно от `facts/episodes`, или эпизоды/факты тоже частный случай заметок? (большой вопрос — возможно объединение памяти и данных)
